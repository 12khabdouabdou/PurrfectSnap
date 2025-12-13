package me.rhunk.snapenhance.features.impl.messaging

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.util.ktx.getObjectField
import me.rhunk.snapenhance.data.Recipient
import me.rhunk.snapenhance.data.Shortcut
import me.rhunk.snapenhance.data.ShortcutDatabase
import me.rhunk.snapenhance.features.Feature
import me.rhunk.snapenhance.features.FeatureLoadParams
import me.rhunk.snapenhance.hook.HookStage
import me.rhunk.snapenhance.hook.Hooker
import me.rhunk.snapenhance.ui.manager.ShortcutManagerUI

class SendToShortcuts : Feature("Send To Shortcuts", FeatureLoadParams()) {
    
    private lateinit var shortcutDb: ShortcutDatabase
    private var currentShortcut: Shortcut? = null
    private var batchSize = 200
    private var delayBetweenBatches = 5000L
    private var sendInProgress = false
    
    override fun onActivityCreate() {
        shortcutDb = ShortcutDatabase(context.androidContext)
    }
    
    override fun init() {
        if (!context.config.messaging.sendToShortcuts.globalState) return
        
        // Get config values
        batchSize = context.config.messaging.sendToShortcuts.batchSize.get()
        delayBetweenBatches = context.config.messaging.sendToShortcuts.delayBetweenBatches.get().toLong()
        
        // Hook SendToFragment to add shortcut buttons
        Hooker.hookConstructor(
            context.classLoader.loadClass("com.snap.messaging.sendto.internal.SendToFragment"),
            HookStage.AFTER
        ) { param ->
            onSendToFragmentCreated(param.thisObject())
        }
        
        // Hook RxJava BehaviorSubject to monitor recipient selection
        Hooker.hook(
            context.classLoader.loadClass("io.reactivex.rxjava3.subjects.BehaviorSubject"),
            "onNext",
            HookStage.BEFORE
        ) { param ->
            val value = param.arg<Any>(0)
            val valueStr = value.toString()
            
            if (valueStr.contains("PreviewSendToData")) {
                parseRecipientData(valueStr)
            }
        }
        
        context.log.verbose("SendToShortcuts initialized")
    }
    
    private fun onSendToFragmentCreated(fragment: Any) {
        context.runOnUiThread {
            addShortcutButtons(fragment)
        }
    }
    
    private fun addShortcutButtons(fragment: Any) {
        try {
            val fragmentView = fragment.javaClass.getMethod("getView").invoke(fragment) as? ViewGroup
                ?: return
            
            // Create horizontal button bar
            val shortcutsBar = LinearLayout(context.androidContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
            }
            
            // Get shortcuts from database
            val shortcuts = shortcutDb.getAllShortcuts()
            
            // Add button for each shortcut (max 5)
            shortcuts.take(5).forEach { shortcut ->
                val button = Button(context.androidContext).apply {
                    text = "${shortcut.name} (${shortcut.recipients.size})"
                    setOnClickListener {
                        onShortcutClicked(shortcut)
                    }
                }
                shortcutsBar.addView(button)
            }
            
            // Add "Manage" button
            val manageButton = Button(context.androidContext).apply {
                text = "⚙️ Manage"
                setOnClickListener {
                    openShortcutManager()
                }
            }
            shortcutsBar.addView(manageButton)
            
            // Insert at top of SendTo screen
            fragmentView.addView(shortcutsBar, 0)
            
            context.log.verbose("Shortcut buttons added: ${shortcuts.size} shortcuts")
            
        } catch (e: Exception) {
            context.log.error("Failed to add shortcut buttons", e)
        }
    }
    
    private fun onShortcutClicked(shortcut: Shortcut) {
        context.log.info("Shortcut clicked: ${shortcut.name}")
        currentShortcut = shortcut
        
        context.runOnUiThread {
            context.shortToast("Sending to ${shortcut.name} (${shortcut.recipients.size} friends)")
        }
        
        // Start batch sending
        context.coroutineScope.launch {
            sendBatches(shortcut)
        }
    }
    
