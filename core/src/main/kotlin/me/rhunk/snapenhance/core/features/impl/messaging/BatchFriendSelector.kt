package me.rhunk.snapenhance.core.features.impl.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BatchFriendSelector : Feature("Batch Friend Selector") {

    companion object {
        private const val MAX_BATCH_SESSIONS = 10
    }

    private val batchSessions = ConcurrentHashMap<String, BatchSession>()
    private val sessionMutex = Mutex()

    private lateinit var notificationManager: BatchNotificationManager
    private lateinit var mediaHandler: SnapMediaHandler

    data class BatchSession(
        val id: String = UUID.randomUUID().toString(),
        val friendIds: List<String>,
        val batches: List<FriendBatch>,
        val snapMedia: SnapMediaHandler.SnapMediaData? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class FriendBatch(
        val id: String = UUID.randomUUID().toString(),
        val friendIds: List<String>,
        val status: BatchStatus = BatchStatus.PENDING,
        val sentAt: Long? = null,
        val error: String? = null
    )

    enum class BatchStatus { PENDING, SENDING, SENT, FAILED, CANCELLED }

    override fun init() {
        val config = context.config.messaging.batchFriendSelector

        if (!config.enabled.value) return

        notificationManager = BatchNotificationManager(context.androidContext)
        mediaHandler = SnapMediaHandler(context)

        context.log.info(
            "BatchFriendSelector initialized with batch size: ${config.batchSize.value}"
        )

        hookFriendSelection()

        context.coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(300_000L) // 5 minutes
                cleanupOldSessions()
            }
        }
    }

    private fun hookFriendSelection() {
        try {
            context.androidContext.classLoader.loadClass("com.snapchat.client.messaging.SendToViewModel")
                ?.hook("validateFriendSelection", HookStage.BEFORE) { param ->
                    val selectedFriends = param.arg<List<Any>>(0)
                    val batchSize =
                        context.config.messaging.batchFriendSelector.batchSize.value

                    if (selectedFriends.size > batchSize) {
                        context.log.info(
                            "Friend selection exceeds limit (${selectedFriends.size}), launching batch manager"
                        )
                        param.setResult(null)

                        val friendIds = selectedFriends.mapNotNull { extractFriendId(it) }
                        context.coroutineScope.launch(Dispatchers.Main) {
                            launchBatchManager(friendIds)
                        }
                    }
                }

            context.androidContext.classLoader.loadClass("com.snapchat.client.messaging.SendToFragment")
                ?.hook("onSendButtonClicked", HookStage.BEFORE) { param ->
                    val selectedCount = getSelectedFriendsCount(param.thisObject())
                    val batchSize =
                        context.config.messaging.batchFriendSelector.batchSize.value

                    if (selectedCount > batchSize) {
                        context.log.info("Intercepting send with $selectedCount friends")
                        param.setResult(null)
                        val friendIds = extractSelectedFriendIds(param.thisObject())
                        context.coroutineScope.launch(Dispatchers.Main) {
                            launchBatchManager(friendIds)
                        }
                    }
                }
        } catch (e: Exception) {
            context.log.error("Error setting up friend selection hooks", e)
        }
    }

    private fun extractFriendId(friendObject: Any): String? = try {
        friendObject.javaClass.methods.firstOrNull {
            it.name == "getUserId" || it.name == "getId" || it.name == "getFriendUserId"
        }?.invoke(friendObject)?.toString()
    } catch (e: Exception) {
        context.log.warn("Failed to extract friend ID", e)
        null
    }

    private fun getSelectedFriendsCount(fragmentObject: Any): Int = try {
        fragmentObject.javaClass.methods.firstOrNull {
            it.name == "getSelectedFriendsCount" || it.name == "getSelectedCount"
        }?.invoke(fragmentObject) as? Int ?: 0
    } catch (_: Exception) {
        0
    }

    private fun extractSelectedFriendIds(fragmentObject: Any): List<String> = try {
        val selectedFriends = fragmentObject.javaClass.methods.firstOrNull {
            it.name == "getSelectedFriends" || it.name == "getSelected"
        }?.invoke(fragmentObject) as? List<*>

        selectedFriends?.mapNotNull { extractFriendId(it!!) } ?: emptyList()
    } catch (e: Exception) {
        context.log.error("Failed to extract selected friend IDs", e)
        emptyList()
    }

    private suspend fun launchBatchManager(friendIds: List<String>) {
        sessionMutex.withLock {
            val capturedMedia = mediaHandler.getCapturedMedia() ?: run {
                context.log.error("No media captured for batch send")
                return
            }

            val batchSize = context.config.messaging.batchFriendSelector.batchSize.value
            val batches = createBatches(friendIds, batchSize)

            val session = BatchSession(
                friendIds = friendIds,
                batches = batches,
                snapMedia = capturedMedia
            )

            batchSessions[session.id] = session

            if (context.config.messaging.batchFriendSelector.enableNotifications.value) {
                notificationManager.showSessionCreated(
                    session.id,
                    batches.size,
                    friendIds.size
                )
            }

            if (batchSessions.size > MAX_BATCH_SESSIONS) {
                val oldest = batchSessions.minByOrNull { it.value.createdAt }?.key
                oldest?.let { batchSessions.remove(it) }
            }

            context.log.info(
                "Created batch session ${session.id} with ${batches.size} batches, media type: ${capturedMedia.mediaType}"
            )

            showBatchManagerUI(session.id)
        }
    }

    private fun createBatches(friendIds: List<String>, batchSize: Int): List<FriendBatch> =
        friendIds.chunked(batchSize).map { FriendBatch(friendIds = it) }

    private fun showBatchManagerUI(sessionId: String) {
        try {
            val activity = context.androidContext as? android.app.Activity ?: return
            activity.runOnUiThread {
                val intent = android.content.Intent(
                    context.androidContext,
                    Class.forName("me.rhunk.snapenhance.ui.manager.pages.social.BatchManagerActivity")
                )
                intent.putExtra("SESSION_ID", sessionId)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.androidContext.startActivity(intent)
            }
        } catch (e: Exception) {
            context.log.error("Failed to launch batch manager UI", e)
        }
    }

    private fun sendSnapToBatch(
        conversationIds: List<SnapUUID>,
        sessionId: String,
        batchId: String,
        batchIndex: Int,
        totalBatches: Int
    ) {
        context.coroutineScope.launch(Dispatchers.IO) {
            try {
                val session = batchSessions[sessionId]
                val mediaData = session?.snapMedia ?: throw Exception("No media data found")

                val result = mediaHandler.sendSnapToConversations(
                    conversationIds = conversationIds,
                    mediaData = mediaData,
                    onProgress = { sent, total ->
                        context.log.verbose("Batch progress: $sent/$total")
                    }
                )

                when (result) {
                    is SnapMediaHandler.SendResult.Success -> {
                        updateBatchStatus(
                            sessionId, batchId, BatchStatus.SENT,
                            sentAt = System.currentTimeMillis()
                        )

                        val allSent = session.batches.all {
                            it.status == BatchStatus.SENT || it.status == BatchStatus.CANCELLED
                        }

                        if (allSent &&
                            context.config.messaging.batchFriendSelector.notifyOnBatchComplete.value
                        ) {
                            notificationManager.showBatchComplete(
                                sessionId, totalBatches, session.friendIds.size
                            )
                        }
                        context.log.info("Batch $batchId sent successfully")
                    }

                    is SnapMediaHandler.SendResult.Failure -> {
                        throw Exception(result.error)
                    }
                }
            } catch (e: Exception) {
                updateBatchStatus(
                    sessionId, batchId, BatchStatus.FAILED, error = e.message
                )

                if (context.config.messaging.batchFriendSelector.notifyOnError.value) {
                    notificationManager.showBatchError(
                        sessionId, batchIndex + 1, e.message ?: "Unknown error"
                    )
                }
                context.log.error("Failed to send batch", e)
            }
        }
    }

    private suspend fun updateBatchStatus(
        sessionId: String,
        batchId: String,
        status: BatchStatus,
        sentAt: Long? = null,
        error: String? = null
    ) {
        sessionMutex.withLock {
            val session = batchSessions[sessionId] ?: return
            val index = session.batches.indexOfFirst { it.id == batchId }
            if (index == -1) return

            val updatedBatch = session.batches[index].copy(
                status = status, sentAt = sentAt, error = error
            )

            val updatedBatches = session.batches.toMutableList()
            updatedBatches[index] = updatedBatch
            batchSessions[sessionId] = session.copy(batches = updatedBatches)
        }
    }

    private suspend fun cleanupOldSessions() {
        sessionMutex.withLock {
            val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            batchSessions.entries.removeIf { (_, session) ->
                session.createdAt < cutoff && session.batches.all {
                    it.status != BatchStatus.PENDING && it.status != BatchStatus.SENDING
                }
            }
        }
    }
}
