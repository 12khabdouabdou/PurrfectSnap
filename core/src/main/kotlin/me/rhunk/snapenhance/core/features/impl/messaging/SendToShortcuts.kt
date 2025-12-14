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
        // Check if feature is enabled
        if (!context.config.messaging.sendToShortcuts.globalState) return
        
        // Get config values
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
            val destinations = event.destinations
            val destinationsClass = destinations.javaClass
            
            val stories = runCatching {
                destinationsClass.getDeclaredField("stories").apply { isAccessible = true }.get(destinations) as? List<*>
            }.getOrNull()
            
            val conversations = runCatching {
                destinationsClass.getDeclaredFiel