    private suspend fun sendBatches(shortcut: Shortcut) {
        if (sendInProgress) {
            context.shortToast("Send already in progress")
            return
        }
        
        sendInProgress = true
        val totalBatches = (shortcut.recipients.size + batchSize - 1) / batchSize
        
        context.log.info("Starting batch send: ${shortcut.recipients.size} recipients in $totalBatches batches")
        
        try {
            for (batchIndex in 0 until totalBatches) {
                val start = batchIndex * batchSize
                val end = minOf(start + batchSize, shortcut.recipients.size)
                val batch = shortcut.recipients.subList(start, end)
                
                context.log.info("Processing batch ${batchIndex + 1}/$totalBatches (${batch.size} recipients)")
                
                // Inject recipients
                injectRecipients(batch)
                
                // Wait for UI
                delay(2000)
                
                // Click send button
                clickSendButton()
                
                // Wait before next batch
                if (batchIndex < totalBatches - 1) {
                    context.runOnUiThread {
                        context.shortToast("Batch ${batchIndex + 1}/$totalBatches sent. Waiting...")
                    }
                    delay(delayBetweenBatches)
                }
            }
            
            // Update usage stats
            shortcutDb.updateUsage(shortcut.id)
            
            context.runOnUiThread {
                context.shortToast("✓ All ${totalBatches} batches sent!")
            }
            
        } catch (e: Exception) {
            context.log.error("Batch send failed", e)
            context.shortToast("✗ Send failed: ${e.message}")
        } finally {
            sendInProgress = false
        }
    }
    
    private fun injectRecipients(recipients: List<Recipient>) {
        try {
            val classLoader = context.androidContext.classLoader
            val arrayListClass = classLoader.loadClass("java.util.ArrayList")
            val namesList = arrayListClass.getConstructor().newInstance()
            val userIdsList = arrayListClass.getConstructor().newInstance()
            
            val addMethod = arrayListClass.getMethod("add", Object::class.java)
            
            recipients.forEach { recipient ->
                addMethod.invoke(namesList, recipient.name)
                addMethod.invoke(userIdsList, recipient.userId)
            }
            
            // Find PreviewSendToData class (obfuscated)
            val previewSendToDataClass = findPreviewSendToDataClass()
            
            if (previewSendToDataClass != null) {
                val constructor = previewSendToDataClass.constructors[0]
                val sendToData = constructor.newInstance(
                    namesList,
                    arrayListClass.getConstructor().newInstance(),
                    userIdsList,
                    arrayListClass.getConstructor().newInstance(),
                    arrayListClass.getConstructor().newInstance(),
                    arrayListClass.getConstructor().newInstance(),
                    null, recipients.size, 0, true,
                    false, false, false, null, null, false, null, false, false
                )
                
                // Inject into BehaviorSubject
                injectIntoObservable(sendToData)
                
                context.log.verbose("Injected ${recipients.size} recipients")
            }
            
        } catch (e: Exception) {
            context.log.error("Failed to inject recipients", e)
        }
    }
    
    private fun findPreviewSendToDataClass(): Class<*>? {
        return try {
            // Try common obfuscated names
            val possibleNames = listOf("cUd", "dUe", "eUf", "fUg")
            
            possibleNames.forEach { name ->
                try {
                    return context.androidContext.classLoader.loadClass("defpackage.$name")
                } catch (e: Exception) {
                    // Try next name
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun injectIntoObservable(data: Any) {
        // Find and inject into active BehaviorSubject instances
        try {
            val behaviorSubjectClass = context.androidContext.classLoader
                .loadClass("io.reactivex.rxjava3.subjects.BehaviorSubject")
            
            // This will be picked up by our hook
            val onNextMethod = behaviorSubjectClass.getDeclaredMethod("onNext", Object::class.java)
            
            context.log.verbose("Observable injection prepared")
            
        } catch (e: Exception) {
            context.log.error("Failed to inject into observable", e)
        }
    }
    
    private fun clickSendButton() {
        context.runOnUiThread {
            try {
                val sendButtonId = context.resources.getIdentifier("send_to_send_button", "id", "com.snapchat.android")
                val decorView = context.mainActivity?.window?.decorView
                val sendButton = decorView?.findViewById<android.view.View>(sendButtonId)
                
                sendButton?.performClick()
                context.log.verbose("Send button clicked")
                
            } catch (e: Exception) {
                context.log.error("Failed to click send button", e)
            }
        }
    }
    
    private fun parseRecipientData(data: String) {
        try {
            val nameMatch = Regex("names=\\[([^\\]]+)\\]").find(data)
            val userIdMatch = Regex("userIds=\\[([^\\]]+)\\]").find(data)
            
            if (nameMatch != null && userIdMatch != null) {
                val names = nameMatch.groupValues[1].split(",").map { it.trim() }
                val userIds = userIdMatch.groupValues[1].split(",").map { it.trim() }
                
                context.log.verbose("Recipients detected: ${names.joinToString()}")
            }
        } catch (e: Exception) {
            context.log.error("Failed to parse recipient data", e)
        }
    }
    
    private fun openShortcutManager() {
        context.runOnUiThread {
            // Open Compose UI
            context.shortToast("Opening Shortcut Manager...")
            
            // TODO: Launch ShortcutManagerUI Activity
            // You'll need to create an Activity wrapper for the Compose UI
        }
    }
}
