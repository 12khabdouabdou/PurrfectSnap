package me.eternal.purrfectsnap.core.features.impl.downloader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.eternal.purrfectsnap.common.ui.createComposeAlertDialog
import me.eternal.purrfectsnap.core.ui.PurrfectGlassCard
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayPalette
import me.eternal.purrfectsnap.core.ui.PurrfectOverlayTheme
import kotlinx.coroutines.runBlocking
import me.eternal.purrfectsnap.bridge.DownloadCallback
import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.data.FileType
import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.common.data.download.*
import me.eternal.purrfectsnap.common.database.impl.ConversationMessage
import me.eternal.purrfectsnap.common.database.impl.FriendInfo
import me.eternal.purrfectsnap.common.util.ktx.copyToClipboard
import me.eternal.purrfectsnap.common.util.ktx.longHashCode
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.common.util.snap.BitmojiSelfie
import me.eternal.purrfectsnap.common.util.snap.MediaDownloaderHelper
import me.eternal.purrfectsnap.common.util.snap.RemoteMediaResolver
import me.eternal.purrfectsnap.core.DownloadManagerClient
import me.eternal.purrfectsnap.core.PurrfectSnap
import me.eternal.purrfectsnap.core.features.MessagingRuleFeature
import me.eternal.purrfectsnap.core.features.impl.downloader.decoder.DecodedAttachment
import me.eternal.purrfectsnap.core.features.impl.downloader.decoder.MessageDecoder
import me.eternal.purrfectsnap.core.features.impl.messaging.Messaging
import me.eternal.purrfectsnap.core.features.impl.spying.MessageLogger
import me.eternal.purrfectsnap.core.ui.ViewAppearanceHelper
import me.eternal.purrfectsnap.core.ui.debugEditText
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.util.SNAPCHAT_13_80_VERSION
import me.eternal.purrfectsnap.core.util.isSnapchatVersionAtLeast
import me.eternal.purrfectsnap.core.util.media.PreviewUtils
import me.eternal.purrfectsnap.core.wrapper.impl.SnapUUID
import me.eternal.purrfectsnap.core.wrapper.impl.media.MediaInfo
import me.eternal.purrfectsnap.core.wrapper.impl.media.dash.LongformVideoPlaylistItem
import me.eternal.purrfectsnap.core.wrapper.impl.media.dash.SnapPlaylistItem
import me.eternal.purrfectsnap.core.wrapper.impl.media.opera.Layer
import me.eternal.purrfectsnap.core.wrapper.impl.media.opera.ParamMap
import me.eternal.purrfectsnap.core.wrapper.impl.media.toKeyPair
import me.eternal.purrfectsnap.core.features.impl.ui.OperaStoryOverlay
import me.eternal.purrfectsnap.core.wrapper.impl.media.EncryptionWrapper
import me.eternal.purrfectsnap.mapper.impl.OperaPageViewControllerMapper
import me.eternal.purrfectsnap.core.wrapper.impl.media.SnapCipherMode
import me.eternal.purrfectsnap.core.wrapper.impl.media.toKeyPairUrlSafe
import me.eternal.purrfectsnap.core.wrapper.impl.media.HybridEncryptionResolver
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import java.nio.file.Paths
import java.util.UUID
import kotlin.coroutines.suspendCoroutine
import kotlin.math.absoluteValue
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SnapChapterInfo(
    val offset: Long,
    val duration: Long?
)

data class OperaViewerMessageContext(
    val conversationId: String,
    val clientMessageId: Long
)

class MediaDownloader : MessagingRuleFeature("MediaDownloader", MessagingRuleType.AUTO_DOWNLOAD) {
    private var lastSeenMediaInfoMap: MutableMap<SplitMediaAssetType, MediaInfo>? = null
    var lastSeenMapParams: ParamMap? = null
        private set
    @Volatile
    private var pendingBatchDownloadIndices: MutableList<Int>? = null
    @Volatile
    private var batchForceAllowDuplicate: Boolean = false
    private val translations by lazy {
        context.translation.getCategory("download_processor")
    }
    private val useModernOperaViewerContext by lazy {
        isSnapchatVersionAtLeast(
            context.mappings.getSnapchatPackageInfo()?.versionName,
            SNAPCHAT_13_80_VERSION
        )
    }

