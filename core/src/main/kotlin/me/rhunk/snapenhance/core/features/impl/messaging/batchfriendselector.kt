package me.rhunk.snapenhance.core.features.impl.messaging

import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BatchFriendSelector : Feature("Batch Friend Selector", loadParams = FeatureLoadParams.INIT_SYNC) {
    
    companion object {
        private const val DEFAULT_BATCH_SIZE = 100
        private const val MAX_BATCH_SESSIONS = 10
    }
    
    private val batchSessions = ConcurrentHashMap<String, BatchSession>()
    private val sessionMutex = Mutex()
    private lateinit var notificationManager: BatchNotificationManager
    
    data class BatchSession(
        val id: String = UUID.randomUUID().toString(),
        val friendIds: List<String>,
        val batches: List<FriendBatch>,
        val snapMedia: SnapMediaData? = null,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    data class FriendBatch(
        val id: String = UUID.randomUUID().toString(),
        val friendIds: List<String>,
        val status: BatchStatus = BatchStatus.PENDING,
        val sentAt: Long? = null,
        val error: String? = null
    )
    
    enum class BatchStatus {
        PENDING,
        SENDING,
        SENT,
        FAILED,
        CANCELLED
    }
    
    data class SnapMediaData(
        val mediaPath: String,
        val mediaType: String,
        val duration: Long? = null
    )
    
    override fun init() {
        val config = context.config.messaging.batchFriendSelector
        
        if (!config.enabled.get()) {
            return
        }
        
        context.log.info("BatchFriendSelector initialized with batch size: ${config.batchSize.get()}")
        
        // Hook Snapchat's friend selection validation
        hookFriendSelection()
        
        // Cleanup old sessions periodically
        context.coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(300000L) // 5 minutes
                cleanupOldSessions()
            }
        }
    }
    
    private fun hookFriendSelection() {
        try {
            // Hook the send validation method
            findClass("com.snapchat.client.messaging.SendToViewModel")?.apply {
                hook("validateFriendSelection", HookStage.BEFORE) { param ->
                    try {
                        val selectedFriends = param.arg<List<Any>>(0)
                        val batchSize = context.config.messaging.batchFriendSelector.batchSize.get()
                        
                        if (selectedFriends.size > batchSize) {
                            context.log.info("Friend selection exceeds limit (${selectedFriends.size}), launching batch manager")
                            
                            // Block native execution
                            param.setResult(null)
                            
                            // Extract friend IDs
                            val friendIds = selectedFriends.mapNotNull { friend ->
                                extractFriendId(friend)
                            }
                            
                            // Launch batch manager UI
                            context.coroutineScope.launch(Dispatchers.Main) {
                                launchBatchManager(friendIds)
                            }
                        }
                    } catch (e: Exception) {
                        context.log.error("Error in friend selection hook", e)
                    }
                }
            }
            
            // Also hook the send button to intercept before Snapchat's validation
            findClass("com.snapchat.client.messaging.SendToFragment")?.apply {
                hook("onSendButtonClicked", HookStage.BEFORE) { param ->
                    try {
                        // Get selected friends count
                        val selectedCount = getSelectedFriendsCount(param.thisObject())
                        val batchSize = context.config.messaging.batchFriendSelector.batchSize.get()
                        
                        if (selectedCount > batchSize) {
                            context.log.info("Intercepting send with $selectedCount friends")
                            param.setResult(null)
                            
                            val friendIds = extractSelectedFriendIds(param.thisObject())
                            context.coroutineScope.launch(Dispatchers.Main) {
                                launchBatchManager(friendIds)
                            }
                        }
                    } catch (e: Exception) {
                        context.log.error("Error intercepting send button", e)
                    }
                }
            }
        } catch (e: Exception) {
            context.log.error("Error setting up friend selection hooks", e)
        }
    }
    
    private fun extractFriendId(friendObject: Any): String? {
        return try {
            // Try different methods to extract friend ID
            friendObject.javaClass.methods.firstOrNull { 
                it.name == "getUserId" || it.name == "getId" || it.name == "getFriendUserId"
            }?.invoke(friendObject)?.toString()
        } catch (e: Exception) {
            context.log.warn("Failed to extract friend ID", e)
            null
        }
    }
    
    private fun getSelectedFriendsCount(fragmentObject: Any): Int {
        return try {
            fragmentObject.javaClass.methods.firstOrNull {
                it.name == "getSelectedFriendsCount" || it.name == "getSelectedCount"
            }?.invoke(fragmentObject) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun extractSelectedFriendIds(fragmentObject: Any): List<String> {
        return try {
            val selectedFriends = fragmentObject.javaClass.methods.firstOrNull {
                it.name == "getSelectedFriends" || it.name == "getSelected"
            }?.invoke(fragmentObject) as? List<*>
            
            selectedFriends?.mapNotNull { extractFriendId(it!!) } ?: emptyList()
        } catch (e: Exception) {
            context.log.error("Failed to extract selected friend IDs", e)
            emptyList()
        }
    }
    
    suspend fun launchBatchManager(friendIds: List<String>) {
        sessionMutex.withLock {
            // Create new batch session
            val batchSize = context.config.messaging.batchFriendSelector.batchSize.get()
            val batches = createBatches(friendIds, batchSize)
            
            val session = BatchSession(
                friendIds = friendIds,
                batches = batches
            )
            
            batchSessions[session.id] = session
            
            // Cleanup old sessions if too many
            if (batchSessions.size > MAX_BATCH_SESSIONS) {
                val oldestSessionId = batchSessions.entries
                    .minByOrNull { it.value.createdAt }?.key
                oldestSessionId?.let { batchSessions.remove(it) }
            }
            
            // Launch UI
            context.log.info("Created batch session ${session.id} with ${batches.size} batches")
            showBatchManagerUI(session.id)
        }
    }
    
    private fun createBatches(friendIds: List<String>, batchSize: Int): List<FriendBatch> {
        return friendIds.chunked(batchSize).map { chunk ->
            FriendBatch(friendIds = chunk)
        }
    }
    
    private fun showBatchManagerUI(sessionId: String) {
        try {
            // Use Snapchat's navigation to show custom UI
            context.androidContext.runOnUiThread {
                // Create and show the batch manager dialog/activity
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
    
    suspend fun sendBatch(sessionId: String, batchId: String) {
        sessionMutex.withLock {
            val session = batchSessions[sessionId] ?: run {
                context.log.error("Batch session not found: $sessionId")
                return
            }
            
            val batchIndex = session.batches.indexOfFirst { it.id == batchId }
            if (batchIndex == -1) {
                context.log.error("Batch not found: $batchId")
                return
            }
            
            val batch = session.batches[batchIndex]
            
            // Update status to sending
            updateBatchStatus(sessionId, batchId, BatchStatus.SENDING)
            
            try {
                // Send snap to all friends in batch
                val conversationIds = batch.friendIds.map { SnapUUID(it) }
                
                context.messageSender.sendChatMessage(
                    conversations = conversationIds,
                    message = "Batch snap", // Replace with actual snap sending logic
                    onSuccess = {
                        context.coroutineScope.launch {
                            updateBatchStatus(
                                sessionId, 
                                batchId, 
                                BatchStatus.SENT,
                                sentAt = System.currentTimeMillis()
                            )
                            context.log.info("Batch $batchId sent successfully")
                        }
                    },
                    onError = { error ->
                        context.coroutineScope.launch {
                            updateBatchStatus(
                                sessionId,
                                batchId,
                                BatchStatus.FAILED,
                                error = error
                            )
                            context.log.error("Batch $batchId failed: $error")
                        }
                    }
                )
            } catch (e: Exception) {
                updateBatchStatus(
                    sessionId,
                    batchId,
                    BatchStatus.FAILED,
                    error = e.message
                )
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
            val batchIndex = session.batches.indexOfFirst { it.id == batchId }
            
            if (batchIndex != -1) {
                val updatedBatch = session.batches[batchIndex].copy(
                    status = status,
                    sentAt = sentAt,
                    error = error
                )
                
                val updatedBatches = session.batches.toMutableList()
                updatedBatches[batchIndex] = updatedBatch
                
                batchSessions[sessionId] = session.copy(batches = updatedBatches)
            }
        }
    }
    
    suspend fun getBatchSession(sessionId: String): BatchSession? {
        return sessionMutex.withLock {
            batchSessions[sessionId]
        }
    }
    
    suspend fun cancelBatch(sessionId: String, batchId: String) {
        updateBatchStatus(sessionId, batchId, BatchStatus.CANCELLED)
    }
    
    suspend fun deleteSession(sessionId: String) {
        sessionMutex.withLock {
            batchSessions.remove(sessionId)
        }
    }
    
    private suspend fun cleanupOldSessions() {
        sessionMutex.withLock {
            val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
            
            batchSessions.entries.removeIf { (_, session) ->
                session.createdAt < cutoffTime && 
                session.batches.all { it.status != BatchStatus.PENDING && it.status != BatchStatus.SENDING }
            }
        }
    }
}
