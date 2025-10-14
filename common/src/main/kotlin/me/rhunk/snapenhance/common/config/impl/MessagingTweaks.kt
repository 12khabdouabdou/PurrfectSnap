package me.rhunk.snapenhance.common.config.impl

import me.rhunk.snapenhance.common.config.ConfigContainer
import me.rhunk.snapenhance.common.config.FeatureNotice
import me.rhunk.snapenhance.common.config.PropertyValue
import me.rhunk.snapenhance.common.data.NotificationType
import me.rhunk.snapenhance.common.util.PURGE_DISABLED_KEY
import me.rhunk.snapenhance.common.util.PURGE_TRANSLATION_KEY
import me.rhunk.snapenhance.common.util.PURGE_VALUES

class MessagingTweaks : ConfigContainer() {
    companion object {
        const val DELETED_MESSAGE_COLOR = 0x6Eb71c1c;
    }

    inner class HalfSwipeNotifierConfig : ConfigContainer(hasGlobalState = true) {
        val minDuration: PropertyValue<Int> = integer("min_duration", defaultValue = 0) {
            inputCheck = { it.toIntOrNull()?.coerceAtLeast(0) != null && maxDuration.get() >= it.toInt() }
        }
        val maxDuration: PropertyValue<Int> = integer("max_duration", defaultValue = 20) {
            inputCheck = { it.toIntOrNull()?.coerceAtLeast(0) != null && minDuration.get() <= it.toInt() }
        }
    }

    inner class MessageLoggerConfig : ConfigContainer(hasGlobalState = true) {
        val keepMyOwnMessages = boolean("keep_my_own_messages")
        val autoPurge = unique("auto_purge", *PURGE_VALUES) {
            customOptionTranslationPath = PURGE_TRANSLATION_KEY
            disabledKey = PURGE_DISABLED_KEY
        }.apply { set("3_days") }
        val messageFilter = multiple("message_filter", "CHAT",
            "SNAP",
            "NOTE",
            "EXTERNAL_MEDIA",
            "STICKER"
        ) {
            customOptionTranslationPath = "content_type"
        }
        val deletedMessageColor = color("deleted_message_color", DELETED_MESSAGE_COLOR)
    }

    class BetterNotifications: ConfigContainer() {
        val groupNotifications = boolean("group_notifications")
        val chatPreview = boolean("chat_preview")
        val mediaPreview = multiple("media_preview", "SNAP", "EXTERNAL_MEDIA", "STICKER", "SHARE", "TINY_SNAP", "MAP_REACTION") {
            customOptionTranslationPath = "content_type"
        }
        val mediaCaption = boolean("media_caption")
        val stackedMediaMessages = boolean("stacked_media_messages")
        val friendAddSource = boolean("friend_add_source")
        val replyButton = boolean("reply_button") { addNotices(FeatureNotice.UNSTABLE) }
        val smartReplies = boolean("smart_replies")
        val downloadButton = boolean("download_button")
        val markAsReadButton = boolean("mark_as_read_button") { addNotices(FeatureNotice.UNSTABLE) }
        val markAsReadAndSaveInChat = boolean("mark_as_read_and_save_in_chat") { addNotices(FeatureNotice.UNSTABLE) }
    }

    val bypassScreenshotDetection = boolean("bypass_screenshot_detection") { requireRestart() }
    val anonymousStoryViewing = boolean("anonymous_story_viewing")
    val preventStoryRewatchIndicator = boolean("prevent_story_rewatch_indicator") { requireRestart() }
    val hidePeekAPeek = boolean("hide_peek_a_peek")
    val hideBitmojiPresence = boolean("hide_bitmoji_presence")
    val unlimitedSnapViewTime = boolean("unlimited_snap_view_time")
    val autoMarkAsRead = multiple("auto_mark_as_read", "snap_reply", "conversation_read", "save_snap_in_chat") { requireRestart() }
    val markSnapAsSeenButton = boolean("mark_snap_as_seen_button") { requireRestart() }
    val skipWhenMarkingAsSeen = boolean("skip_when_marking_as_seen") { requireRestart() }
    val loopMediaPlayback = boolean("loop_media_playback") { requireRestart() }
    val disableReplayInFF = boolean("disable_replay_in_ff")
    val halfSwipeNotifier = container("half_swipe_notifier", HalfSwipeNotifierConfig()) { requireRestart()}
    val callStartConfirmation = boolean("call_start_confirmation") { requireRestart() }
    val unlimitedConversationPinning = boolean("unlimited_conversation_pinning") { requireRestart() }
    val disableSnapModeRestrictions = boolean("disable_snap_mode_restrictions") { requireRestart() }
    val autoSaveMessagesInConversations = multiple("auto_save_messages_in_conversations",
        "CHAT",
        "SNAP",
        "NOTE",
        "EXTERNAL_MEDIA",
        "STICKER"
    ) { requireRestart(); customOptionTranslationPath = "content_type" }
    val preventMessageSending = multiple("prevent_message_sending", *NotificationType.getOutgoingValues().map { it.key }.toTypedArray()) {
        customOptionTranslationPath = "features.options.notifications"
    }
    val friendMutationNotifier = multiple("friend_mutation_notifier",
        "remove_friend",
        "birthday_changes",
        "bitmoji_selfie_changes",
        "bitmoji_avatar_changes",
        "bitmoji_background_changes",
        "bitmoji_scene_changes",
    ) { requireRestart() }
    val betterNotifications = container("better_notifications", BetterNotifications()) { requireRestart() }
    val notificationBlacklist = multiple("notification_blacklist", *NotificationType.getIncomingValues().map { it.key }.toTypedArray()) {
        customOptionTranslationPath = "features.options.notifications"
    }
    val messageLogger = container("message_logger", MessageLoggerConfig()) { requireRestart() }
    val galleryMediaSendOverride = unique("gallery_media_send_override", "always_ask", "SNAP", "NOTE", "SAVEABLE_SNAP") { requireRestart() }
    val stripMediaMetadata = multiple("strip_media_metadata", "hide_caption_text", "hide_snap_filters", "hide_extras", "remove_audio_note_duration", "remove_audio_note_transcript_capability") { requireRestart() }
    val bypassMessageRetentionPolicy = boolean("bypass_message_retention_policy") { addNotices(FeatureNotice.UNSTABLE); requireRestart() }
    val bypassMessageActionRestrictions = boolean("bypass_message_action_restrictions") { requireRestart() }
    val removeGroupsLockedStatus = boolean("remove_groups_locked_status") { requireRestart() }
    val doubleTapChatAction = unique("double_tap_chat_action", "like_message", "copy_text", "delete_message", "mark_as_read", "custom_emoji_reaction") { requireRestart() }
    val doubleTapChatActionCustomEmoji = string("double_tap_chat_action_custom_emoji") {
        inputCheck = { it.length == 2 && it.toByteArray(Charsets.UTF_8).size >= 4 } }