    fun provideDownloadManagerClient(
        mediaIdentifier: String,
        mediaAuthor: String,
        creationTimestamp: Long? = null,
        downloadSource: MediaDownloadSource,
        friendInfo: FriendInfo? = null,
        forceAllowDuplicate: Boolean = false
    ): DownloadManagerClient {
        val generatedHash = (
            if (!context.config.downloader.allowDuplicate.get() && !forceAllowDuplicate) mediaIdentifier
            else UUID.randomUUID().toString()
        ).longHashCode().absoluteValue.toString(16)

        val iconUrl = BitmojiSelfie.getBitmojiSelfie(friendInfo?.bitmojiSelfieId, friendInfo?.bitmojiAvatarId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D)

        val downloadLogging by context.config.downloader.logging

        val outputPath = createNewFilePath(
            context.config,
            generatedHash.substring(0, generatedHash.length.coerceAtMost(8)),
            downloadSource,
            mediaAuthor,
            creationTimestamp?.takeIf { it > 0L }
        )

        return DownloadManagerClient(
            context = context,
            metadata = DownloadMetadata(
                mediaIdentifier = generatedHash,
                mediaAuthor = mediaAuthor,
                downloadSource = downloadSource.translate(context.translation),
                iconUrl = iconUrl,
                outputPath = outputPath
            ),
            callback = object: DownloadCallback.Stub() {
                override fun onSuccess(outputFile: String) {
                    if (!downloadLogging.contains("success")) return
                    context.log.verbose("onSuccess: outputFile=$outputFile")
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Outlined.DownloadDone,
                        durationMs = 1300,
                        text = translations["content_saved_toast"].also {
                            if (context.isMainActivityPaused) {
                                context.shortToast(it)
                            }
                        },
                    )
                }

                override fun onProgress(message: String) {
                    if (!downloadLogging.contains("progress")) return
                    context.log.verbose("onProgress: message=$message")
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Outlined.Info,
                        durationMs = 1300,
                        text = message,
                    )
                    if (context.isMainActivityPaused) {
                        context.shortToast(message)
                    }
                }

                override fun onFailure(message: String, throwable: String?) {
                    if (!downloadLogging.contains("failure")) return
                    context.log.verbose("onFailure: message=$message, throwable=$throwable")
                    if (context.isMainActivityPaused) {
                        context.shortToast(message)
                    }
                    throwable?.let { t ->
                        context.inAppOverlay.showStatusToast(
                            icon = Icons.Outlined.Error,
                            text = message + t.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty(),
                        )
                        return
                    }

                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Outlined.Warning,
                        durationMs = 1300,
                        text = message,
                    )
                }
            }
        )
    }

    private fun ParamMap.getStorySnapIndex(): Int? =
        this["snap_index_in_story"]?.toString()?.toIntOrNull()
            ?: this["SNAP_POSITION_IN_STORY"]?.toString()?.toIntOrNull()

    private fun ParamMap.getStorySnapTotal(): Int? =
        this["snap_story_length"]?.toString()?.toIntOrNull()
            ?: this["NUM_SNAPS_IN_STORY"]?.toString()?.toIntOrNull()

    private fun isMultiSnapStory(paramMap: ParamMap): Boolean {
        if (paramMap.containsKey("MESSAGE_ID") || paramMap["SNAP_SOURCE"]?.toString() == "SINGLE_SNAP_STORY") return false
        if (paramMap.containsKey("LONGFORM_VIDEO_PLAYLIST_ITEM")) return false
        val total = paramMap.getStorySnapTotal() ?: return false
        return total > 1
    }

    /*
     * Download the last seen media
     */
    fun downloadLastOperaMediaAsync(allowDuplicate: Boolean) {
        if (lastSeenMapParams == null || lastSeenMediaInfoMap == null) return
        val paramMap = lastSeenMapParams!!
        val mediaInfoMap = lastSeenMediaInfoMap!!

        if (isMultiSnapStory(paramMap) && context.config.downloader.storySnapListDownload.get()) {
            context.runOnUiThread {
                showStorySnapSelectionDialog(paramMap, mediaInfoMap, allowDuplicate)
            }
            return
        }

        context.executeAsync {
            handleOperaMedia(paramMap, mediaInfoMap, true, allowDuplicate)
        }
    }

    private fun showStorySnapSelectionDialog(paramMap: ParamMap, mediaInfoMap: Map<SplitMediaAssetType, MediaInfo>, allowDuplicate: Boolean) {
        val totalCount = paramMap.getStorySnapTotal() ?: return
        val currentIndex = paramMap.getStorySnapIndex() ?: 0
        val tr = context.translation.getCategory("download_processor.story_snap_dialog")
        val cancelStr = context.translation["button.cancel"]
        val downloadStr = context.translation["button.download"]

        context.runOnUiThread {
            createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                PurrfectOverlayTheme {
                    val selected = remember { mutableStateListOf<Int>().apply { add(currentIndex) } }

                    LaunchedEffect(Unit) {
                        if (!selected.contains(currentIndex)) selected.add(currentIndex)
                    }

                    PurrfectGlassCard(
                        title = tr["title"],
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 320.dp)
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed((0 until totalCount).toList()) { index, _ ->
                                    val label = tr.format("snap_item", "index" to (index + 1).toString(), "total" to totalCount.toString())
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selected.contains(index),
                                            onCheckedChange = { checked ->
                                                if (checked) selected.add(index) else selected.remove(index)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = PurrfectOverlayPalette.glowPrimary)
                                        )
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = PurrfectOverlayPalette.textPrimary
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selected.size == totalCount,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            selected.clear()
                                            selected.addAll(0 until totalCount)
                                        } else {
                                            selected.clear()
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = PurrfectOverlayPalette.glowPrimary)
                                )
                                Text(
                                    tr["select_all"],
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PurrfectOverlayPalette.textPrimary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { alertDialog.dismiss() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PurrfectOverlayPalette.textPrimary)
                                ) {
                                    Text(cancelStr)
                                }
                                Button(
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            startBatchDownload(selected.sorted().toMutableList(), allowDuplicate)
                                            alertDialog.dismiss()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurrfectOverlayPalette.glowPrimary)
                                ) {
                                    Text(downloadStr)
                                }
                            }
                        }
                    }
                }
            }.apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
        }
    }

    private fun startBatchDownload(indices: MutableList<Int>, allowDuplicate: Boolean) {
        if (indices.isEmpty()) return
        val paramMap = lastSeenMapParams ?: return
        val mediaInfoMap = lastSeenMediaInfoMap ?: return

        pendingBatchDownloadIndices = indices
        batchForceAllowDuplicate = allowDuplicate

        val currentIndex = paramMap.getStorySnapIndex() ?: 0
        val targetIndex = indices.first()
        val totalCount = paramMap.getStorySnapTotal()

        if (currentIndex == targetIndex) {
            processNextBatchDownload(paramMap, mediaInfoMap)
        } else {
            val jumped = context.feature(OperaStoryOverlay::class).requestJumpToSnap(targetIndex, totalCount)
            if (!jumped) {
                pendingBatchDownloadIndices = null
                context.shortToast(translations["batch_download_jump_failed_toast"])
            }
        }
    }

    private fun downloadSingleSnap(paramMap: ParamMap, mediaInfoMap: Map<SplitMediaAssetType, MediaInfo>) {
        context.executeAsync {
            runCatching { handleOperaMedia(paramMap, mediaInfoMap, true, batchForceAllowDuplicate) }
                .onFailure {
                    context.log.error("Batch download failed", it)
                    context.shortToast(translations["failed_generic_toast"])
                }
        }
    }

    private fun processNextBatchDownload(paramMap: ParamMap, mediaInfoMap: Map<SplitMediaAssetType, MediaInfo>) {
        val queue = pendingBatchDownloadIndices ?: return
        if (queue.isEmpty()) {
            flushPendingMergeAndComplete()
            return
        }

        val currentIndex = paramMap.getStorySnapIndex() ?: -1
        if (currentIndex != queue.first()) return

        queue.removeAt(0)
        downloadSingleSnap(paramMap, mediaInfoMap)

        if (queue.isEmpty()) {
            flushPendingMergeAndComplete()
        } else {
            val totalCount = paramMap.getStorySnapTotal()
            context.runOnUiThread {
                fun tryJump(retryCount: Int = 0) {
                    val delayMs = if (retryCount == 0) 120L else 220L
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val jumped = runCatching {
                            context.feature(OperaStoryOverlay::class).requestJumpToSnap(queue.first(), totalCount)
                        }.getOrNull() == true
                        if (!jumped && retryCount < 1) {
                            tryJump(retryCount + 1)
                        } else if (!jumped) {
                            pendingBatchDownloadIndices = null
                            context.shortToast(translations["batch_download_jump_failed_toast"])
                        }
                    }, delayMs)
                }
                tryJump()
            }
        }
    }

    private fun flushPendingMergeAndComplete() {
        pendingBatchDownloadIndices = null
        context.shortToast(translations["batch_download_complete_toast"])
    }

    fun showLastOperaDebugMediaInfo() {
        if (lastSeenMapParams == null || lastSeenMediaInfoMap == null) return

        context.runOnUiThread {
            val mediaInfoText = lastSeenMapParams?.concurrentHashMap?.map { (key, value) ->
                val transformedValue = value.let {
                    if (it::class.java == PurrfectSnap.classCache.snapUUID) {
                        SnapUUID(it).toString()
                    }
                    it
                }
                "- $key: $transformedValue"
            }?.joinToString("\n") ?: "No media info found"

            ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity!!).apply {
                setTitle("Debug Media Info")
                setView(debugEditText(context, mediaInfoText))
                setNeutralButton("Copy") { _, _ ->
                    context.copyToClipboard(mediaInfoText)
                }
                setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            }.show()
        }
    }

    private fun isSnapContentType(contentTypeId: Int): Boolean {
        return when (ContentType.fromId(contentTypeId)) {
            ContentType.SNAP,
            ContentType.TINY_SNAP,
            ContentType.EXTERNAL_MEDIA -> true
            else -> false
        }
    }

    private fun validateViewerMessageContext(messageContext: OperaViewerMessageContext): OperaViewerMessageContext? {
        val message = context.database.getConversationMessageFromId(messageContext.clientMessageId) ?: return null
        if (message.clientConversationId != messageContext.conversationId) return null
        if (!isSnapContentType(message.contentType)) return null
        return messageContext
    }

    private fun resolveLegacyViewerMessageContext(paramMap: ParamMap? = lastSeenMapParams): OperaViewerMessageContext? {
        val parts = paramMap?.get("MESSAGE_ID")
            ?.toString()
            ?.split(':')
            ?.takeIf { it.size == 3 }
            ?: return null

        return OperaViewerMessageContext(
            conversationId = parts[0],
            clientMessageId = parts[2].toLongOrNull() ?: return null
        )
    }

    private fun parseViewerMessageContext(rawValue: String): OperaViewerMessageContext? {
        val parts = rawValue.split(':')
        if (parts.size < 3) return null

        val conversationId = parts.firstOrNull()?.takeIf {
            runCatching { UUID.fromString(it) }.isSuccess
        } ?: return null
        val clientMessageId = parts.lastOrNull()?.toLongOrNull() ?: return null

        return OperaViewerMessageContext(
            conversationId = conversationId,
            clientMessageId = clientMessageId
        )
    }

    fun resolveViewerMessageContextFromParamMap(paramMap: ParamMap? = lastSeenMapParams): OperaViewerMessageContext? {
        if (paramMap == null) return null
        if (!useModernOperaViewerContext) return resolveLegacyViewerMessageContext(paramMap)

        paramMap["MESSAGE_ID"]?.toString()
            ?.let(::parseViewerMessageContext)
            ?.let(::validateViewerMessageContext)
            ?.let { return it }

        return paramMap.concurrentHashMap.values
            .asSequence()
            .mapNotNull { value ->
                value?.toString()?.let(::parseViewerMessageContext)
            }
            .mapNotNull(::validateViewerMessageContext)
            .firstOrNull()
    }

    fun resolveCurrentSnapMessageContext(): OperaViewerMessageContext? {
        if (!useModernOperaViewerContext) return resolveLegacyViewerMessageContext()

        val messaging = context.feature(Messaging::class)
        val currentConversationId = messaging.openedConversationUUID?.toString()
        val currentMessageId = messaging.lastFocusedMessageId.takeIf { it > 0L }

        if (currentConversationId != null && currentMessageId != null) {
            validateViewerMessageContext(
                OperaViewerMessageContext(
                    conversationId = currentConversationId,
                    clientMessageId = currentMessageId
                )
            )?.let { return it }
        }

        return resolveViewerMessageContextFromParamMap()
    }

    private fun handleLocalReferences(path: String) = runBlocking {
        Uri.parse(path).let { uri ->
            if (uri.scheme == "file" || uri.scheme == null) {
                return@let suspendCoroutine<String> { continuation ->
                    context.httpServer.ensureServerStarted()?.let { server ->
                        val file = Paths.get(uri.path).toFile()
                        val url = server.putDownloadableContent(file.inputStream(), file.length())
                        continuation.resumeWith(Result.success(url))
                    } ?: run {
                        continuation.resumeWith(Result.failure(Exception("Failed to start http server")))
                    }
                }
            }
            path
        }
    }

    private fun downloadOperaMedia(
        downloadManagerClient: DownloadManagerClient,
        mediaInfoMap: Map<SplitMediaAssetType, MediaInfo>,
        paramMap: ParamMap
    ) {
        if (mediaInfoMap.isEmpty()) return

        // Story Snap Entry (images)
        paramMap["SNAP_ID"]?.toString()?.let { snapId ->
            context.database.getStorySnapEntry(snapId)?.let { storySnapEntry ->

                downloadManagerClient.downloadSingleMedia(
                    storySnapEntry.mediaUrl ?: throw Exception("Media URL not found"),
                    DownloadMediaType.fromUri(Uri.parse(storySnapEntry.mediaUrl)),
                    (storySnapEntry.mediaKey to storySnapEntry.mediaIv)
                        .takeIf { it.first != null && it.second != null }
                        ?.let { (key, iv) -> MediaEncryptionKeyPair(key!!, iv!!, urlSafe = false) }
                )
                return
            }
        }

        val originalMediaInfo = mediaInfoMap[SplitMediaAssetType.ORIGINAL]!!
        val originalMediaInfoReference = handleLocalReferences(originalMediaInfo.uri)

        // Overlay (if present)
        mediaInfoMap[SplitMediaAssetType.OVERLAY]?.let { overlay ->
            val overlayReference = handleLocalReferences(overlay.uri)

            downloadManagerClient.downloadMediaWithOverlay(
                original = InputMedia(
                    originalMediaInfoReference,
                    DownloadMediaType.fromUri(Uri.parse(originalMediaInfoReference)),
                    originalMediaInfo.encryption?.toKeyPair()
                ),
                overlay = InputMedia(
                    overlayReference,
                    DownloadMediaType.fromUri(Uri.parse(overlayReference)),
                    overlay.encryption?.toKeyPair(),
                    isOverlay = true
                )
            )
            return
        }

        // Single media (video/DASH)
        downloadManagerClient.downloadSingleMedia(
            originalMediaInfoReference,
            DownloadMediaType.fromUri(Uri.parse(originalMediaInfoReference)),
            originalMediaInfo.encryption?.toKeyPair()
        )
    }

    fun canAutoDownloadMessage(databaseMessage: ConversationMessage): Boolean {
        if (context.config.downloader.preventSelfAutoDownload.get() && databaseMessage.senderId == context.database.myUserId) return false
        return canUseRule(databaseMessage.clientConversationId!!)
    }

    /**
     * Handles the media from the opera viewer
     *
     * @param paramMap      the parameters from the opera viewer
     * @param mediaInfoMap  the media info map
     * @param forceDownload if the media should be downloaded
     */
    private fun handleOperaMedia(
        paramMap: ParamMap,
        mediaInfoMap: Map<SplitMediaAssetType, MediaInfo>,
        forceDownload: Boolean,
        forceAllowDuplicate: Boolean = false
    ) {
        
        // ─── Messages ─────────────────────────
        resolveViewerMessageContextFromParamMap(paramMap)?.takeIf {
            forceDownload || shouldAutoDownload("friend_snaps")
        }?.let { messageContext ->
            val conversationMessage = context.database.getConversationMessageFromId(messageContext.clientMessageId) ?: return@let
            val conversationId = conversationMessage.clientConversationId!!

            if (!forceDownload && !canUseRule(conversationId)) return@let

            val senderId = conversationMessage.senderId!!
            if (!forceDownload && context.config.downloader.preventSelfAutoDownload.get() &&
                senderId == context.database.myUserId
            ) return@let

            val author = context.database.getFriendInfo(senderId) ?: return@let
            val authorUsername = author.usernameForSorting!!
            val mediaId = paramMap["MEDIA_ID"]?.toString()?.substringAfter("-")?.substringBefore(".") ?: ""

            downloadOperaMedia(
                provideDownloadManagerClient(
                    mediaIdentifier = "$conversationId$senderId${conversationMessage.serverMessageId}$mediaId",
                    mediaAuthor = authorUsername,
                    creationTimestamp = conversationMessage.creationTimestamp,
                    downloadSource = MediaDownloadSource.CHAT_MEDIA,
                    friendInfo = author,
                    forceAllowDuplicate = forceAllowDuplicate
                ),
                mediaInfoMap,
                paramMap
            )
            return
        }

        // ─── Private Friend Story ─────────────────────────
        paramMap["PLAYLIST_V2_GROUP"]?.takeIf {
            forceDownload || shouldAutoDownload("friend_stories")
        }?.let { playlistGroup ->
            val playlistGroupString = playlistGroup.toString()

            val storyUserId = paramMap["TOPIC_SNAP_CREATOR_USER_ID"]?.toString() ?: paramMap["PLAYABLE_STORY_SNAP_RECORD"]?.toString()?.let {
                if (it.contains("userId=")) it.substringAfter("userId=").substringBefore(",") else null
            } ?: if (playlistGroupString.contains("storyUserId=")) {
                playlistGroupString.substringAfter("storyUserId=").substringBefore(",")
            } else {
                //story replies
                val arroyoMessageId = playlistGroup::class.java.methods.firstOrNull { it.name == "getId" }
                    ?.invoke(playlistGroup)?.toString()
                    ?.split(":")?.getOrNull(2) ?: return@let

                val conversationMessage = context.database.getConversationMessageFromId(arroyoMessageId.toLong()) ?: return@let
                val conversationParticipants = context.database.getConversationParticipants(conversationMessage.clientConversationId.toString()) ?: return@let
                conversationParticipants.firstOrNull { it != conversationMessage.senderId }
            }

            val author = context.database.getFriendInfo(
                if (storyUserId == null || storyUserId == "null")
                    context.database.myUserId
                else storyUserId
            ) ?: throw Exception("Friend not found in database")
            val authorName = author.usernameForSorting!!

            if (!forceDownload) {
                if (context.config.downloader.preventSelfAutoDownload.get() && author.userId == context.database.myUserId) return
                if (!canUseRule(author.userId!!)) return
            }

            downloadOperaMedia(
                provideDownloadManagerClient(
                    mediaIdentifier = paramMap["MEDIA_ID"].toString(),
                    mediaAuthor = authorName,
                    creationTimestamp = paramMap["PLAYABLE_STORY_SNAP_RECORD"]?.toString()?.substringAfter("timestamp=")
                        ?.substringBefore(",")?.toLongOrNull(),
                    downloadSource = MediaDownloadSource.STORY,
                    friendInfo = author,
                    forceAllowDuplicate = forceAllowDuplicate
                ),
                mediaInfoMap,
                paramMap
            )
            return
        }

        // ─── Public Stories / Spotlight ───────────────────
        val snapSource = paramMap["SNAP_SOURCE"].toString()

        //spotlight
        if (snapSource == "SINGLE_SNAP_STORY" && (forceDownload || shouldAutoDownload("spotlight"))) {
            downloadOperaMedia(provideDownloadManagerClient(
                mediaIdentifier = paramMap["SNAP_ID"].toString(),
                downloadSource = MediaDownloadSource.SPOTLIGHT,
                mediaAuthor = paramMap["CREATOR_DISPLAY_NAME"].toString(),
                creationTimestamp = paramMap["SNAP_TIMESTAMP"]?.toString()?.toLongOrNull(),
                forceAllowDuplicate = forceAllowDuplicate,
            ), mediaInfoMap, paramMap)
            return
        }

        //stories with mpeg dash media
        if (paramMap.containsKey("LONGFORM_VIDEO_PLAYLIST_ITEM") && forceDownload) {
            val storyName = paramMap["STORY_NAME"].toString().sanitizeForPath()
            //get the position of the media in the playlist and the duration
            val snapItem = SnapPlaylistItem(paramMap["SNAP_PLAYLIST_ITEM"]!!)
            val snapChapterList = LongformVideoPlaylistItem(paramMap["LONGFORM_VIDEO_PLAYLIST_ITEM"]!!).chapters
            val currentChapterIndex = snapChapterList.indexOfFirst { it.snapId == snapItem.snapId }

            if (snapChapterList.isEmpty()) {
                context.shortToast(translations["dash_no_chapter"])
                return
            }

            fun prettyPrintTime(time: Long): String {
                val seconds = time / 1000
                val minutes = seconds / 60
                val hours = minutes / 60
                return "${(hours % 24).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
            }

            val playlistUrl = paramMap["MEDIA_ID"].toString().let {
                val urlIndexes = arrayOf(it.indexOf("https://cf-st.sc-cdn.net"), it.indexOf("https://bolt-gcdn.sc-cdn.net"))

                urlIndexes.firstOrNull { index -> index != -1 }?.let { validIndex ->
                    it.substring(validIndex)
                } ?: "${RemoteMediaResolver.CF_ST_CDN_D}$it"
            }

            context.runOnUiThread {
                val selectedChapters = mutableListOf<Int>()
                val dialogTranslation = translations.getCategory("dash_dialog")
                val chapters = snapChapterList.mapIndexed { index, snapChapter ->
                    val nextChapter = snapChapterList.getOrNull(index + 1)
                    val duration = nextChapter?.startTimeMs?.minus(snapChapter.startTimeMs)
                    SnapChapterInfo(snapChapter.startTimeMs, duration)
                }
                ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity!!).apply {
                    setTitle(dialogTranslation["title"])
                    setMultiChoiceItems(
                        chapters.map { dialogTranslation.format("segment_text", "from" to prettyPrintTime(it.offset), "to" to prettyPrintTime(it.offset + (it.duration ?: 0))) }.toTypedArray(),
                        List(chapters.size) { index ->
                            if (currentChapterIndex == index) {
                                selectedChapters.add(index)
                                true
                            } else false
                        }.toBooleanArray()
                    ) { _, which, isChecked ->
                        if (isChecked) {
                            selectedChapters.add(which)
                        } else if (selectedChapters.contains(which)) {
                            selectedChapters.remove(which)
                        }
                    }
                    setNegativeButton(this@MediaDownloader.context.translation["button.cancel"]) { dialog, _ -> dialog.dismiss() }
                    setNeutralButton(dialogTranslation["download_all"]) { _, _ ->
                        provideDownloadManagerClient(
                            mediaIdentifier = paramMap["STORY_ID"].toString(),
                            downloadSource = MediaDownloadSource.PUBLIC_STORY,
                            mediaAuthor = storyName
                        ).downloadDashMedia(playlistUrl, 0, null)
                    }
                    setPositiveButton(this@MediaDownloader.context.translation["button.download"]) { _, _ ->
                        val groups = mutableListOf<MutableList<SnapChapterInfo>>()

                        var lastChapterIndex = -1
                        // group consecutive chapters
                        chapters.forEachIndexed { index, snapChapter ->
                            lastChapterIndex = if (selectedChapters.contains(index)) {
                                if (lastChapterIndex == -1) {
                                    groups.add(mutableListOf())
                                }
                                groups.last().add(snapChapter)
                                index
                            } else {
                                -1
                            }
                        }

                        groups.forEach { group ->
                            val firstChapter = group.first()
                            val lastChapter = group.last()
                            val duration = if (firstChapter == lastChapter) {
                                firstChapter.duration
                            } else {
                                lastChapter.duration?.let { lastChapter.offset - firstChapter.offset + it }
                            }

                            provideDownloadManagerClient(
                                mediaIdentifier = "${paramMap["STORY_ID"]}-${firstChapter.offset}-${lastChapter.offset}",
                                downloadSource = MediaDownloadSource.PUBLIC_STORY,
                                mediaAuthor = storyName,
                                forceAllowDuplicate = forceAllowDuplicate,
                            ).downloadDashMedia(
                                playlistUrl,
                                firstChapter.offset.plus(100),
                                duration
                            )
                        }
                    }
                }.show()
            }
        }

        if (!forceDownload && !shouldAutoDownload("public_stories")) return

        //public stories
        val author = (
                paramMap["USER_ID"]?.let { context.database.getFriendInfo(it.toString())?.mutableUsername } // only for following users
                    ?: paramMap["USERNAME"]?.toString()?.takeIf {
                        it.contains("value=")
                    }?.substringAfter("value=")?.substringBefore(")")?.substringBefore(",")
                    ?: paramMap["CONTEXT_USER_IDENTITY"]?.toString()?.takeIf {
                        it.contains("username=")
                    }?.substringAfter("username=")?.substringBefore(",")
                    // fallback display name
                    ?: paramMap["USER_DISPLAY_NAME"]?.toString()?.takeIf { it.isNotEmpty() }
                    ?: paramMap["TIME_STAMP"]?.toString()
                    ?: "unknown"
                ).sanitizeForPath()

        downloadOperaMedia(provideDownloadManagerClient(
            mediaIdentifier = paramMap["SNAP_ID"].toString(),
            mediaAuthor = author,
            downloadSource = MediaDownloadSource.PUBLIC_STORY,
            creationTimestamp = paramMap["SNAP_TIMESTAMP"]?.toString()?.toLongOrNull(),
            forceAllowDuplicate = forceAllowDuplicate,
        ), mediaInfoMap, paramMap)
    }

    private fun shouldAutoDownload(keyFilter: String? = null): Boolean {
        val options by context.config.downloader.autoDownloadSources
        return options.any { keyFilter == null || it.contains(keyFilter, true) }
    }

    override fun init() {
        if (getRuleState() == null) return
        onNextActivityCreate {
            context.mappings.useMapper(OperaPageViewControllerMapper::class) {
                arrayOf(onDisplayStateChange, onDisplayStateChangeGesture).forEach { methodName ->
                    classReference.get()?.hook(
                        methodName.get() ?: return@forEach,
                        HookStage.AFTER
                    ) onOperaViewStateCallback@{ param ->
                        val viewState = (param.thisObject() as Any).getObjectField(viewStateField.get()!!).toString()

                        if (viewState != "FULLY_DISPLAYED") {
                            return@onOperaViewStateCallback
                        }

                        val operaLayerList = (param.thisObject() as Any).getObjectField(layerListField.get()!!) as ArrayList<*>
                        val layerParamMaps = operaLayerList
                            .asSequence()
                            .mapNotNull { layerObj ->
                                layerObj?.let { runCatching { Layer(it).paramMap }.getOrNull() }
                            }
                            .toList()
                        val firstLayerParamMap = layerParamMaps.firstOrNull()
                        val mediaParamMap: ParamMap = if (useModernOperaViewerContext) {
                            (
                                // Chat snaps need the primary MESSAGE_ID-bearing param map for mark-as-seen to work.
                                layerParamMaps.firstOrNull {
                                    it.containsKey("MESSAGE_ID") &&
                                        (it.containsKey("image_media_info") || it.containsKey("video_media_info_list"))
                                }
                                    ?: firstLayerParamMap?.takeIf {
                                        it.containsKey("image_media_info") || it.containsKey("video_media_info_list")
                                    }
                                    ?: layerParamMaps.firstOrNull {
                                        it.containsKey("image_media_info") || it.containsKey("video_media_info_list")
                                    }
                                )
                        } else {
                            layerParamMaps.firstOrNull {
                                it.containsKey("image_media_info") || it.containsKey("video_media_info_list")
                            }
                        } ?: return@onOperaViewStateCallback

                        val mediaInfoMap = mutableMapOf<SplitMediaAssetType, MediaInfo>()
                        val isVideo = mediaParamMap.containsKey("video_media_info_list")

                        mediaInfoMap[SplitMediaAssetType.ORIGINAL] = MediaInfo(
                            (if (isVideo) mediaParamMap["video_media_info_list"] else mediaParamMap["image_media_info"])!!
                        )

                        if (context.config.downloader.mergeOverlays.get() && mediaParamMap.containsKey("overlay_image_media_info")) {
                            mediaInfoMap[SplitMediaAssetType.OVERLAY] =
                                MediaInfo(mediaParamMap["overlay_image_media_info"]!!)
                        }

                        val shouldAutoDownload = shouldAutoDownload()

                        if (shouldAutoDownload && lastSeenMediaInfoMap?.get(SplitMediaAssetType.ORIGINAL)?.uri == mediaInfoMap[SplitMediaAssetType.ORIGINAL]?.uri) return@onOperaViewStateCallback

                        lastSeenMapParams = mediaParamMap
                        lastSeenMediaInfoMap = mediaInfoMap

                        if (pendingBatchDownloadIndices != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (pendingBatchDownloadIndices != null) {
                                    processNextBatchDownload(mediaParamMap, mediaInfoMap)
                                }
                            }, 80L)
                            return@onOperaViewStateCallback
                        }

                        if (!shouldAutoDownload) {
                            return@onOperaViewStateCallback
                        }

                        context.executeAsync {
                            runCatching {
                                handleOperaMedia(mediaParamMap, mediaInfoMap, false)
                            }.onFailure {
                                context.log.error("Failed to handle opera media", it)
                                context.longToast(it.message)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun downloadMessageAttachments(
        friendInfo: FriendInfo,
        message: ConversationMessage,
        authorName: String,
        attachments: List<DecodedAttachment>,
        forceAllowDuplicate: Boolean = false
    ) {
        attachments.forEach { attachment ->
            runCatching {
                provideDownloadManagerClient(
                    mediaIdentifier = "${message.clientConversationId}${message.senderId}${message.serverMessageId}${attachment.mediaUniqueId}",
                    downloadSource = MediaDownloadSource.CHAT_MEDIA,
                    mediaAuthor = authorName,
                    friendInfo = friendInfo,
                    forceAllowDuplicate = forceAllowDuplicate,
                    creationTimestamp = message.creationTimestamp,
                ).apply {
                    downloadInputMedias(
                        arrayOf(attachment.createInputMedia()!!)
                    )
                }
            }.onFailure {
                context.longToast(translations["failed_generic_toast"])
                context.log.error("Failed to download", it)
            }
        }
    }

    private fun DecodedAttachment.getInfo(): String {
        return "${translations["attachment_type.${type.key}"]} ${attachmentInfo?.resolution?.let { "(${it.first}x${it.second})" } ?: ""}"
    }

    @SuppressLint("SetTextI18n")
    private fun previewAttachment(
        attachment: DecodedAttachment
    ) {
        var previewBitmap: Bitmap? = null
        val previewCoroutine = context.coroutineScope.launch {
            runCatching {
                attachment.openStream { attachmentStream, _ ->
                    val downloadedMediaList = mutableMapOf<SplitMediaAssetType, ByteArray>()

                    MediaDownloaderHelper.getSplitElements(attachmentStream!!) {
                            type, inputStream ->
                        downloadedMediaList[type] = inputStream.readBytes()
                    }

                    val originalMedia = downloadedMediaList[SplitMediaAssetType.ORIGINAL] ?: return@openStream
                    val overlay = downloadedMediaList[SplitMediaAssetType.OVERLAY]

                    var bitmap = PreviewUtils.createPreview(originalMedia, isVideo = FileType.fromByteArray(originalMedia).isVideo)
                        ?: throw Exception("preview is null")

                    overlay?.also {
                        bitmap = PreviewUtils.mergeBitmapOverlay(bitmap, BitmapFactory.decodeByteArray(it, 0, it.size))
                    }

                    previewBitmap = bitmap
                }
            }.onFailure {
                context.shortToast(translations["failed_to_create_preview_toast"])
                context.log.error("Failed to create preview", it)
            }
        }

        with(ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)) {
            val viewGroup = LinearLayout(context).apply {
                layoutParams = MarginLayoutParams(
                    MarginLayoutParams.MATCH_PARENT,
                    MarginLayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                addView(ProgressBar(context).apply {
                    isIndeterminate = true
                })
            }

            setOnDismissListener {
                previewCoroutine.cancel()
            }

            previewCoroutine.invokeOnCompletion { cause ->
                if (previewCoroutine.isCancelled) return@invokeOnCompletion
                runOnUiThread {
                    viewGroup.removeAllViews()
                    if (cause != null) {
                        viewGroup.addView(TextView(context).apply {
                            text =
                                translations["failed_to_create_preview_toast"] + "\n" + cause.message
                            setPadding(30, 30, 30, 30)
                        })
                        return@runOnUiThread
                    }

                    viewGroup.addView(ImageView(context).apply {
                        setImageBitmap(previewBitmap)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        adjustViewBounds = true
                    })
                }
            }

            runOnUiThread {
                show().apply {
                    setContentView(viewGroup)
                    window?.setLayout(
                        context.resources.displayMetrics.widthPixels,
                        context.resources.displayMetrics.heightPixels
                    )
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun downloadMessageId(messageId: Long, forceAllowDuplicate: Boolean = false, isPreview: Boolean = false, forceDownloadFirst: Boolean = false) {
        val messageLogger = context.feature(MessageLogger::class)
        val message = context.database.getConversationMessageFromId(messageId) ?: throw Exception("Message not found in database")

        val friendInfo = context.database.getFriendInfo(message.senderId!!) ?: throw Exception("Friend not found in database")
        val authorName = friendInfo.usernameForSorting!!

        val decodedAttachments = (
            messageLogger.takeIf { it.isEnabled }?.getMessageObject(message.clientConversationId!!, message.clientMessageId.toLong())?.let {
                MessageDecoder.decode(it.getAsJsonObject("mMessageContent"))
            } ?: MessageDecoder.decode(
                protoReader = ProtoReader(message.messageContent!!)
            ).toMutableList().apply {
                val quotedMessage = message.quotedServerMessageId?.takeIf { it > 0 }?.let { quotedMessageId ->
                    context.database.getConversationServerMessage(message.clientConversationId!!, quotedMessageId)
                } ?: return@apply
                addAll(0, MessageDecoder.decode(
                    protoReader = ProtoReader(quotedMessage.messageContent ?: return@apply)
                ))
            }
        ).toMutableList()

        context.feature(Messaging::class).conversationManager?.takeIf {
            decodedAttachments.isEmpty()
        }?.also { conversationManager ->
            runBlocking {
                suspendCoroutine { continuation ->
                    conversationManager.fetchMessage(message.clientConversationId!!, message.clientMessageId.toLong(), onSuccess = { message ->
                        decodedAttachments.addAll(MessageDecoder.decode(message.messageContent!!))
                        continuation.resumeWith(Result.success(Unit))
                    }, onError = {
                        continuation.resumeWith(Result.success(Unit))
                    })
                }
            }
        }

        if (decodedAttachments.isEmpty()) {
            context.shortToast(translations["no_attachments_toast"])
            return
        }

        if (!isPreview) {
            if (forceDownloadFirst ||
                decodedAttachments.size == 1 ||
                context.isMainActivityPaused
            ) {
                downloadMessageAttachments(friendInfo, message, authorName,
                    listOf(decodedAttachments.first()),
                    forceAllowDuplicate = forceAllowDuplicate
                )
                return
            }

            runOnUiThread {
                ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity).apply {
                    val selectedAttachments = mutableListOf<Int>().apply {
                        addAll(decodedAttachments.indices)
                    }
                    setMultiChoiceItems(
                        decodedAttachments.mapIndexed { index, decodedAttachment ->
                            "${index + 1}: ${decodedAttachment.getInfo()}"
                        }.toTypedArray(),
                        decodedAttachments.map { true }.toBooleanArray()
                    ) { _, which, isChecked ->
                        if (isChecked) {
                            selectedAttachments.add(which)
                        } else if (selectedAttachments.contains(which)) {
                            selectedAttachments.remove(which)
                        }
                    }
                    setTitle(translations["select_attachments_title"])
                    setNegativeButton(this@MediaDownloader.context.translation["button.cancel"]) { dialog, _ -> dialog.dismiss() }
                    setPositiveButton(this@MediaDownloader.context.translation["button.download"]) { _, _ ->
                        downloadMessageAttachments(friendInfo, message, authorName, selectedAttachments.map { decodedAttachments[it] },
                            forceAllowDuplicate = forceAllowDuplicate
                        )
                    }
                }.show()
            }
            return
        }

        if (decodedAttachments.size == 1) {
            previewAttachment(decodedAttachments.first())
            return
        }

        runOnUiThread {
            ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity).apply {
                var selectedAttachment = 0
                setSingleChoiceItems(
                    decodedAttachments.mapIndexed { index, decodedAttachment -> "${index + 1}: ${decodedAttachment.getInfo()}" }.toTypedArray(),
                    0
                ) { _, which ->
                    selectedAttachment = which
                }
                setTitle(translations["select_attachments_title"])
                setNegativeButton(this@MediaDownloader.context.translation["button.cancel"]) { dialog, _ -> dialog.dismiss() }
                setPositiveButton(this@MediaDownloader.context.translation["chat_action_menu.preview_button"]) { _, _ ->
                    previewAttachment(decodedAttachments[selectedAttachment])
                }
            }.show()
        }
    }

    fun downloadProfilePicture(url: String, author: String) {
        provideDownloadManagerClient(
            mediaIdentifier = url.hashCode().toString(16).replaceFirst("-", ""),
            mediaAuthor = author,
            downloadSource = MediaDownloadSource.PROFILE_PICTURE
        ).downloadSingleMedia(
            url,
            DownloadMediaType.REMOTE_MEDIA
        )
    }

    /**
     * Called when a message is focused in chat
     */
    fun onMessageActionMenu(isPreviewMode: Boolean, forceAllowDuplicate: Boolean = false) {
        val messaging = context.feature(Messaging::class)
        if (messaging.openedConversationUUID == null) return

        context.executeAsync {
            downloadMessageId(messaging.lastFocusedMessageId, forceAllowDuplicate, isPreviewMode)
        }
    }
}
