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

            shouldModifySavePolicy = true
            
            // Modify mSavePolicy directly using XposedHelpers
            try {
                val savePolicyEnumClass = runCatching {
                    XposedHelpers.findClass("com.snapchat.client.messaging.SavePolicy", 
                        localMessageContent.instanceNonNull().javaClass.classLoader)
                }.getOrNull()
                
                if (savePolicyEnumClass != null && savePolicyEnumClass.isEnum) {
                    val enumConstants = savePolicyEnumClass.enumConstants
                    if (enumConstants != null && enumConstants.size > 2) {
                        val viewSessionEnum = enumConstants[2] as Enum<*>
                        val savePolicyField = localMessageContent.instanceNonNull().javaClass.declaredFields
                            .find { it.name == "mSavePolicy" }
                        
                        if (savePolicyField != null) {
                            @Suppress("UNCHECKED_CAST")
                            val fieldType = savePolicyField.type as Class<out Enum<*>>
                            val enumValue = java.lang.Enum.valueOf(fieldType, viewSessionEnum.name)
                            XposedHelpers.setObjectField(localMessageContent.instanceNonNull(), "mSavePolicy", enumValue)
                        }
                    }
                }
            } catch (e: Exception) {
                context.log.warn("UnsaveableMessages: Failed to set mSavePolicy: ${e.message}")
            }
            
            // Modify proto
            val messageContentBytes = localMessageContent.content ?: return@subscribe
            
            try {
                val protoReader = ProtoReader(messageContentBytes)
                val messageContentProto4 = protoReader.followPath(4)
                val messageContentProto2 = protoReader.followPath(2)
                
                val targetProto = messageContentProto4 ?: messageContentProto2
                if (targetProto == null) return@subscribe
                
                val fieldPath = if (messageContentProto4 != null) 4 else 2
                
                val modifiedContent = ProtoEditor(messageContentBytes).apply {
                    edit(fieldPath) {
                        if (getOrNull(7) != null) {
                            remove(7)
                        }
                        addVarInt(7, 3)
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
                event.buffer = ProtoEditor(event.buffer).apply {
                    edit(4) {
                        if (getOrNull(7) != null) {
                            remove(7)
                        }
                        addVarInt(7, 3)
                    }
                }.toByteArray()
                shouldModifySavePolicy = false
            } catch (e: Exception) {
                // Try field 2 as fallback
                try {
                    event.buffer = ProtoEditor(event.buffer).apply {
                        edit(2) {
                            if (getOrNull(7) != null) {
                                remove(7)
                            }
                            addVarInt(7, 3)
                        }
                    }.toByteArray()
                    shouldModifySavePolicy = false
                } catch (e2: Exception) {
                    context.log.error("UnsaveableMessages: NativeUnaryCallEvent modification failed: ${e2.message}")
                }
            }
        }

    }
}
