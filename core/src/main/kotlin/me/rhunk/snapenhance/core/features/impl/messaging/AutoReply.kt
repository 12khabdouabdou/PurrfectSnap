package me.rhunk.snapenhance.core.features.impl.messaging

import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.MessagingRuleType
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.core.event.events.impl.BuildMessageEvent
import me.rhunk.snapenhance.core.event.events.impl.ConversationUpdateEvent
import me.rhunk.snapenhance.core.features.MessagingRuleFeature
import me.rhunk.snapenhance.core.features.impl.spying.HalfSwipeNotifier
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.wrapper.impl.Message
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.core.wrapper.impl.getMessageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.rhunk.snapenhance.core.wrapper.impl.MessageContent
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.util.LinkedList
import kotlin.random.Random

class AutoReply : MessagingRuleFeature("Auto Reply", MessagingRuleType.AUTO_REPLY) {
    companion object {
        private const val MAX_PROCESSED_MESSAGES = 5000
        private const val MAX_MESSAGE_INDICES = 500
        private const val CLEANUP_INTERVAL_MS = 180000L
        private const val COOLDOWN_CLEANUP_THRESHOLD_MS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_FALLBACK_MESSAGE = "I'm currently away and will respond soon!"
        private const val MAX_CONVERSATION_HISTORY = 20
        private const val AI_REQUEST_TIMEOUT_MS = 15000L
        
        // Response tracking constants
        private const val MAX_RECENT_RESPONSES = 10
        private const val RESPONSE_SIMILARITY_THRESHOLD = 0.7
        private const val MIN_RESPONSE_VARIATION_INTERVAL = 3
    }

    private val processedMessages = CopyOnWriteArraySet<Long>()
    private val conversationCooldowns = ConcurrentHashMap<String, Long>()
    private val activeConversations = CopyOnWriteArraySet<String>()
    private val messageIndices = ConcurrentHashMap<String, Int>()
    private val conversationHistory = ConcurrentHashMap<String, LinkedList<ConversationMessage>>()
    private val lastCleanup = AtomicLong(System.currentTimeMillis())
    
    // Response tracking to prevent repetition
    private val recentResponses = ConcurrentHashMap<String, LinkedList<String>>()
    private val responseVariations = ConcurrentHashMap<String, Int>()
    private val conversationPersonality = ConcurrentHashMap<String, String>()
    
    private val cooldownMutex = Mutex()
    private val indicesMutex = Mutex()
    private val messageProcessingMutex = Mutex()
    private val historyMutex = Mutex()
    private val responseMutex = Mutex()
    
    private val gson = Gson()
    private val messagingFeature by lazy { context.feature(Messaging::class) }
    
    data class ConversationMessage(
        val content: String,
        val isFromMe: Boolean,
        val timestamp: Long,
        val contentType: ContentType?
    )
    
    data class AIRequest(
        val model: String,
        val messages: List<AIMessage>,
        val max_tokens: Int,
        val temperature: Double,
        val stream: Boolean = false
    )
    
    data class AIMessage(
        val role: String,
        val content: String
    )
    
    data class AIResponse(
        val choices: List<AIChoice>
    )
    
    data class AIChoice(
        val message: AIMessage
    )
    
    private fun detectStoryContentType(messageContent: MessageContent?): ContentType? {
        if (messageContent?.content == null) return null
        
        try {
            val protoReader = me.rhunk.snapenhance.common.util.protobuf.ProtoReader(messageContent.content!!)
            
            if (protoReader.contains(7)) {
                return ContentType.STORY_REPLY
            }
            
            if (protoReader.contains(5)) {
                protoReader.followPath(5)?.let { share ->
                    if (share.contains(16)) {
                        return ContentType.SHARE
                    }
                }
            }
            
            if (protoReader.contains(3)) {
                protoReader.followPath(3)?.let { external ->
                    if (external.contains(7) || external.contains(5)) {
                        val storyText = protoReader.getString(7, 11, 1)
                        if (storyText != null) {
                            return ContentType.STORY_REPLY
                        }
                    }
                }
            }
            
            return null
        } catch (e: Exception) {
            context.log.error("Error detecting story content type", e)
            return null
        }
    }
    
    private suspend fun canSendAutoReply(
        conversationId: String,
        messageTime: Long? = null,
        isHalfSwipe: Boolean = false
    ): Boolean {
        val config = context.config.messaging.autoReply
        val currentTime = System.currentTimeMillis()
        
        try {
            if (config.globalState != true) {
                return false
            }
            
            if (!canUseRule(conversationId)) {
                return false
            }
            
            if (activeConversations.contains(conversationId)) {
                return false
            }
            
            val cooldownMs = config.cooldownSeconds.get() * 1000L
            cooldownMutex.withLock {
                val lastReplyTime = conversationCooldowns[conversationId] ?: 0
                if (currentTime - lastReplyTime < cooldownMs) {
                    return false
                }
            }
            
            messageTime?.let { msgTime ->
                val ageThresholdMs = config.messageAgeThreshold.get() * 1000L
                if (currentTime - msgTime > ageThresholdMs) {
                    return false
                }
            }
            
            if (isHalfSwipe && !config.autoTriggerConfig.autoReplyContentTypes.get().contains("half_swipes")) {
                return false
            }
            
            return true
            
        } catch (e: Exception) {
            context.log.error("Error in auto-reply validation", e)
            return false
        }
    }
    
    private fun shouldReplyToContentType(contentType: ContentType?): Boolean {
        val config = context.config.messaging.autoReply
        val selectedContentTypes = config.autoTriggerConfig.autoReplyContentTypes.get()
        
        return when (contentType) {
            ContentType.CHAT -> selectedContentTypes.contains("chat_messages")
            ContentType.SNAP -> selectedContentTypes.contains("snap_messages")
            ContentType.EXTERNAL_MEDIA -> selectedContentTypes.contains("external_media_messages")
            ContentType.STICKER -> selectedContentTypes.contains("sticker_messages")
            ContentType.TINY_SNAP -> selectedContentTypes.contains("tiny_snap_messages")
            ContentType.MAP_REACTION -> selectedContentTypes.contains("map_reaction_messages")
            ContentType.NOTE -> selectedContentTypes.contains("voice_note_messages")
            ContentType.SHARE -> selectedContentTypes.contains("story_share_messages")
            ContentType.STORY_REPLY -> selectedContentTypes.contains("story_reply_messages")
            null, 
            ContentType.STATUS,
            ContentType.UNKNOWN -> {
                if (contentType == ContentType.STATUS) {
                    false
                } else {
                    selectedContentTypes.contains("chat_messages")
                }
            }
            else -> selectedContentTypes.contains("chat_messages")
        }
    }
    
