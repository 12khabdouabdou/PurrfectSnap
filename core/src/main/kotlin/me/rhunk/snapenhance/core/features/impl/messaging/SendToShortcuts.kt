package me.rhunk.snapenhance.core.features.impl.messaging

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Send
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.data.Shortcut
import me.rhunk.snapenhance.data.ShortcutDatabase
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook

class SendToShortcuts : Feature("Send To Shortcuts", loadParams = FeatureLoadParams.ACTIVITY_CREATE_SYNC) {
    
    private lateinit var shortcutDb: ShortcutDatabase
    private var activeBatchSend: BatchSendState? = null
    
    private data class BatchSendState(
        val shortcut: Shortcut,
        var originalMessageContent: Any? = null,
        var originalDestinations: Any? = null,
        val batchSize: Int,
        val delayMs: Long,
        var currentBatchIndex: Int = 0,
        var totalSent: Int = 0
    )
    
    override fun onActivityCreate() {
        shortcutDb = ShortcutDatabase(context.androidContext)
    }
    
    override fun init() {
        if (!context.config.messaging.sendToShortcuts.globalState) return
        
        val batchSize = context.config.messaging.sendToShortcuts.batchSize.get()
        val delayBetweenBatches = context.config.messaging.sendToShortcuts.delayBetweenBatches.get().toLong()
        
        // Hook BehaviorSubject to modify recipient data for UI preview
        hookRecipientData()
        
        // Hook SendToFragment to add shortcut buttons
        hookSendToFragment()
        
        // Intercept send events for batch processing
        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            val batchState = activeBatchSend ?: return@subscribe
            
            // Skip story sends
            if (event.destinations.stories?.isNotEmpty() == true && 
                event.destinations.conversations?.isEmpty() == true) {
                return@subscribe
            }
            
            // Cancel this event - we'll handle it
            event.canceled = true
            
            context.log.info("Intercepted send event - starting batch processing")
            
            // Capture the original content and destinations
            batchState.originalMessageContent = cloneMessageContent(event.messageContent)
            batchState.originalDestinations = event.destinations
            
            // Process all batches
            context.coroutineScope.launch {
                processBatches(batchState)
            }
        }
        
