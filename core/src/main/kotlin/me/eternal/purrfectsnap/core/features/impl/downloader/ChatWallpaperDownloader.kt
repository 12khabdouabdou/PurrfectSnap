package me.eternal.purrfectsnap.core.features.impl.downloader

import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.absoluteValue
import me.eternal.purrfectsnap.common.data.download.DownloadMediaType
import me.eternal.purrfectsnap.common.data.download.InputMedia
import me.eternal.purrfectsnap.common.data.download.MediaDownloadSource
import me.eternal.purrfectsnap.common.data.download.toKeyPair
import me.eternal.purrfectsnap.common.ui.createComposeView
import me.eternal.purrfectsnap.core.event.events.impl.AddViewEvent
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.ui.getValdiContext
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayPalette
import me.eternal.purrfectsnap.core.ui.triggerCloseTouchEvent
import me.eternal.purrfectsnap.core.util.EvictingMap
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hookConstructor
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.util.ktx.getObjectFieldOrNull
import me.eternal.purrfectsnap.core.wrapper.impl.SnapUUID

class ChatWallpaperDownloader : Feature("Chat Wallpaper Downloader") {
    private class ChatWallpaper(
        val contentObject: ByteArray,
        val key: ByteArray?,
        val iv: ByteArray?,
    )

    @OptIn(ExperimentalEncodingApi::class)
    override fun init() {
        if (!context.config.downloader.chatWallpaperDownloader.get()) return

        val chatWallpapers = EvictingMap<String, ChatWallpaper>(50)

        context.classCache.conversation.hookConstructor(HookStage.AFTER) { param ->
            val instance = param.thisObject<Any>()
            val conversationId = SnapUUID(instance.getObjectField("mConversationId")).toString()
            val chatWallpaper = instance.getObjectFieldOrNull("mChatWallpaper") ?: return@hookConstructor
            val mediaEncryptionInfo = chatWallpaper.getObjectFieldOrNull("mEncryptionInfo")

            chatWallpapers[conversationId] = ChatWallpaper(
                contentObject = chatWallpaper.getObjectFieldOrNull("mContentObject") as? ByteArray
                    ?: return@hookConstructor,
                key = mediaEncryptionInfo?.getObjectFieldOrNull("mKey") as? ByteArray,
                iv = mediaEncryptionInfo?.getObjectFieldOrNull("mIv") as? ByteArray,
            )
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (!event.viewClassName.endsWith("ChatWallpaperSectionComponent")) return@subscribe

            event.view.post {
                val valdiContext = event.view.getValdiContext() ?: return@post
                val conversationId = valdiContext.viewModel
                    ?.getObjectFieldOrNull("_conversationId")
                    ?.toString()
                    ?: return@post
                val chatWallpaper = chatWallpapers[conversationId] ?: return@post

                event.parent.addView(createComposeView(event.parent.context) {
                    val label = context.translation["chat_wallpaper_downloader.download_button"]
                    val stroke = Brush.linearGradient(
                        listOf(
                            PurrfectOverlayPalette.glowPrimary.copy(alpha = 0.75f),
                            PurrfectOverlayPalette.glowSecondary.copy(alpha = 0.6f)
                        )
                    )
                    val background = Brush.linearGradient(
                        listOf(
                            Color(0xFF2A2452),
                            Color(0xFF1B163A)
                        )
                    )
                    val shape = RoundedCornerShape(20.dp)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = shape,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, stroke),
                        shadowElevation = 6.dp,
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .background(background, shape)
                                .clickable {
                                    val friendInfo = runCatching {
                                        context.database.getDMOtherParticipant(conversationId)?.let {
                                            context.database.getFriendInfo(it)
                                        } ?: context.database.getFriendInfo(context.database.myUserId)
                                    }.getOrNull()

                                    context.feature(MediaDownloader::class).provideDownloadManagerClient(
                                        mediaIdentifier = chatWallpaper.contentObject.contentHashCode().absoluteValue.toString(16),
                                        mediaAuthor = friendInfo?.mutableUsername ?: "unknown",
                                        downloadSource = MediaDownloadSource.CHAT_WALLPAPER,
                                        friendInfo = friendInfo,
                                    ).downloadInputMedias(
                                        arrayOf(
                                            InputMedia(
                                                content = Base64.UrlSafe.encode(chatWallpaper.contentObject),
                                                encryption = chatWallpaper.key?.let { key ->
                                                    chatWallpaper.iv?.let { iv ->
                                                        (key to iv).toKeyPair()
                                                    }
                                                },
                                                type = DownloadMediaType.PROTO_MEDIA
                                            )
                                        )
                                    )

                                    event.view.triggerCloseTouchEvent()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DownloadForOffline,
                                contentDescription = label,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                text = label,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
            }
        }
    }
}