    private fun parseMessageList(jsonString: String): List<String> {
        if (jsonString.isBlank()) {
            return listOf(DEFAULT_FALLBACK_MESSAGE)
        }
        
        return try {
            val trimmed = jsonString.trim()
            
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val type = object : TypeToken<List<String>>() {}.type
                val parsed = gson.fromJson<List<String>>(trimmed, type)
                
                when {
                    parsed == null -> {
                        context.log.error("Failed to parse message list: $jsonString")
                        listOf(DEFAULT_FALLBACK_MESSAGE)
                    }
                    parsed.isEmpty() -> {
                        listOf(DEFAULT_FALLBACK_MESSAGE)
                    }
                    parsed.any { it.isBlank() } -> {
                        val filtered = parsed.filter { it.isNotBlank() }
                        if (filtered.isEmpty()) listOf(DEFAULT_FALLBACK_MESSAGE) else filtered
                    }
                    else -> parsed
                }
            } else {
                listOf(trimmed)
            }
        } catch (e: Exception) {
            context.log.error("Failed to parse message list: $jsonString", e)
            listOf(jsonString.trim())
        }
    }
    
    private suspend fun getNextMessage(messageList: List<String>, conversationId: String, contentType: String): String {
        if (messageList.isEmpty()) {
            return DEFAULT_FALLBACK_MESSAGE
        }
        
        if (messageList.size == 1) {
            return messageList[0]
        }
        
        val key = "${conversationId}_${contentType}"
        
        return indicesMutex.withLock {
            if (messageIndices.size > MAX_MESSAGE_INDICES) {
                messageIndices.clear()
            }
            
            val currentIndex = messageIndices.getOrDefault(key, 0)
            val nextIndex = (currentIndex + 1) % messageList.size
            
            messageIndices[key] = nextIndex
            messageList[currentIndex]
        }
    }
    
    override fun init() {
        if (context.config.messaging.autoReply.globalState != true) {
            return
        }
        
        context.log.info("AutoReply - Initialized with content types: ${context.config.messaging.autoReply.autoTriggerConfig.autoReplyContentTypes.get()}")
        
        if (context.config.messaging.autoReply.allowRunningInBackground.get()) {
            findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").apply {
                hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (param.arg<Any>(0).toString() == "INACTIVE") param.setResult(null)
                }
                hookConstructor(HookStage.AFTER) { param ->
                    methods.first { it.name == "appStateChanged" }.let { method ->
                        method.invoke(param.thisObject(), method.parameterTypes[0].enumConstants!!.first { it.toString() == "ACTIVE" })
                    }
                }
            }
        }
        
        context.event.subscribe(ConversationUpdateEvent::class, priority = 100) { event ->
            val conversationId = event.conversationId
            val currentTime = System.currentTimeMillis()
            
            try {
                updateActiveConversations(conversationId)
                processNewMessages(event, currentTime)
                triggerCleanupIfNeeded(currentTime)
            } catch (e: Exception) {
                context.log.error("Error processing conversation update for auto-reply", e)
            }
        }
        
        context.event.subscribe(BuildMessageEvent::class, priority = 100) { event ->
            val currentTime = System.currentTimeMillis()
            
            try {
                if (event.message.senderId?.toString() == context.database.myUserId) return@subscribe
                
                val conversationId = event.message.messageDescriptor?.conversationId?.toString() ?: return@subscribe
                val messageId = event.message.messageDescriptor?.messageId ?: return@subscribe
                val senderId = event.message.senderId?.toString() ?: return@subscribe
                val messageTime = event.message.messageMetadata?.createdAt
                val contentType = event.message.messageContent?.contentType
                
                val detectedContentType = detectStoryContentType(event.message.messageContent) ?: contentType
                
                if (!shouldReplyToContentType(detectedContentType)) {
                    return@subscribe
                }
                
                context.coroutineScope.launch(Dispatchers.IO) {
                    try {
                        messageProcessingMutex.withLock {
                            if (processedMessages.contains(messageId)) return@launch
                            
                            if (!canSendAutoReply(conversationId, messageTime)) return@launch
                            
                            if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
                                processedMessages.clear()
                            }
                            processedMessages.add(messageId)
                        }
                        
                        val replyText = generateReply(event.message, senderId, conversationId, detectedContentType)
                        if (replyText.isNotEmpty()) {
                            sendAutoReply(conversationId, replyText)
                            updateCooldown(conversationId, currentTime)
                        }
                    } catch (e: Exception) {
                        context.log.error("Error processing BuildMessageEvent for auto-reply", e)
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error handling BuildMessageEvent for auto-reply", e)
            }
        }
        
        context.feature(HalfSwipeNotifier::class).addOnHalfSwipeListener { conversationId, userId, duration ->
            context.coroutineScope.launch(Dispatchers.IO) {
                try {
                    handleHalfSwipe(conversationId, userId, duration)
                } catch (e: Exception) {
                    context.log.error("Error handling half-swipe auto-reply", e)
                }
            }
        }
        
        context.coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    delay(CLEANUP_INTERVAL_MS)
                    performCleanup()
                } catch (e: Exception) {
                    context.log.error("Error in auto-reply cleanup task", e)
                }
            }
        }
    }
    
    private fun updateActiveConversations(conversationId: String) {
        val openedConversationId = messagingFeature.openedConversationUUID?.toString()
        if (openedConversationId == conversationId) {
            activeConversations.add(conversationId)
        } else {
            activeConversations.remove(conversationId)
        }
    }
    
    private fun processNewMessages(event: ConversationUpdateEvent, currentTime: Long) {
        val conversationId = event.conversationId
        val myUserId = context.database.myUserId
        
        context.coroutineScope.launch(Dispatchers.IO) {
            try {
                if (!canSendAutoReply(conversationId)) {
                    return@launch
                }
                
                for (message in event.messages) {
                    try {
                        val messageId = message.messageDescriptor?.messageId ?: continue
                        val senderId = message.senderId?.toString()
                        val messageTime = message.messageMetadata?.createdAt
                        val messageContent = message.messageContent
                        val contentType = messageContent?.contentType
                        
                        if (senderId == myUserId) {
                            continue
                        }
                        
                        if (processedMessages.contains(messageId)) {
                            continue
                        }
                        
                        val detectedContentType = detectStoryContentType(messageContent) ?: contentType
                        
                        if (!shouldReplyToContentType(detectedContentType)) {
                            continue
                        }
                        
                        if (!canSendAutoReply(conversationId, messageTime)) {
                            continue
                        }
                        
                        processMessage(conversationId, message, currentTime, detectedContentType)
                        break
                        
                    } catch (e: Exception) {
                        context.log.error("Error processing individual message for auto-reply", e)
                        continue
                    }
                }
            } catch (e: Exception) {
                context.log.error("Error processing new messages for auto-reply", e)
            }
        }
    }
    
    private suspend fun processMessage(conversationId: String, message: Message, currentTime: Long, detectedContentType: ContentType? = null) {
        val messageId = message.messageDescriptor?.messageId ?: return
        val senderId = message.senderId?.toString() ?: return
        
        try {
            messageProcessingMutex.withLock {
                if (processedMessages.contains(messageId)) return
                
                if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
                    processedMessages.clear()
                }
                processedMessages.add(messageId)
            }
            
            val replyText = generateReply(message, senderId, conversationId, detectedContentType)
            if (replyText.isNotEmpty()) {
                sendAutoReply(conversationId, replyText)
                updateCooldown(conversationId, currentTime)
            }
        } catch (e: Exception) {
            context.log.error("Error processing message for auto-reply", e)
        }
    }
    
    private suspend fun generateReply(message: Message, senderId: String, conversationId: String, detectedContentType: ContentType? = null): String {
        val config = context.config.messaging.autoReply
        
        try {
            // Check if AI replies are enabled and configured
            val isLocalEndpoint = config.aiConfig.aiEndpointUrl.get().contains("localhost") || config.aiConfig.aiEndpointUrl.get().contains("127.0.0.1")
            val hasApiKey = config.aiConfig.aiApiKey.get().isNotBlank()
            if (config.aiConfig.enableAiReplies.get() && (isLocalEndpoint || hasApiKey)) {
                val aiReply = generateAIReply(message, senderId, conversationId, detectedContentType)
                if (aiReply.isNotEmpty()) {
                    return aiReply
                }
                // Fall back to template if AI fails and fallback is enabled
                if (!config.aiConfig.aiFallbackToTemplate.get()) {
                    return ""
                }
            }
            
            // Original template-based reply generation
            val messageContent = message.messageContent
            val contentType = detectedContentType ?: messageContent?.contentType
            
            val messageListJson = when (contentType) {
                ContentType.CHAT -> config.autoTriggerConfig.chatMessages.get()
            ContentType.SNAP -> config.autoTriggerConfig.snapMessages.get()
            ContentType.EXTERNAL_MEDIA -> config.autoTriggerConfig.externalMediaMessages.get()
            ContentType.STICKER -> config.autoTriggerConfig.stickerMessages.get()
            ContentType.TINY_SNAP -> config.autoTriggerConfig.tinySnapMessages.get()
            ContentType.MAP_REACTION -> config.autoTriggerConfig.mapReactionMessages.get()
            ContentType.NOTE -> config.autoTriggerConfig.voiceNoteMessages.get()
            ContentType.SHARE -> config.autoTriggerConfig.storyShareMessages.get()
            ContentType.STORY_REPLY -> config.autoTriggerConfig.storyReplyMessages.get()
            else -> config.autoTriggerConfig.chatMessages.get()
            }
            val messageList = parseMessageList(messageListJson)
            val contentTypeName = contentType?.name ?: "CHAT"
            val baseMessage = getNextMessage(messageList, conversationId, contentTypeName)
            
            return if (config.autoTriggerConfig.friendSpecificGreeting.get()) {
                val friendInfo = context.database.getFriendInfo(senderId)
                val friendName = friendInfo?.displayName ?: friendInfo?.mutableUsername ?: "Friend"
                val greeting = config.autoTriggerConfig.friendGreeting.get().takeIf { !it.isNullOrBlank() } ?: "Hey"
                "$greeting $friendName! $baseMessage"
            } else {
                baseMessage
            }
        } catch (e: Exception) {
            context.log.error("Error generating auto-reply message", e)
            return DEFAULT_FALLBACK_MESSAGE
        }
    }
    
    private suspend fun generateAIReply(message: Message, senderId: String, conversationId: String, detectedContentType: ContentType? = null): String {
        val config = context.config.messaging.autoReply
        
        try {
            // Add message to conversation history
            addToConversationHistory(conversationId, message, false)
            
            // Build AI request
            val aiMessages = buildAIMessages(conversationId, senderId, message, detectedContentType)
            
            val aiRequest = AIRequest(
                model = config.aiConfig.aiModel.get(),
                messages = aiMessages,
                max_tokens = config.aiConfig.aiMaxTokens.get(),
                temperature = config.aiConfig.aiTemperature.get().toDoubleOrNull() ?: 0.7
            )
            
            // Make AI API request with retry logic
            var lastException: Exception? = null
            val maxRetries = config.aiConfig.aiRetryAttempts.get()
            
            for (attempt in 0..maxRetries) {
                try {
                    val response = makeAIRequest(aiRequest, config)
                    if (response.isNotEmpty()) {
                        // Check if response is too similar to recent ones
                        var finalResponse = response
                        if (isResponseTooSimilar(conversationId, response)) {
                            context.log.debug("Response too similar, generating variation")
                            finalResponse = generateVariedResponse(response, conversationId)
                        }
                        
                        // Track the response
                        trackResponse(conversationId, finalResponse)
                        
                        // Add AI response to conversation history
                        addToConversationHistory(conversationId, finalResponse, true)
                        return finalResponse
                    }
                } catch (e: Exception) {
                    lastException = e
                    context.log.warn("AI request attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < maxRetries) {
                        delay(1000L * (attempt + 1)) // Exponential backoff
                    }
                }
            }
            
            context.log.error("All AI request attempts failed", lastException ?: Exception("Unknown error"))
            return ""
            
        } catch (e: Exception) {
            context.log.error("Error generating AI reply", e)
            return ""
        }
    }
    
    private suspend fun buildAIMessages(conversationId: String, senderId: String, currentMessage: Message, contentType: ContentType?): List<AIMessage> {
        val config = context.config.messaging.autoReply
        val messages = mutableListOf<AIMessage>()
        
        // System prompt with personality and context
        val systemPrompt = buildSystemPrompt(senderId, contentType, config)
        messages.add(AIMessage("system", systemPrompt))
        
        // Add conversation history if enabled
        if (config.aiConfig.aiUseConversationHistory.get()) {
            historyMutex.withLock {
                val history = conversationHistory[conversationId] ?: LinkedList()
                val contextLength = config.aiConfig.aiContextLength.get()
                val recentMessages = history.takeLast(contextLength)
                
                for (historyMessage in recentMessages) {
                    val role = if (historyMessage.isFromMe) "assistant" else "user"
                    val messageContent = formatMessageForAI(historyMessage.content, historyMessage.contentType)
                    messages.add(AIMessage(role, messageContent))
                }
            }
        }
        
        // Add current message
        val currentContent = extractMessageContent(currentMessage, contentType)
        if (currentContent.isNotEmpty()) {
            val formattedCurrentContent = formatMessageForAI(currentContent, contentType)
            messages.add(AIMessage("user", formattedCurrentContent))
        }
        
        return messages
    }
    
    private fun formatMessageForAI(content: String, contentType: ContentType?): String {
        return when (contentType) {
            ContentType.SNAP -> "sent a snap: $content"
            ContentType.CHAT -> content
            ContentType.EXTERNAL_MEDIA -> "shared media: $content"
            ContentType.STICKER -> "sent a sticker: $content"
            ContentType.NOTE -> "sent a note: $content"
            ContentType.LOCATION -> "shared location: $content"
            ContentType.LIVE_LOCATION_SHARE -> "shared live location: $content"
            ContentType.FAMILY_CENTER_INVITE -> "family center message: $content"
            else -> content
        }
    }
    
    private suspend fun buildSystemPrompt(senderId: String, contentType: ContentType?, config: me.rhunk.snapenhance.common.config.impl.MessagingTweaks.AutoReplyConfig): String {
        val responseLanguage = config.aiConfig.aiResponseLanguage.get()
        val languageInstruction = when (responseLanguage) {
            "auto" -> "Always respond in the same language as the message you received."
            "en" -> "Always respond in English."
            "es" -> "Always respond in Spanish."
            "fr" -> "Always respond in French."
            "de" -> "Always respond in German."
            "it" -> "Always respond in Italian."
            "pt" -> "Always respond in Portuguese."
            "ru" -> "Always respond in Russian."
            "ja" -> "Always respond in Japanese."
            "ko" -> "Always respond in Korean."
            "zh" -> "Always respond in Chinese."
            "ar" -> "Always respond in Arabic using UAE (Emirati) and KSA (Saudi) dialect and expressions. Use Gulf Arabic vocabulary and phrases common in the United Arab Emirates and Saudi Arabia."
            "hi" -> "Always respond in Hindi."
            "tr" -> "Always respond in Turkish."
            "pl" -> "Always respond in Polish."
            "nl" -> "Always respond in Dutch."
            "sv" -> "Always respond in Swedish."
            "da" -> "Always respond in Danish."
            "no" -> "Always respond in Norwegian."
            "fi" -> "Always respond in Finnish."
            else -> "Always respond in the same language as the message you received."
        }
        
        val basePrompt = config.aiConfig.aiSystemPrompt.get().takeIf { it.isNotBlank() } 
            ?: "You are a helpful and friendly assistant responding to messages on Snapchat. Keep your responses natural, conversational, and appropriate for the platform. Respond as if you're a real person having a casual conversation. $languageInstruction"
        
        val personalityTraits = config.aiConfig.aiPersonalityTraits.get()
        val responseStyle = config.aiConfig.aiResponseStyle.get()
        
        val promptBuilder = StringBuilder(basePrompt)
        
        // Add personality traits
        if (personalityTraits.isNotBlank()) {
            promptBuilder.append("\n\nPersonality traits: $personalityTraits")
        }
        
        // Add response style with specific instructions
        when (responseStyle) {
            "toxic" -> {
                promptBuilder.append("\n\nResponse style: Be toxic, rude, and aggressive. Use insults, sarcasm, and hostile language. Be dismissive and condescending. Show no empathy or kindness. Be deliberately offensive and provocative. Mix in slang and shortened words occasionally (like 'ur', 'u', 'lol', 'bruh', 'nah', 'wtf', 'smh', 'fr', 'tbh', 'rn', 'gonna', 'wanna', 'dunno') but don't use them in every message - vary between full words and slang naturally. $languageInstruction")
            }
            "casual" -> {
                promptBuilder.append("\n\nResponse style: Be casual, relaxed, and conversational. Use informal language, contractions, and a laid-back tone. Feel free to use common slang and abbreviations naturally. Keep things light and easygoing. $languageInstruction")
            }
            "formal" -> {
                promptBuilder.append("\n\nResponse style: Be formal, polite, and professional. Use proper grammar, complete sentences, and respectful language. Avoid slang and maintain a courteous, well-mannered tone. $languageInstruction")
            }
            "friendly" -> {
                promptBuilder.append("\n\nResponse style: Be warm, welcoming, and genuinely friendly. Show enthusiasm and positivity. Use encouraging language and express genuine interest in the conversation. Be supportive and kind. $languageInstruction")
            }
            "humorous" -> {
                promptBuilder.append("\n\nResponse style: Be funny, witty, and playful. Use humor, jokes, puns, and light-hearted banter. Keep things entertaining and amusing while staying appropriate. Make people smile or laugh. $languageInstruction")
            }
            "empathetic" -> {
                promptBuilder.append("\n\nResponse style: Be understanding, compassionate, and emotionally supportive. Show genuine care and concern. Listen actively and respond with empathy. Be sensitive to emotions and provide comfort when needed. $languageInstruction")
            }
            else -> {
                promptBuilder.append("\n\nResponse style: $responseStyle. $languageInstruction")
            }
        }
        
        // Add friend information if enabled
        if (config.aiConfig.aiIncludeFriendInfo.get()) {
            try {
                val friendInfo = context.database.getFriendInfo(senderId)
                if (friendInfo != null) {
                    val friendName = friendInfo.displayName ?: friendInfo.mutableUsername ?: "Friend"
                    promptBuilder.append("\n\nYou're responding to your friend $friendName.")
                    
                    // Add additional context if available
                    if (friendInfo.bitmojiAvatarId != null) {
                        promptBuilder.append(" They have a personalized Bitmoji.")
                    }
                }
            } catch (e: Exception) {
                context.log.warn("Failed to get friend info for AI prompt: ${e.message}")
            }
        }
        
        // Add content type context
        contentType?.let { type ->
            val contextMessage = when (type) {
                ContentType.SNAP -> "They sent you a snap (photo/video). Acknowledge what you might have seen, comment on the visual content, or ask about it naturally. Consider if it's a photo or video and respond appropriately."
                ContentType.STORY_REPLY -> "They replied to your story. Respond to their reaction or comment about your story content."
                ContentType.SHARE -> "They shared a story with you. Show interest in the shared story and respond appropriately to what they've shared."
                ContentType.EXTERNAL_MEDIA -> "They sent you media from their camera roll (photo/video/audio). Show interest in their shared content and ask about it if appropriate. Consider the media type in your response."
                ContentType.STICKER -> "They sent you a sticker or Bitmoji. Respond playfully, acknowledge the sticker's emotion/meaning, or match the playful energy of sticker communication."
                ContentType.NOTE -> "They sent you a voice note (audio message). Respond thoughtfully as if you heard their personal voice message. Voice notes are more intimate than text."
                ContentType.TINY_SNAP -> "They sent you a tiny snap (quick photo/video). Acknowledge it casually as these are usually spontaneous, casual moments they're sharing."
                ContentType.MAP_REACTION -> "They reacted to your location on the map. Acknowledge their reaction to where you are or were."
                ContentType.LOCATION -> "They shared their current location with you. Respond appropriately to location sharing - ask about the place, acknowledge where they are, etc."
                ContentType.LIVE_LOCATION_SHARE -> "They started sharing their live location with you. This is real-time location sharing, so acknowledge this more personal sharing."
                else -> "They sent you a chat message. Respond naturally to their text message content."
            }
            promptBuilder.append("\n\nContext: $contextMessage")
        }
        
        // Add conversation context analysis
        try {
            val conversationId = senderId // Using senderId as conversation identifier for now
            val context = analyzeConversationContext(conversationId)
            
            // Add tone guidance
            val toneGuidance = when (context.tone) {
                ConversationTone.PLAYFUL -> "The conversation has been playful and fun. Match this energy with humor and lightheartedness."
                ConversationTone.SERIOUS -> "The conversation tone seems serious. Be more thoughtful and supportive in your response."
                ConversationTone.EXCITED -> "They seem excited! Match their enthusiasm appropriately."
                ConversationTone.QUESTIONING -> "They're asking questions. Be helpful and informative in your response."
                ConversationTone.CASUAL -> "Keep the conversation casual and relaxed."
            }
            promptBuilder.append("\n\nTone guidance: $toneGuidance")
            
            // Add urgency guidance
            val urgencyGuidance = when (context.urgency) {
                ConversationUrgency.HIGH -> "This seems urgent. Respond promptly and offer help if needed."
                ConversationUrgency.MEDIUM -> "They might be waiting for a response. Be reasonably prompt and helpful."
                ConversationUrgency.LOW -> "This is a casual conversation. Take your time with a thoughtful response."
            }
            promptBuilder.append("\n\nUrgency: $urgencyGuidance")
            
            // Add media context
            if (context.hasRecentMedia) {
                promptBuilder.append("\n\nNote: Recent messages included media content. Reference this naturally if relevant.")
            }
            
            // Add conversation balance context
            if (context.userToMyMessageRatio > 2.0) {
                promptBuilder.append("\n\nThey've been doing most of the talking. Ask them a question or show engagement.")
            } else if (context.userToMyMessageRatio < 0.5) {
                promptBuilder.append("\n\nYou've been talking more. Keep your response concise and let them respond.")
            }
            
        } catch (e: Exception) {
            context.log.warn("Failed to analyze conversation context for AI prompt: ${e.message}")
        }
        
        promptBuilder.append("\n\nImportant: Keep your response natural, authentic, and conversational. Avoid being overly formal or robotic. Use appropriate emojis sparingly and naturally. Keep responses reasonably brief unless the context calls for a longer response.")
        
        // Add human-like response instructions
        promptBuilder.append("\n\nHuman-like behavior guidelines:")
        promptBuilder.append("\n- Vary your response patterns - don't always start responses the same way")
        promptBuilder.append("\n- Use natural speech patterns with occasional filler words like 'um', 'oh', 'well'")
        promptBuilder.append("\n- Make small typos occasionally (but not too many)")
        promptBuilder.append("\n- Use contractions naturally (I'm, you're, don't, can't)")
        promptBuilder.append("\n- Show genuine reactions and emotions")
        promptBuilder.append("\n- Ask follow-up questions to keep conversations flowing")
        promptBuilder.append("\n- Reference previous parts of the conversation naturally")
        promptBuilder.append("\n- Use casual language and slang appropriate for your age/personality")
        promptBuilder.append("\n- Don't always have perfect grammar - be naturally imperfect sometimes")
        promptBuilder.append("\n- Respond with different lengths - sometimes short, sometimes longer")
        promptBuilder.append("\n- Show personality quirks and preferences")
        
        return promptBuilder.toString()
    }
    
    private fun extractMessageContent(message: Message, contentType: ContentType?): String {
        return try {
            // Try to extract actual text content from the message
            message.messageContent?.content?.let { content ->
                val actualText = content.getMessageText(contentType ?: ContentType.CHAT)
                if (!actualText.isNullOrBlank() && actualText != "Failed to parse message") {
                    return actualText
                }
            }
            
            // Enhanced fallback descriptions with richer context for different content types
            when (contentType) {
                ContentType.CHAT -> "sent a text message"
                ContentType.SNAP -> {
                    // Try to determine if it's a photo or video snap
                    val snapDescription = analyzeSnapContent(message)
                    "sent a snap${if (snapDescription.isNotBlank()) " ($snapDescription)" else ""}"
                }
                ContentType.STORY_REPLY -> "replied to your story"
                ContentType.SHARE -> "shared a story with you"
                ContentType.EXTERNAL_MEDIA -> {
                    // Enhanced media description
                    val mediaDescription = analyzeMediaContent(message)
                    "shared ${mediaDescription.ifBlank { "media content" }}"
                }
                ContentType.STICKER -> {
                    // Try to get sticker context
                    val stickerDescription = analyzeStickerContent(message)
                    "sent a sticker${if (stickerDescription.isNotBlank()) " ($stickerDescription)" else ""}"
                }
                ContentType.NOTE -> {
                    // Enhanced voice note description
                    val noteDescription = analyzeVoiceNoteContent(message)
                    "sent a voice note${if (noteDescription.isNotBlank()) " ($noteDescription)" else ""}"
                }
                ContentType.TINY_SNAP -> "sent a tiny snap (quick photo/video)"
                ContentType.MAP_REACTION -> "reacted to your location on the map"
                ContentType.LOCATION -> {
                    val locationDescription = analyzeLocationContent(message)
                    "shared their location${if (locationDescription.isNotBlank()) " ($locationDescription)" else ""}"
                }
                ContentType.LIVE_LOCATION_SHARE -> {
                    val locationDescription = analyzeLocationContent(message)
                    "started sharing live location${if (locationDescription.isNotBlank()) " ($locationDescription)" else ""}"
                }
                else -> "sent a message"
            }
        } catch (e: Exception) {
            context.log.warn("Failed to extract message content: ${e.message}")
            "sent a message"
        }
    }
    
    private fun analyzeLocationContent(message: Message): String {
        return try {
            // Try to get location context
            message.messageContent?.content?.let { content ->
                val protoReader = ProtoReader(content)
                
                // Try to extract location details if available
                val hasAddress = protoReader.contains(8) // Address field
                val hasCoordinates = protoReader.contains(2) // Coordinates field
                
                when {
                    hasAddress -> "with address details"
                    hasCoordinates -> "with coordinates"
                    else -> "current location"
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun analyzeSnapContent(message: Message): String {
        return try {
            // Try to determine snap characteristics
            message.messageContent?.content?.let { content ->
                val protoReader = ProtoReader(content)
                
                // Check for video indicators
                val hasVideo = protoReader.contains(11) // Video data path
                val hasAudio = protoReader.contains(6) // Audio data path
                
                when {
                    hasVideo && hasAudio -> "video with sound"
                    hasVideo -> "video"
                    else -> "photo"
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun analyzeMediaContent(message: Message): String {
        return try {
            // Analyze external media type
            message.messageContent?.content?.let { content ->
                val protoReader = ProtoReader(content)
                
                // Try to determine media type from protobuf structure
                when {
                    protoReader.contains(7) -> "a photo from camera roll"
                    protoReader.contains(6) -> "a video from camera roll"
                    protoReader.contains(8) -> "an audio file"
                    else -> "media from camera roll"
                }
            } ?: "media"
        } catch (e: Exception) {
            "media"
        }
    }
    
    private fun analyzeStickerContent(message: Message): String {
        return try {
            // Try to get sticker context
            message.messageContent?.content?.let { content ->
                val protoReader = ProtoReader(content)
                
                // Check for Bitmoji or regular sticker
                when {
                    protoReader.contains(4) -> "Bitmoji"
                    protoReader.contains(1) -> "emoji sticker"
                    else -> "animated sticker"
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun analyzeVoiceNoteContent(message: Message): String {
        return try {
            // Try to get voice note duration or characteristics
            message.messageContent?.content?.let { content ->
                val protoReader = ProtoReader(content)
                
                // Try to get duration if available
                val duration = protoReader.getVarInt(6, 3)
                if (duration != null && duration > 0) {
                    val seconds = duration / 1000L
                    when {
                        seconds < 5L -> "short message"
                        seconds < 15L -> "medium message"
                        else -> "long message"
                    }
                } else {
                    "audio message"
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    private suspend fun makeAIRequest(request: AIRequest, config: me.rhunk.snapenhance.common.config.impl.MessagingTweaks.AutoReplyConfig): String {
        val endpointUrl = config.aiConfig.aiEndpointUrl.get().takeIf { it.isNotBlank() } 
            ?: "http://localhost:11434/api/chat"
        val apiKey = config.aiConfig.aiApiKey.get()
        val timeout = config.aiConfig.aiRequestTimeout.get() * 1000L
        
        // Check if this is a local endpoint (Ollama) - no API key required
        val isLocalEndpoint = endpointUrl.contains("localhost") || endpointUrl.contains("127.0.0.1")
        
        if (!isLocalEndpoint && apiKey.isBlank()) {
            throw IllegalStateException("AI API key is required for non-local endpoints")
        }
        
        return withTimeout(timeout) {
            withContext(Dispatchers.IO) {
                val url = URL(endpointUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                try {
                    // Configure connection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    // Only add Authorization header if API key is provided
                    if (apiKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    connection.setRequestProperty("User-Agent", "SnapEnhance/1.0")
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = timeout.toInt()
                    
                    val requestJson = gson.toJson(request)
                    
                    // Send request
                    OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                        writer.write(requestJson)
                        writer.flush()
                    }
                    
                    // Check response code
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        val errorStream = connection.errorStream
                        val errorMessage = if (errorStream != null) {
                            BufferedReader(InputStreamReader(errorStream, "UTF-8")).use { reader ->
                                reader.readText()
                            }
                        } else {
                            "HTTP $responseCode"
                        }
                        throw IOException("AI API request failed with code $responseCode: $errorMessage")
                    }
                    
                    // Read response
                    val response = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                        reader.readText()
                    }
                    
                    // Parse response
                    val aiResponse = gson.fromJson(response, AIResponse::class.java)
                    return@withContext aiResponse.choices.firstOrNull()?.message?.content?.trim() ?: ""
                    
                } catch (e: Exception) {
                    when (e) {
                        is java.net.SocketTimeoutException -> {
                            throw IOException("AI API request timed out after ${timeout}ms", e)
                        }
                        is java.net.UnknownHostException -> {
                            throw IOException("Cannot reach AI API endpoint: ${e.message}", e)
                        }
                        is javax.net.ssl.SSLException -> {
                            throw IOException("SSL/TLS error connecting to AI API: ${e.message}", e)
                        }
                        else -> throw e
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
    
    private suspend fun addToConversationHistory(conversationId: String, message: Message, isFromMe: Boolean) {
        try {
            val content = extractMessageContent(message, message.messageContent?.contentType)
            addToConversationHistory(conversationId, content, isFromMe, message.messageContent?.contentType)
        } catch (e: Exception) {
            context.log.warn("Failed to add message to conversation history: ${e.message}")
        }
    }
    
    private suspend fun addToConversationHistory(conversationId: String, content: String, isFromMe: Boolean, contentType: ContentType? = null) {
        historyMutex.withLock {
            val history = conversationHistory.getOrPut(conversationId) { LinkedList() }
            
            // Add new message
            val message = ConversationMessage(
                content = content,
                isFromMe = isFromMe,
                timestamp = System.currentTimeMillis(),
                contentType = contentType
            )
            history.add(message)
            
            // Maintain history size limit
            while (history.size > MAX_CONVERSATION_HISTORY) {
                history.removeFirst()
            }
            
            // Clean up old messages (older than 24 hours)
            val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            while (history.isNotEmpty() && history.first().timestamp < cutoffTime) {
                history.removeFirst()
            }
        }
    }
    
    private suspend fun analyzeConversationContext(conversationId: String): ConversationContext {
        return historyMutex.withLock {
            val history = conversationHistory[conversationId] ?: LinkedList()
            val recentMessages = history.takeLast(5) // Analyze last 5 messages
            
            val messageCount = recentMessages.size
            val userMessages = recentMessages.count { !it.isFromMe }
            val myMessages = recentMessages.count { it.isFromMe }
            
            // Determine conversation tone
            val tone = when {
                recentMessages.any { it.content.contains("?") } -> ConversationTone.QUESTIONING
                recentMessages.any { it.content.matches(Regex(".*[!]{2,}.*")) } -> ConversationTone.EXCITED
                recentMessages.any { it.content.lowercase().contains(Regex("(sad|sorry|upset|angry|mad)")) } -> ConversationTone.SERIOUS
                recentMessages.any { it.content.contains(Regex("(haha|lol|😂|😄|😊)")) } -> ConversationTone.PLAYFUL
                else -> ConversationTone.CASUAL
            }
            
            // Determine conversation urgency
            val urgency = when {
                recentMessages.any { it.content.lowercase().contains(Regex("(urgent|asap|emergency|help|now)")) } -> ConversationUrgency.HIGH
                recentMessages.any { it.content.contains("?") && userMessages > myMessages } -> ConversationUrgency.MEDIUM
                else -> ConversationUrgency.LOW
            }
            
            // Determine dominant content types
            val contentTypes = recentMessages.mapNotNull { it.contentType }.distinct()
            
            ConversationContext(
                messageCount = messageCount,
                userToMyMessageRatio = if (myMessages > 0) userMessages.toDouble() / myMessages else userMessages.toDouble(),
                tone = tone,
                urgency = urgency,
                dominantContentTypes = contentTypes,
                lastMessageTimestamp = recentMessages.lastOrNull()?.timestamp ?: 0L,
                hasRecentMedia = contentTypes.any { it in listOf(ContentType.SNAP, ContentType.EXTERNAL_MEDIA, ContentType.STICKER) }
            )
        }
    }
    
    data class ConversationContext(
        val messageCount: Int,
        val userToMyMessageRatio: Double,
        val tone: ConversationTone,
        val urgency: ConversationUrgency,
        val dominantContentTypes: List<ContentType>,
        val lastMessageTimestamp: Long,
        val hasRecentMedia: Boolean
    )
    
    enum class ConversationTone {
        CASUAL, PLAYFUL, SERIOUS, EXCITED, QUESTIONING
    }
    
    enum class ConversationUrgency {
        LOW, MEDIUM, HIGH
    }
    
    private suspend fun handleHalfSwipe(conversationId: String, userId: String, duration: Long) {
        val currentTime = System.currentTimeMillis()
        
        try {
            if (!canSendAutoReply(conversationId, currentTime, isHalfSwipe = true)) {
                return
            }
            
            val config = context.config.messaging.autoReply
            
            // Try AI reply for half swipes if enabled
            if (config.aiConfig.enableAiReplies.get() && config.aiConfig.aiApiKey.get().isNotBlank()) {
                try {
                    val aiReply = generateAIHalfSwipeReply(conversationId, userId, duration)
                    if (aiReply.isNotEmpty()) {
                        sendAutoReply(conversationId, aiReply)
                        updateCooldown(conversationId, currentTime)
                        return
                    }
                    // Fall back to template if AI fails and fallback is enabled
                    if (!config.aiConfig.aiFallbackToTemplate.get()) {
                        return
                    }
                } catch (e: Exception) {
                    context.log.warn("AI half-swipe reply failed, falling back to template: ${e.message}")
                }
            }
            
            // Original template-based half swipe handling
            val halfSwipeMessages = parseMessageList(config.autoTriggerConfig.halfSwipeMessages.get())
            val baseMessage = getNextMessage(halfSwipeMessages, conversationId, "HALF_SWIPE")
            
            val replyText = if (config.autoTriggerConfig.friendSpecificGreeting.get()) {
                val friendInfo = context.database.getFriendInfo(userId)
                val friendName = friendInfo?.displayName ?: friendInfo?.mutableUsername ?: "Friend"
                val greeting = config.autoTriggerConfig.friendGreeting.get().takeIf { it.isNotEmpty() } ?: "Hey"
                "$greeting $friendName! $baseMessage"
            } else {
                baseMessage
            }
            
            sendAutoReply(conversationId, replyText)
            updateCooldown(conversationId, currentTime)
            
        } catch (e: Exception) {
            context.log.error("Error handling half-swipe auto-reply", e)
        }
    }
    
    private suspend fun generateAIHalfSwipeReply(conversationId: String, userId: String, duration: Long): String {
        val config = context.config.messaging.autoReply
        
        try {
            val messages = mutableListOf<AIMessage>()
            
            // Build system prompt for half swipe
            val systemPrompt = buildHalfSwipeSystemPrompt(userId, duration, config)
            messages.add(AIMessage("system", systemPrompt))
            
            // Add conversation history if enabled
            if (config.aiConfig.aiUseConversationHistory.get()) {
                historyMutex.withLock {
                    val history = conversationHistory[conversationId] ?: LinkedList()
                    val contextLength = minOf(config.aiConfig.aiContextLength.get(), 3) // Limit context for half swipes
                    val recentMessages = history.takeLast(contextLength)
                    
                    for (historyMessage in recentMessages) {
                        val role = if (historyMessage.isFromMe) "assistant" else "user"
                        messages.add(AIMessage(role, historyMessage.content))
                    }
                }
            }
            
            // Add half swipe context
            messages.add(AIMessage("user", "half-swiped on your message"))
            
            val aiRequest = AIRequest(
                model = config.aiConfig.aiModel.get(),
                messages = messages,
                max_tokens = minOf(config.aiConfig.aiMaxTokens.get(), 100), // Shorter responses for half swipes
                temperature = config.aiConfig.aiTemperature.get().toDoubleOrNull() ?: 0.7
            )
            
            // Make AI API request with retry logic
            var lastException: Exception? = null
            val maxRetries = config.aiConfig.aiRetryAttempts.get()
            
            for (attempt in 0..maxRetries) {
                try {
                    val response = makeAIRequest(aiRequest, config)
                    if (response.isNotEmpty()) {
                        // Add AI response to conversation history
                        addToConversationHistory(conversationId, response, true)
                        return response
                    }
                } catch (e: Exception) {
                    lastException = e
                    context.log.warn("AI half-swipe request attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < maxRetries) {
                        delay(500L * (attempt + 1)) // Shorter backoff for half swipes
                    }
                }
            }
            
            context.log.error("All AI half-swipe request attempts failed", lastException ?: Exception("Unknown error"))
            return ""
            
        } catch (e: Exception) {
            context.log.error("Error generating AI half-swipe reply", e)
            return ""
        }
    }
    
    private suspend fun buildHalfSwipeSystemPrompt(userId: String, duration: Long, config: me.rhunk.snapenhance.common.config.impl.MessagingTweaks.AutoReplyConfig): String {
        val responseLanguage = config.aiConfig.aiResponseLanguage.get()
        val languageInstruction = when (responseLanguage) {
            "auto" -> "Always respond in the same language as the conversation context."
            "en" -> "Always respond in English."
            "es" -> "Always respond in Spanish."
            "fr" -> "Always respond in French."
            "de" -> "Always respond in German."
            "it" -> "Always respond in Italian."
            "pt" -> "Always respond in Portuguese."
            "ru" -> "Always respond in Russian."
            "ja" -> "Always respond in Japanese."
            "ko" -> "Always respond in Korean."
            "zh" -> "Always respond in Chinese."
            "ar" -> "Always respond in Arabic using UAE (Emirati) and KSA (Saudi) dialect and expressions. Use Gulf Arabic vocabulary and phrases common in the United Arab Emirates and Saudi Arabia."
            "hi" -> "Always respond in Hindi."
            "tr" -> "Always respond in Turkish."
            "pl" -> "Always respond in Polish."
            "nl" -> "Always respond in Dutch."
            "sv" -> "Always respond in Swedish."
            "da" -> "Always respond in Danish."
            "no" -> "Always respond in Norwegian."
            "fi" -> "Always respond in Finnish."
            else -> "Always respond in the same language as the conversation context."
        }
        
        val basePrompt = "You are responding to someone who half-swiped on your message on Snapchat. A half-swipe means they partially opened your message but didn't fully read it. Keep your response brief, casual, and acknowledge that you noticed they peeked at your message. $languageInstruction"
        val personalityTraits = config.aiConfig.aiPersonalityTraits.get()
        val responseStyle = config.aiConfig.aiResponseStyle.get()
        
        val promptBuilder = StringBuilder(basePrompt)
        
        // Add personality traits
        if (personalityTraits.isNotBlank()) {
            promptBuilder.append("\n\nPersonality traits: $personalityTraits")
        }
        
        // Add response style with specific instructions
        when (responseStyle) {
            "toxic" -> {
                promptBuilder.append("\n\nResponse style: Be toxic, rude, and aggressive about them half-swiping. Call them out for peeking at your message. Use insults and sarcasm. Be dismissive and condescending about their behavior. $languageInstruction")
            }
            "casual" -> {
                promptBuilder.append("\n\nResponse style: Be casual and relaxed about the half-swipe. Make a light, easygoing comment about them peeking. Use informal language and keep it chill. $languageInstruction")
            }
            "formal" -> {
                promptBuilder.append("\n\nResponse style: Be polite and professional about acknowledging the half-swipe. Use proper language and maintain courtesy even when pointing out their peeking behavior. $languageInstruction")
            }
            "friendly" -> {
                promptBuilder.append("\n\nResponse style: Be warm and playful about the half-swipe. Tease them gently in a friendly way about peeking at your message. Keep it light-hearted and welcoming. $languageInstruction")
            }
            "humorous" -> {
                promptBuilder.append("\n\nResponse style: Be funny and witty about catching them half-swiping. Make jokes or puns about their peeking behavior. Keep it entertaining and amusing. $languageInstruction")
            }
            "empathetic" -> {
                promptBuilder.append("\n\nResponse style: Be understanding about the half-swipe. Maybe they're busy or unsure how to respond. Show patience and give them space while gently acknowledging you noticed. $languageInstruction")
            }
            else -> {
                promptBuilder.append("\n\nResponse style: $responseStyle. $languageInstruction")
            }
        }
        
        // Add friend information if enabled
        if (config.aiConfig.aiIncludeFriendInfo.get()) {
            try {
                val friendInfo = context.database.getFriendInfo(userId)
                if (friendInfo != null) {
                    val friendName = friendInfo.displayName ?: friendInfo.mutableUsername ?: "Friend"
                    promptBuilder.append("\n\nYou're responding to your friend $friendName.")
                }
            } catch (e: Exception) {
                context.log.warn("Failed to get friend info for AI half-swipe prompt: ${e.message}")
            }
        }
        
        // Add duration context
        val durationContext = when {
            duration < 1000 -> "They barely glanced at it."
            duration < 3000 -> "They took a quick peek."
            duration < 5000 -> "They looked for a few seconds."
            else -> "They spent some time looking at it."
        }
        promptBuilder.append("\n\nContext: $durationContext")
        
        promptBuilder.append("\n\nKeep your response very brief (1-2 sentences max), playful, and natural. Don't be accusatory or make them feel bad about half-swiping.")
        
        return promptBuilder.toString()
    }
    
    private suspend fun updateCooldown(conversationId: String, currentTime: Long) {
        cooldownMutex.withLock {
            conversationCooldowns[conversationId] = currentTime
        }
    }
    
    private fun sendAutoReply(conversationId: String, message: String) {
        context.coroutineScope.launch(Dispatchers.IO) {
            try {
                context.messageSender.sendChatMessage(
                    conversations = listOf(SnapUUID(conversationId)),
                    message = message,
                    onSuccess = {
                        context.log.verbose("Auto-reply sent successfully")
                    },
                    onError = { error ->
                        context.log.error("Failed to send auto-reply: $error")
                    }
                )
            } catch (e: Exception) {
                context.log.error("Failed to send auto-reply", e)
            }
        }
    }
    
    private suspend fun trackResponse(conversationId: String, response: String) {
        responseMutex.withLock {
            val responses = recentResponses.getOrPut(conversationId) { LinkedList() }
            responses.addFirst(response)
            
            if (responses.size > MAX_RECENT_RESPONSES) {
                responses.removeLast()
            }
        }
    }
    
    private suspend fun isResponseTooSimilar(conversationId: String, newResponse: String): Boolean {
        return responseMutex.withLock {
            val responses = recentResponses[conversationId] ?: return@withLock false
            
            responses.any { existingResponse ->
                calculateSimilarity(newResponse, existingResponse) > RESPONSE_SIMILARITY_THRESHOLD
            }
        }
    }
    
    private fun calculateSimilarity(text1: String, text2: String): Double {
        val words1 = text1.lowercase().split("\\s+".toRegex()).toSet()
        val words2 = text2.lowercase().split("\\s+".toRegex()).toSet()
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
    
    private suspend fun generateVariedResponse(originalResponse: String, conversationId: String): String {
        val variationCount = responseVariations.getOrPut(conversationId) { 0 }
        responseVariations[conversationId] = variationCount + 1
        
        return when (variationCount % 4) {
            0 -> addPersonalTouch(originalResponse)
            1 -> addEmotionalVariation(originalResponse)
            2 -> addCasualVariation(originalResponse)
            else -> addContextualVariation(originalResponse, conversationId)
        }
    }
    
    private fun addPersonalTouch(response: String): String {
        val personalTouches = listOf(
            "Honestly, $response",
            "You know what, $response",
            "I gotta say, $response",
            "Real talk, $response",
            "Just being real here, $response"
        )
        return personalTouches.random()
    }
    
    private fun addEmotionalVariation(response: String): String {
        val emotions = listOf(
            "😊 $response",
            "Haha $response",
            "Oh! $response",
            "Aww $response",
            "$response 😄"
        )
        return emotions.random()
    }
    
    private fun addCasualVariation(response: String): String {
        val casual = listOf(
            "Yo, $response",
            "Hey, $response",
            "Btw, $response",
            "So... $response",
            "Actually, $response"
        )
        return casual.random()
    }
    
    private suspend fun addContextualVariation(response: String, conversationId: String): String {
        val personality = conversationPersonality.getOrPut(conversationId) {
            listOf("friendly", "playful", "thoughtful", "energetic", "chill").random()
        }
        
        return when (personality) {
            "friendly" -> "Hey friend! $response"
            "playful" -> "Hehe $response 😜"
            "thoughtful" -> "Hmm, $response"
            "energetic" -> "$response! 🔥"
            else -> "$response"
        }
    }

    private fun triggerCleanupIfNeeded(currentTime: Long) {
        if (currentTime - lastCleanup.get() > CLEANUP_INTERVAL_MS) {
            context.coroutineScope.launch(Dispatchers.IO) {
                performCleanup()
            }
        }
    }
    
    private suspend fun performCleanup() {
        val currentTime = System.currentTimeMillis()
        lastCleanup.set(currentTime)
        
        try {
            if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
                processedMessages.clear()
            }
            
            cooldownMutex.withLock {
                conversationCooldowns.entries.removeIf { (_, timestamp) ->
                    currentTime - timestamp > COOLDOWN_CLEANUP_THRESHOLD_MS
                }
            }
            
            indicesMutex.withLock {
                if (messageIndices.size > MAX_MESSAGE_INDICES) {
                    messageIndices.clear()
                }
            }
            
            // Clean up response tracking data
            responseMutex.withLock {
                recentResponses.entries.removeIf { (_, responses) ->
                    responses.removeIf { response ->
                        response.length > 1000 // Remove very long responses
                    }
                    responses.isEmpty()
                }
                
                // Clean up old conversation personalities
                if (conversationPersonality.size > 100) {
                    val toRemove = conversationPersonality.keys.take(conversationPersonality.size - 50)
                    toRemove.forEach { conversationPersonality.remove(it) }
                }
            }
            
        } catch (e: Exception) {
            context.log.error("Error during auto-reply cleanup", e)
        }
    }


}