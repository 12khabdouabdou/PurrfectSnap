package me.eternal.purrfectsnap.core.features.impl.downloader

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
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
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        onClick = {
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
                    ) {
                        Text(context.translation["chat_wallpaper_downloader.download_button"])
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
