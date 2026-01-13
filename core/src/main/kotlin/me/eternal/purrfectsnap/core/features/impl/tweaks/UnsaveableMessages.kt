package me.eternal.purrfectsnap.core.features.impl.tweaks

import de.robv.android.xposed.XposedHelpers
import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.common.data.MessagingRuleType
import me.eternal.purrfectsnap.common.util.protobuf.ProtoEditor
import me.eternal.purrfectsnap.common.util.protobuf.ProtoReader
import me.eternal.purrfectsnap.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfectsnap.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfectsnap.core.features.MessagingRuleFeature

class UnsaveableMessages : MessagingRuleFeature(
    "Unsaveable Messages",
    MessagingRuleType.UNSAVEABLE_MESSAGES
) {
    private var shouldModifySavePolicy = false

    override fun init() {
        val ruleState = context.config.rules.getRuleState(MessagingRuleType.UNSAVEABLE_MESSAGES)
        if (ruleState == null) return

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            shouldModifySavePolicy = false
            
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe
            
            val localMessageContent = event.messageContent
            if (localMessageContent.contentType == ContentType.STATUS) return@subscribe

            val conversationIds = event.destinations.conversations?.map { it.toString() } ?: emptyList()
            val shouldApply = if (conversationIds.isEmpty()) {
                true
            } else {
                conversationIds.all { canUseRule(it) }
            }

            if (!shouldApply) return@subscribe

            val messageContentBytes = localMessageContent.content ?: return@subscribe

            val config = context.config.messaging.unsaveableMessages
            val enabledFields = mutableListOf<Int>()
            if (config.chat.get()) enabledFields.add(2)
            if (config.snap.get()) enabledFields.add(11)
            if (config.externalMedia.get()) enabledFields.add(3)
            if (config.sticker.get()) enabledFields.add(4)
            if (config.share.get()) enabledFields.add(5)
            if (config.note.get()) enabledFields.add(6)
            if (config.storyReply.get()) enabledFields.add(7)

            val protoReader = ProtoReader(messageContentBytes)

            val fieldPath = enabledFields.firstOrNull { fieldId ->
                protoReader.followPath(fieldId) != null
            } ?: return@subscribe

            shouldModifySavePolicy = true
            
            try {
                val savePolicyEnumClass = runCatching {
                    XposedHelpers.findClass("com.snapchat.client.messaging.SavePolicy", 
                        localMessageContent.instanceNonNull().javaClass.classLoader)
                }.getOrNull()
                
                if (savePolicyEnumClass != null && savePolicyEnumClass.isEnum) {
                    @Suppress("UNCHECKED_CAST")
                    val enumClass = savePolicyEnumClass as Class<out Enum<*>>
                    val prohibitedEnum = runCatching {
                        java.lang.Enum.valueOf(enumClass, "PROHIBITED")
                    }.getOrNull()
                    
                    if (prohibitedEnum != null) {
                        val savePolicyField = localMessageContent.instanceNonNull().javaClass.declaredFields
                            .find { it.name == "mSavePolicy" }
                        
                        if (savePolicyField != null) {
                            savePolicyField.isAccessible = true
                            XposedHelpers.setObjectField(localMessageContent.instanceNonNull(), "mSavePolicy", prohibitedEnum)
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.warn("UnsaveableMessages: Failed to set mSavePolicy: ${e.message}")
            }
            
            try {
                val modifiedContent = ProtoEditor(messageContentBytes).apply {
                    edit(fieldPath) {
                        if (getOrNull(7) != null) {
                            remove(7)
                        }
                        addVarInt(7, 1)
                    }
                }.toByteArray()
                
                localMessageContent.content = modifiedContent
                
            } catch (e: Exception) {
                context.log.error("UnsaveableMessages: Failed to modify savePolicy in proto: ${e.message}")
            }
        }

        // Modify in NativeUnaryCallEvent as fallback
        context.event.subscribe(NativeUnaryCallEvent::class) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
            if (!shouldModifySavePolicy) return@subscribe
            
            try {
                val config = context.config.messaging.unsaveableMessages
                val enabledFields = mutableListOf<Int>()
                if (config.chat.get()) enabledFields.add(2)
                if (config.snap.get()) enabledFields.add(11)
                if (config.externalMedia.get()) enabledFields.add(3)
                if (config.sticker.get()) enabledFields.add(4)
                if (config.share.get()) enabledFields.add(5)
                if (config.note.get()) enabledFields.add(6)
                if (config.storyReply.get()) enabledFields.add(7)

                val protoReader = ProtoReader(event.buffer)
                val fieldPath = enabledFields.firstOrNull { fieldId ->
                    protoReader.followPath(fieldId) != null
                } ?: return@subscribe

                try {
                    event.buffer = ProtoEditor(event.buffer).apply {
                        edit(fieldPath) {
                            if (getOrNull(7) != null) {
                                remove(7)
                            }
                            addVarInt(7, 1)
                        }
                    }.toByteArray()
                } catch (e: Exception) {
                    context.log.error("UnsaveableMessages: NativeUnaryCallEvent proto modification failed for field $fieldPath: ${e.message}")
                }
                shouldModifySavePolicy = false
            } catch (e: Exception) {
                context.log.error("UnsaveableMessages: NativeUnaryCallEvent modification failed: ${e.message}")
            }
        }
    }
}
