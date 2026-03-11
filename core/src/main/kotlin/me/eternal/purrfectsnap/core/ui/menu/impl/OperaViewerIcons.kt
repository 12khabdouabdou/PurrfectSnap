package me.eternal.purrfectsnap.core.ui.menu.impl

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfectsnap.common.ui.createComposeView
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.event.events.impl.OnSnapInteractionEvent
import me.eternal.purrfectsnap.core.features.impl.downloader.MediaDownloader
import me.eternal.purrfectsnap.core.features.impl.downloader.OperaViewerMessageContext
import me.eternal.purrfectsnap.core.features.impl.messaging.AutoMarkAsRead
import me.eternal.purrfectsnap.core.ui.children
import me.eternal.purrfectsnap.core.ui.iterateParent
import me.eternal.purrfectsnap.core.ui.menu.AbstractMenu
import me.eternal.purrfectsnap.core.ui.randomTag
import me.eternal.purrfectsnap.core.ui.triggerCloseTouchEvent
import me.eternal.purrfectsnap.core.util.SNAPCHAT_13_80_VERSION
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.util.isSnapchatVersionAtLeast
import me.eternal.purrfectsnap.core.util.ktx.vibrateLongPress
import me.eternal.purrfectsnap.mapper.impl.OperaPageViewControllerMapper

class OperaViewerIcons : AbstractMenu() {
    private val actionMenuIconSize by lazy { context.userInterface.dpToPx(32) }
    private val actionMenuIconMargin by lazy { context.userInterface.dpToPx(5) }
    private val actionMenuIconMarginTop by lazy { context.userInterface.dpToPx(10) }
    private val injectedParentTag = randomTag()
    private val viewerVisibleState = mutableStateOf(false)
    private val viewerMessageContextState = mutableStateOf<OperaViewerMessageContext?>(null)
    private val inlineMarkButtonVisibleState = mutableStateOf(false)
    private var overlayRegistered = false
    private var hooksInitialized = false
    private val useModernViewerBehavior by lazy {
        isSnapchatVersionAtLeast(
            context.mappings.getSnapchatPackageInfo()?.versionName,
            SNAPCHAT_13_80_VERSION
        )
    }

    override fun init() {
        if (!useModernViewerBehavior) return
        if (hooksInitialized) return
        hooksInitialized = true

        registerOverlayFallback()

        context.event.subscribe(OnSnapInteractionEvent::class) {
            viewerMessageContextState.value = context.feature(MediaDownloader::class).resolveCurrentSnapMessageContext()
        }

        context.mappings.useMapper(OperaPageViewControllerMapper::class) {
            arrayOf(onDisplayStateChange, onDisplayStateChangeGesture).forEach { methodName ->
                classReference.get()?.hook(
                    methodName.get() ?: return@forEach,
                    HookStage.AFTER
                ) { param ->
                    val viewState = param.thisObject<Any>().getObjectField(viewStateField.get()!!).toString()
                    val isVisible = viewState == "FULLY_DISPLAYED"
                    viewerVisibleState.value = isVisible

                    if (!isVisible) {
                        viewerMessageContextState.value = null
                        inlineMarkButtonVisibleState.value = false
                        return@hook
                    }

                    viewerMessageContextState.value = context.feature(MediaDownloader::class).resolveCurrentSnapMessageContext()
                }
            }
        }
    }