        context.log.verbose("SendToShortcuts initialized")
    }
    
    private fun cloneMessageContent(messageContent: Any): Any {
        try {
            val contentType = messageContent.javaClass.getDeclaredField("mContentType")
                .apply { isAccessible = true }
                .get(messageContent)
            
            val content = messageContent.javaClass.getDeclaredField("mContent")
                .apply { isAccessible = true }
                .get(messageContent) as? ByteArray
            
            context.log.verbose("Cloned message content: type=$contentType, size=${content?.size ?: 0}")
            
            val clazz = messageContent.javaClass
            val constructor = clazz.getDeclaredConstructor()
            constructor.isAccessible = true
            val cloned = constructor.newInstance()
            
            clazz.getDeclaredField("mContentType").apply {
                isAccessible = true
                set(cloned, contentType)
            }
            
            content?.let { originalContent ->
                clazz.getDeclaredField("mContent").apply {
                    isAccessible = true
                    set(cloned, originalContent.copyOf())
                }
            }
            
            return cloned
            
        } catch (e: Exception) {
            context.log.error("Failed to clone message content", e)
            return messageContent
        }
    }
    
    private suspend fun processBatches(batchState: BatchSendState) {
        val totalBatches = getTotalBatches(batchState)
        
        context.log.info("Starting batch send: ${batchState.shortcut.recipients.size} recipients in $totalBatches batches")
        
        try {
            for (batchIndex in 0 until totalBatches) {
                val start = batchIndex * batchState.batchSize
                val end = minOf(start + batchState.batchSize, batchState.shortcut.recipients.size)
                val currentBatch = batchState.shortcut.recipients.subList(start, end)
                
                context.log.info("Sending batch ${batchIndex + 1}/$totalBatches: ${currentBatch.size} recipients")
                
                context.runOnUiThread {
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Default.Send,
                        text = "Batch ${batchIndex + 1}/$totalBatches\nSending to ${currentBatch.size} friends..."
                    )
                }
                
                sendBatch(batchState, currentBatch)
                
                batchState.totalSent += currentBatch.size
                
                context.log.info("Batch ${batchIndex + 1} sent successfully (${batchState.totalSent} total)")
                
                if (batchIndex < totalBatches - 1) {
                    context.log.verbose("Waiting ${batchState.delayMs}ms before next batch...")
                    delay(batchState.delayMs)
                }
            }
            
            finishBatchSend(batchState)
            
        } catch (e: Exception) {
            context.log.error("Batch send failed", e)
            
            context.runOnUiThread {
                context.inAppOverlay.showStatusToast(
                    icon = Icons.Default.Group,
                    text = "❌ Batch send failed\nSent to ${batchState.totalSent}/${batchState.shortcut.recipients.size} friends"
                )
            }
            
            activeBatchSend = null
        }
    }
    
    private fun sendBatch(batchState: BatchSendState, batch: List<me.rhunk.snapenhance.data.Recipient>) {
        try {
            val batchConversations = batch.map { recipient ->
                recipient.userId
            }.toMutableList()
            
            val destinationsClass = batchState.originalDestinations!!.javaClass
            val clonedDestinations = destinationsClass.getDeclaredConstructor().newInstance()
            
            destinationsClass.getDeclaredField("conversations").apply {
                isAccessible = true
                set(clonedDestinations, batchConversations)
            }
            
            destinationsClass.getDeclaredField("stories").apply {
                isAccessible = true
                set(clonedDestinations, mutableListOf<String>())
            }
            
            val batchMessageContent = cloneMessageContent(batchState.originalMessageContent!!)
            
            val batchEvent = SendMessageWithContentEvent(
                destinations = clonedDestinations,
                messageContent = batchMessageContent
            )
            
            batchEvent.invokeOriginal()
            
            context.log.verbose("Batch sent to ${batch.size} recipients")
            
        } catch (e: Exception) {
            context.log.error("Failed to send batch", e)
            throw e
        }
    }
    
    private fun hookRecipientData() {
        try {
            val behaviorSubjectClass = context.androidContext.classLoader
                .loadClass("io.reactivex.rxjava3.subjects.BehaviorSubject")
            
            behaviorSubjectClass.hook("onNext", HookStage.BEFORE) { param ->
                val value = param.arg<Any>(0) ?: return@hook
                val className = value.javaClass.name
                
                if (className == "cUd") {
                    val batchState = activeBatchSend ?: return@hook
                    
                    try {
                        modifyRecipientDataForPreview(value, batchState)
                    } catch (e: Exception) {
                        context.log.error("Failed to modify recipient data", e)
                    }
                }
            }
            
            context.log.verbose("BehaviorSubject hooked successfully")
            
        } catch (e: Exception) {
            context.log.error("Failed to hook BehaviorSubject", e)
        }
    }
    
    private fun modifyRecipientDataForPreview(previewSendToData: Any, batchState: BatchSendState) {
        val clazz = previewSendToData.javaClass
        val allRecipients = batchState.shortcut.recipients
        
        context.log.verbose("Modifying preview to show all ${allRecipients.size} recipients")
        
        val namesField = clazz.getDeclaredField("a").apply { isAccessible = true }
        val userIdsField = clazz.getDeclaredField("c").apply { isAccessible = true }
        val countField = clazz.getDeclaredField("h").apply { isAccessible = true }
        val snapSendField = clazz.getDeclaredField("j").apply { isAccessible = true }
        
        val arrayListClass = Class.forName("java.util.ArrayList")
        val newNames = arrayListClass.getDeclaredConstructor().newInstance()
        val newIds = arrayListClass.getDeclaredConstructor().newInstance()
        val addMethod = arrayListClass.getMethod("add", Object::class.java)
        
        allRecipients.take(200).forEach { recipient ->
            addMethod.invoke(newNames, recipient.name)
            addMethod.invoke(newIds, recipient.userId)
        }
        
        namesField.set(previewSendToData, newNames)
        userIdsField.set(previewSendToData, newIds)
        countField.setInt(previewSendToData, allRecipients.size)
        snapSendField.setBoolean(previewSendToData, true)
        
        context.log.verbose("Modified preview to show ${allRecipients.size} recipients")
    }
    
    private fun hookSendToFragment() {
        try {
            val sendToFragmentClass = context.androidContext.classLoader
                .loadClass("com.snap.messaging.sendto.internal.SendToFragment")
            
            sendToFragmentClass.getDeclaredConstructor().hook(HookStage.AFTER) { param ->
                context.runOnUiThread {
                    addShortcutButtons(param.thisObject())
                }
            }
            
            context.log.verbose("SendToFragment hooked successfully")
            
        } catch (e: Exception) {
            context.log.error("Failed to hook SendToFragment", e)
        }
    }
    
    private fun addShortcutButtons(fragment: Any) {
        try {
            val fragmentView = fragment.javaClass.getMethod("getView")
                .invoke(fragment) as? ViewGroup ?: return
            
            val buttonBar = LinearLayout(context.androidContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setPadding(16, 16, 16, 16)
                }
            }
            
            val shortcuts = shortcutDb.getAllShortcuts()
            
            if (shortcuts.isEmpty()) {
                context.log.verbose("No shortcuts to display")
                return
            }
            
            shortcuts.take(5).forEach { shortcut ->
                val button = Button(context.androidContext).apply {
                    text = "${shortcut.name} (${shortcut.recipients.size})"
                    setOnClickListener {
                        onShortcutClicked(shortcut)
                    }
                }
                buttonBar.addView(button)
            }
            
            val manageButton = Button(context.androidContext).apply {
                text = "⚙️"
                setOnClickListener {
                    context.shortToast("Opening Shortcut Manager...")
                }
            }
            buttonBar.addView(manageButton)
            
            fragmentView.addView(buttonBar, 0)
            
            context.log.verbose("Added ${shortcuts.size} shortcut buttons")
            
        } catch (e: Exception) {
            context.log.error("Failed to add shortcut buttons", e)
        }
    }
    
    private fun onShortcutClicked(shortcut: Shortcut) {
        context.log.info("Shortcut clicked: ${shortcut.name} (${shortcut.recipients.size} recipients)")
        
        val batchSize = context.config.messaging.sendToShortcuts.batchSize.get()
        val delayMs = context.config.messaging.sendToShortcuts.delayBetweenBatches.get().toLong()
        val totalBatches = (shortcut.recipients.size + batchSize - 1) / batchSize
        
        context.inAppOverlay.showStatusToast(
            icon = Icons.Default.Group,
            text = "📤 ${shortcut.name}\n${shortcut.recipients.size} friends in $totalBatches batches\n\nClick Send to start"
        )
        
        activeBatchSend = BatchSendState(
            shortcut = shortcut,
            batchSize = batchSize,
            delayMs = delayMs
        )
        
        context.log.info("Batch send armed: $totalBatches batches of $batchSize")
    }
    
    private fun finishBatchSend(batchState: BatchSendState) {
        context.log.info("All batches sent: ${batchState.totalSent} recipients")
        
        shortcutDb.updateUsage(batchState.shortcut.id)
        
        context.runOnUiThread {
            context.inAppOverlay.showStatusToast(
                icon = Icons.Default.Group,
                text = "✅ Sent to ${batchState.shortcut.name}\n${batchState.totalSent} friends!"
            )
        }
        
        activeBatchSend = null
    }
    
    private fun getTotalBatches(batchState: BatchSendState): Int {
        return (batchState.shortcut.recipients.size + batchState.batchSize - 1) / batchState.batchSize
    }
}