    class AutoReplyConfig : ConfigContainer(hasGlobalState = true) {
        val cooldownSeconds = integer("cooldown_seconds", 60)
        val messageAgeThreshold = integer("message_age_threshold", 60)
        val allowRunningInBackground = boolean("allow_running_in_background")

        inner class AutoTriggerConfig: ConfigContainer() {
            val autoReplyContentTypes = multiple("auto_reply_content_types",
                "chat_messages",
                "snap_messages",
                "story_reply_messages",
                "story_share_messages",
                "external_media_messages",
                "sticker_messages",
                "tiny_snap_messages",
                "map_reaction_messages",
                "voice_note_messages",
                "half_swipes"
            ) {
                customOptionTranslationPath = "features.options.auto_reply.content_types"
            }
            val chatMessages = string("chat_messages", "{\"type\":\"list\",\"values\":[\"hi!\",\"hello\"]}")
            val snapMessages = string("snap_messages", "{\"type\":\"list\",\"values\":[\"received your snap!\"]}")
            val storyReplyMessages = string("story_reply_messages", "{\"type\":\"list\",\"values\":[\"thanks for the reply!\"]}")
            val storyShareMessages = string("story_share_messages", "{\"type\":\"list\",\"values\":[\"cool story!\"]}")
            val externalMediaMessages = string("external_media_messages", "{\"type\":\"list\",\"values\":[\"nice media!\"]}")
            val stickerMessages = string("sticker_messages", "{\"type\":\"list\",\"values\":[\"nice sticker!\"]}")
            val tinySnapMessages = string("tiny_snap_messages", "{\"type\":\"list\",\"values\":[\"tiny snap received!\"]}")
            val mapReactionMessages = string("map_reaction_messages", "{\"type\":\"list\",\"values\":[\"thanks for the reaction!\"]}")
            val voiceNoteMessages = string("voice_note_messages", "{\"type\":\"list\",\"values\":[\"got your voice note!\"]}")
            val halfSwipeMessages = string("half_swipe_messages", "{\"type\":\"list\",\"values\":[\"i see you peeking!\"]}")
            val friendSpecificGreeting = boolean("friend_specific_greeting")
            val friendGreeting = string("friend_greeting", "Hey")
        }

        inner class AIConfig: ConfigContainer() {
            val enableAiReplies = boolean("enable_ai_replies")
            val aiEndpointUrl = string("ai_endpoint_url", "http://localhost:11434/v1/chat/completions")
            val aiApiKey = string("ai_api_key", "")
            val aiModel = string("ai_model", "gpt-3.5-turbo")
            val aiSystemPrompt = string("ai_system_prompt", "You are a helpful assistant.")
            val aiMaxTokens = integer("ai_max_tokens", 150)
            val aiTemperature = float("ai_temperature", 0.7f)
            val aiUseConversationHistory = boolean("ai_use_conversation_history")
            val aiContextLength = integer("ai_context_length", 10)
            val aiIncludeFriendInfo = boolean("ai_include_friend_info")
            val aiResponseLanguage = string("ai_response_language", "auto")
            val aiPersonalityTraits = string("ai_personality_traits", "")
            val aiResponseStyle = string("ai_response_style", "casual")
            val aiRequestTimeout = integer("ai_request_timeout", 15)
            val aiRetryAttempts = integer("ai_retry_attempts", 2)
            val aiFallbackToTemplate = boolean("ai_fallback_to_template")
        }

        val autoTriggerConfig = container("auto_trigger_config", AutoTriggerConfig())
        val aiConfig = container("ai_config", AIConfig())
    }
    val autoReply = container("auto_reply", AutoReplyConfig()) { requireRestart() }
}