    private fun registerOverlayFallback() {
        if (overlayRegistered) return
        overlayRegistered = true

        context.inAppOverlay.addCustomComposable {
            val messageContext = viewerMessageContextState.value
            if (
                !context.config.messaging.markSnapAsSeenButton.get() ||
                !viewerVisibleState.value ||
                inlineMarkButtonVisibleState.value ||
                messageContext == null
            ) return@addCustomComposable

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 18.dp, bottom = 118.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Surface(
                    modifier = Modifier
                        .size(52.dp)
                        .clickable {
                            context.coroutineScope.launch {
                                markCurrentSnapAsSeen(parent = null)
                            }
                        },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.RemoveRedEye,
                            tint = Color.White,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }

    private fun Class<*>?.hasNameSuffixInHierarchy(suffix: String): Boolean {
        var current = this
        while (current != null) {
            if (current.name.endsWith(suffix)) return true
            current = current.superclass
        }
        return false
    }

    private fun shouldInjectIntoViewer(event: AddViewEvent): Boolean {
        if (event.view !is FrameLayout) return false
        if (!event.parent.javaClass.hasNameSuffixInHierarchy("OpenLayout")) return false

        val viewGroup = event.view as? ViewGroup ?: return false
        if (viewGroup.getTag(injectedParentTag) != null) return false

        val hasOnlyImageChildren = viewGroup.childCount > 0 && viewGroup.children().all { it is ImageView }
        val hasMaskFrameSibling = event.parent.children().any {
            it.javaClass.hasNameSuffixInHierarchy("ScalableCircleMaskFrameLayout")
        }

        return hasOnlyImageChildren || hasMaskFrameSibling
    }

    private fun resolveCurrentMessageContext(mediaDownloader: MediaDownloader): OperaViewerMessageContext? {
        return mediaDownloader.resolveCurrentSnapMessageContext()?.also {
            viewerMessageContextState.value = it
        }
    }

    private fun syncInlineMarkButtonVisibility(view: View, mediaDownloader: MediaDownloader) {
        val isVisible = resolveCurrentMessageContext(mediaDownloader) != null
        view.visibility = if (isVisible) View.VISIBLE else View.GONE
        inlineMarkButtonVisibleState.value = isVisible
    }

    private suspend fun markCurrentSnapAsSeen(parent: ViewGroup?) {
        val messageContext = resolveCurrentMessageContext(context.feature(MediaDownloader::class)) ?: return
        val result = context.feature(AutoMarkAsRead::class).markSnapAsSeen(
            messageContext.conversationId,
            messageContext.clientMessageId
        )

        if (result == "DUPLICATEREQUEST" || result == null) {
            if (context.config.messaging.skipWhenMarkingAsSeen.get()) {
                withContext(Dispatchers.Main) {
                    if (parent != null) {
                        parent.iterateParent {
                            it.triggerCloseTouchEvent()
                            false
                        }
                    } else {
                        context.mainActivity
                            ?.findViewById<View>(android.R.id.content)
                            ?.triggerCloseTouchEvent()
                    }
                }
            }
        }

        if (result == "DUPLICATEREQUEST") return
        if (result == null) {
            context.inAppOverlay.showStatusToast(
                Icons.Default.Info,
                context.translation["mark_as_seen.seen_toast"],
                durationMs = 800
            )
        } else {
            context.inAppOverlay.showStatusToast(
                Icons.Default.Info,
                "Failed to mark as seen: $result",
            )
        }
    }

    override fun onViewAdded(event: AddViewEvent) {
        if (!useModernViewerBehavior) {
            if (event.view is FrameLayout && event.parent.javaClass.superclass?.name?.endsWith("OpenLayout") == true) {
                val viewGroup = event.view as? ViewGroup ?: return
                if (
                    viewGroup.childCount == 0 ||
                    viewGroup.children().any { it !is ImageView } ||
                    event.parent.children().none { it.javaClass.name.endsWith("ScalableCircleMaskFrameLayout") }
                ) return
                inject(viewGroup)
            }
            return
        }
        if (!shouldInjectIntoViewer(event)) return
        val viewGroup = event.view as? ViewGroup ?: return
        viewGroup.setTag(injectedParentTag, true)
        viewerVisibleState.value = true
        viewGroup.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                viewerVisibleState.value = true
            }

            override fun onViewDetachedFromWindow(v: View) {
                viewerVisibleState.value = false
                inlineMarkButtonVisibleState.value = false
            }
        })
        inject(viewGroup)
    }

    private fun inject(parent: ViewGroup) {
        val mediaDownloader = context.feature(MediaDownloader::class)

        if (context.config.downloader.operaDownloadButton.get()) {
            parent.addView(LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, actionMenuIconMarginTop * 2 + actionMenuIconSize, 0, 0)
                    marginEnd = actionMenuIconMargin
                    gravity = Gravity.TOP or Gravity.END
                }
                addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.visibility = View.VISIBLE
                        (parent.parent as? ViewGroup)?.children()?.forEach { child ->
                            if (child !is ViewGroup) return@forEach
                            child.children().forEach {
                                if (it::class.java.name.endsWith("PreviewToolbar")) v.visibility = View.GONE
                            }
                        }
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })

                addView(createComposeView(parent.context) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        tint = Color.White,
                        contentDescription = null
                    )
                }.apply {
                    setOnClickListener {
                        mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = false)
                    }
                    setOnLongClickListener {
                        context.vibrateLongPress()
                        mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = true)
                        true
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        actionMenuIconSize,
                        actionMenuIconSize
                    ).apply {
                        setMargins(0, 0, 0, actionMenuIconMargin * 2)
                    }
                })
            }, 0)
        }

        if (context.config.messaging.markSnapAsSeenButton.get()) {
            if (!useModernViewerBehavior) {
                parent.addView(createComposeView(parent.context)  {
                    Icon(
                        imageVector = Icons.Default.RemoveRedEye,
                        tint = Color.White,
                        contentDescription = null
                    )
                }.apply {
                    setOnClickListener {
                        this@OperaViewerIcons.context.coroutineScope.launch {
                            markCurrentSnapAsSeen(parent)
                        }
                    }

                    addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            v.visibility = View.GONE
                            this@OperaViewerIcons.context.coroutineScope.launch(Dispatchers.Main) {
                                delay(250)
                                v.visibility = if (resolveCurrentMessageContext(mediaDownloader) != null) View.VISIBLE else View.GONE
                            }
                        }

                        override fun onViewDetachedFromWindow(v: View) {}
                    })

                    layoutParams = FrameLayout.LayoutParams(
                        (actionMenuIconSize * 1.5).toInt(),
                        (actionMenuIconSize * 1.5).toInt()
                    ).apply {
                        setMargins(0, 0, 0, actionMenuIconMarginTop * 2 + this@OperaViewerIcons.context.userInterface.dpToPx(80))
                        marginEnd = actionMenuIconMarginTop * 2
                        marginStart = actionMenuIconMarginTop * 2
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                })
                return
            }

            parent.addView(createComposeView(parent.context)  {
                Icon(
                    imageVector = Icons.Default.RemoveRedEye,
                    tint = Color.White,
                    contentDescription = null
                )
            }.apply {
                setOnClickListener {
                    this@OperaViewerIcons.context.coroutineScope.launch {
                        markCurrentSnapAsSeen(parent)
                    }
                }

                addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.visibility = View.GONE
                        inlineMarkButtonVisibleState.value = false
                        this@OperaViewerIcons.context.coroutineScope.launch(Dispatchers.Main) {
                            delay(250)
                            syncInlineMarkButtonVisibility(v, mediaDownloader)
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        inlineMarkButtonVisibleState.value = false
                    }
                })

                layoutParams = FrameLayout.LayoutParams(
                    (actionMenuIconSize * 1.5).toInt(),
                    (actionMenuIconSize * 1.5).toInt()
                ).apply {
                    setMargins(0, 0, 0, actionMenuIconMarginTop * 2 + this@OperaViewerIcons.context.userInterface.dpToPx(80))
                    marginEnd = actionMenuIconMarginTop * 2
                    marginStart = actionMenuIconMarginTop * 2
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            })
        }
    }
}
