package me.eternal.purrfectsnap.core.features.impl.experiments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.database.CursorWrapper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.common.data.FileType
import me.eternal.purrfectsnap.common.ui.createComposeView
import me.eternal.purrfectsnap.common.util.ktx.getLongOrNull
import me.eternal.purrfectsnap.common.util.ktx.getTypeArguments
import me.eternal.purrfectsnap.core.event.events.impl.ActivityResultEvent
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayPalette
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayTheme
import me.eternal.purrfectsnap.core.util.dataBuilder
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.mapper.impl.ChatMediaDrawerMapper
import java.io.InputStream
import java.lang.reflect.Method
import kotlin.random.Random

class MediaFilePicker : Feature("Media File Picker") {
    var lastMediaDuration: Long? = null
        private set

    private fun extractMediaDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context.androidContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    private fun resolveInputExtension(uri: Uri, mimeType: String?): String {
        val extensionFromMime = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.lowercase()
        val extensionFromUri = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).takeIf { !it.isNullOrBlank() }?.lowercase()

        return when (extensionFromMime ?: extensionFromUri ?: mimeType) {
            "video/mp4", "audio/mp4", "application/mp4", "mp4", "m4v" -> "mp4"
            "video/quicktime", "mov", "qt" -> "mov"
            "video/webm", "webm" -> "webm"
            "video/x-matroska", "video/mkv", "mkv" -> "mkv"
            "video/avi", "video/x-msvideo", "avi" -> "avi"
            "audio/mpeg", "audio/mp3", "mp3" -> "mp3"
            "audio/aac", "aac" -> "aac"
            "audio/ogg", "audio/opus", "opus", "ogg" -> "opus"
            "audio/wav", "audio/x-wav", "wav" -> "wav"
            "audio/mp4a-latm", "audio/x-m4a", "m4a" -> "m4a"
            else -> FileType.fromString(extensionFromMime ?: extensionFromUri).fileExtension ?: "mp4"
        }
    }

    @SuppressLint("Recycle")
    override fun init() {
        if (!context.config.experimental.mediaFilePicker.get()) return

        onNextActivityCreate(defer = true) {
            lateinit var chatMediaDrawerActionHandler: Any
            var sendItemsMethod: Method? = null
            var drawerViewClass: Class<*>? = null
            var sendItemsListItemClassFallback: Class<*>? = null

            context.mappings.useMapper(ChatMediaDrawerMapper::class) {
                val drawerCls = chatMediaDrawerClass.getAsClass() ?: return@useMapper
                val actionHandlerCls = actionHandlerClass.getAsClass() ?: return@useMapper
                val sendItemsName = sendItemsMethodName.getAsString() ?: "sendItems"
                drawerViewClass = drawerCls
                sendItemsListItemClassFallback = sendItemsListItemClass.getAsClass()

                val contextType = drawerCls.genericSuperclass?.getTypeArguments()?.getOrNull(1) ?: return@useMapper
                val handlerParamMethod = contextType.methods.firstOrNull { method ->
                    method.parameterTypes.size == 1 && (
                        method.parameterTypes[0].name.endsWith("ChatMediaDrawerActionHandler") ||
                            actionHandlerCls.isAssignableFrom(method.parameterTypes[0])
                    )
                } ?: return@useMapper
                val sendItems = handlerParamMethod.parameterTypes[0].methods.firstOrNull { it.name == sendItemsName } ?: return@useMapper
                sendItemsMethod = sendItems
                handlerParamMethod.hook(HookStage.AFTER) {
                    chatMediaDrawerActionHandler = it.arg(0)
                }
            }

            var requestCode: Int? = null
            var firstVideoId: Long? = null
            var mediaInputStream: InputStream? = null

            ContentResolver::class.java.apply {
                hook("query", HookStage.AFTER) { param ->
                    val uri = param.arg<Uri>(0)
                    if (!uri.toString().endsWith(firstVideoId.toString())) return@hook

                    param.setResult(object : CursorWrapper(param.getResult() as Cursor) {
                        override fun getLong(columnIndex: Int): Long {
                            if (getColumnName(columnIndex) == "duration") {
                                return lastMediaDuration ?: -1
                            }
                            return super.getLong(columnIndex)
                        }
                    })
                }
                hook("openInputStream", HookStage.BEFORE) { param ->
                    val uri = param.arg<Uri>(0)
                    if (uri.toString().endsWith(firstVideoId.toString())) {
                        param.setResult(mediaInputStream)
                        mediaInputStream = null
                    }
                }
            }

            context.event.subscribe(ActivityResultEvent::class) { event ->
                if (sendItemsMethod == null || event.requestCode != requestCode || event.resultCode != Activity.RESULT_OK) return@subscribe
                requestCode = null

                firstVideoId = context.androidContext.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_TAKEN} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getLongOrNull("_id")
                    } else {
                        null
                    }
                }

                if (firstVideoId == null) {
                    context.inAppOverlay.showStatusToast(
                        Icons.Default.Upload,
                        "Must have a video in gallery to upload."
                    )
                    return@subscribe
                }

                fun sendMedia() {
                    val method = sendItemsMethod ?: return
                    val itemClass = method.genericParameterTypes.getOrNull(1)?.getTypeArguments()?.firstOrNull()
                        ?: sendItemsListItemClassFallback
                    if (itemClass == null) {
                        context.log.warn("MediaFilePicker: sendItems second parameter type has no generic info (type erasure). genericParameterTypes[1]=${method.genericParameterTypes.getOrNull(1)}")
                        context.inAppOverlay.showStatusToast(Icons.Default.Error, "Failed to send media (incompatible version).")
                        return
                    }
                    val item = itemClass.dataBuilder {
                        from("_item") {
                            set("_cameraRollSource", "Snapchat")
                            set("_contentUri", "")
                            set("_durationMs", (lastMediaDuration ?: 0L).toDouble())
                            set("_disabled", false)
                            set("_imageRotation", 0.0)
                            set("_width", 1080.0)
                            set("_height", 1920.0)
                            set("_timestampMs", System.currentTimeMillis().toDouble())
                            from("_itemId") {
                                set("_itemId", firstVideoId.toString())
                                set("_type", "VIDEO")
                            }
                        }
                        set("_order", 0.0)
                    } ?: run {
                        context.inAppOverlay.showStatusToast(Icons.Default.Error, "Failed to build media item.")
                        return
                    }
                    method.invoke(chatMediaDrawerActionHandler, listOf<Any>(), listOf(item))
                }

                fun startConversion(audioOnly: Boolean) {
                    context.coroutineScope.launch {
                        val pickedUri = event.intent?.data ?: run {
                            context.inAppOverlay.showStatusToast(Icons.Default.Error, "No media was selected.")
                            return@launch
                        }
                        val mimeType = context.androidContext.contentResolver.getType(pickedUri)
                        val inputExtension = resolveInputExtension(pickedUri, mimeType)
                        val outputExtension = if (audioOnly || mimeType?.startsWith("audio/") == true) "m4a" else "mp4"

                        lastMediaDuration = extractMediaDuration(pickedUri)

                        context.inAppOverlay.showStatusToast(Icons.Default.Crop, "Converting media...", durationMs = 3000)
                        val pickedFileDescriptor = context.androidContext.contentResolver.openFileDescriptor(pickedUri, "r")
                        if (pickedFileDescriptor == null) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Error, "Failed to open selected media.")
                            return@launch
                        }

                        val pfd = context.bridgeClient.convertMedia(
                            pickedFileDescriptor,
                            inputExtension,
                            outputExtension,
                            "aac",
                            if (!audioOnly) "libx264" else null
                        )

                        if (pfd == null) {
                            context.inAppOverlay.showStatusToast(Icons.Default.Error, "Failed to convert media.")
                            return@launch
                        }

                        context.inAppOverlay.showStatusToast(Icons.Default.CheckCircleOutline, "Media converted successfully.")

                        runCatching {
                            mediaInputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                            sendMedia()
                        }.onFailure {
                            mediaInputStream = null
                            context.log.error(it)
                            context.inAppOverlay.showStatusToast(Icons.Default.Error, "Failed to send media.")
                        }
                    }
                }

                val pickedUri = event.intent?.data ?: return@subscribe
                val isAudio = context.androidContext.contentResolver.getType(pickedUri)?.startsWith("audio/") == true

                if (isAudio || context.config.messaging.galleryMediaSendOverride.mode.getNullable() == null) {
                    startConversion(isAudio)
                    return@subscribe
                }

                android.app.AlertDialog.Builder(context.mainActivity!!)
                    .setTitle("Convert video file")
                    .setItems(arrayOf("Send as video/audio", "Send as audio only")) { _, which ->
                        startConversion(which == 1)
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.show()
            }

            val buttonTag = Random.nextInt(0, 65535)

            context.event.subscribe(AddViewEvent::class) { event ->
                if (event.parent !is FrameLayout || drawerViewClass?.isInstance(event.view) != true) return@subscribe

                event.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        if (event.parent.findViewWithTag<View>(buttonTag)?.run {
                                visibility = View.VISIBLE
                                bringToFront()
                            } != null) return
                        event.parent.addView(
                            createComposeView(context.mainActivity!!) {
                                PurrfectOverlayTheme {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = 10.dp, top = 8.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        val shape = RoundedCornerShape(18.dp)
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .shadow(
                                                    elevation = 16.dp,
                                                    shape = shape,
                                                    spotColor = PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.28f),
                                                    ambientColor = PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.20f)
                                                )
                                                .clip(shape)
                                                .background(
                                                    brush = Brush.linearGradient(
                                                        listOf(
                                                            PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.35f),
                                                            PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.18f)
                                                        )
                                                    ),
                                                    shape = shape
                                                )
                                                .border(
                                                    1.dp,
                                                    Brush.linearGradient(
                                                        listOf(
                                                            PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.7f),
                                                            PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.55f)
                                                        )
                                                    ),
                                                    shape = shape
                                                )
                                                .clickable {
                                                    requestCode = Random.nextInt(0, 65535)
                                                    this@MediaFilePicker.context.mainActivity!!.startActivityForResult(
                                                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                                            addCategory(Intent.CATEGORY_OPENABLE)
                                                            type = "video/*"
                                                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
                                                        },
                                                        requestCode!!
                                                    )
                                                },
                                            contentAlignment = androidx.compose.ui.Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Upload,
                                                contentDescription = "Upload media",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }.apply {
                                tag = buttonTag
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            }
                        )
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        event.parent.findViewWithTag<View>(buttonTag)?.visibility = View.GONE
                    }
                })
            }
        }
    }
}
