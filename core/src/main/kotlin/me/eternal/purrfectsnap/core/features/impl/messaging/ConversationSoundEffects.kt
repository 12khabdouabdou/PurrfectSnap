package me.eternal.purrfectsnap.core.features.impl.messaging

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import me.eternal.purrfectsnap.core.event.events.impl.ConversationUpdateEvent
import me.eternal.purrfectsnap.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfectsnap.core.features.Feature

class ConversationSoundEffects : Feature("Conversation Sound Effects") {
    private val seenIncomingMessageIds = LinkedHashSet<Long>()
    private val maxTrackedMessages = 512

    private data class ToneStep(
        val tone: Int,
        val durationMs: Int,
        val pauseAfterMs: Long = 0L
    )

    private data class ToneSpec(
        val sendPattern: List<ToneStep>,
        val receivePattern: List<ToneStep>
    )

    private fun currentConversationId() = context.feature(Messaging::class).openedConversationUUID?.toString()

    private fun styleSpec(): ToneSpec {
        return when (context.config.messaging.conversationSoundEffectsStyle.get()) {
            "telegram" -> ToneSpec(
                sendPattern = listOf(
                    ToneStep(ToneGenerator.TONE_PROP_BEEP, 35),
                    ToneStep(ToneGenerator.TONE_PROP_BEEP2, 45, 25)
                ),
                receivePattern = listOf(
                    ToneStep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 70),
                    ToneStep(ToneGenerator.TONE_PROP_BEEP2, 35, 20)
                )
            )
            "whatsapp" -> ToneSpec(
                sendPattern = listOf(
                    ToneStep(ToneGenerator.TONE_PROP_ACK, 55)
                ),
                receivePattern = listOf(
                    ToneStep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 85),
                    ToneStep(ToneGenerator.TONE_PROP_ACK, 35, 15)
                )
            )
            "subtle" -> ToneSpec(
                sendPattern = listOf(
                    ToneStep(ToneGenerator.TONE_PROP_PROMPT, 22)
                ),
                receivePattern = listOf(
                    ToneStep(ToneGenerator.TONE_PROP_ACK, 28)
                )
            )
            else -> ToneSpec(
                sendPattern = listOf(
                    ToneStep(ToneGenerator.TONE_PROP_PROMPT, 40),
                    ToneStep(ToneGenerator.TONE_PROP_BEEP, 28, 18)
                ),
                receivePattern = listOf(
                    ToneStep(ToneGenerator.TONE_PROP_ACK, 55),
                    ToneStep(ToneGenerator.TONE_PROP_BEEP2, 40, 22)
                )
            )
        }
    }

    private fun playPattern(pattern: List<ToneStep>) {
        if (context.isMainActivityPaused) return
        context.executeAsync {
            var toneGenerator: ToneGenerator? = null
            runCatching {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
                pattern.forEach { step ->
                    toneGenerator?.startTone(step.tone, step.durationMs)
                    if (step.pauseAfterMs > 0) delay(step.pauseAfterMs)
                }
            }.also {
                runCatching { toneGenerator?.release() }
            }
        }
    }

    private fun markSeen(messageId: Long): Boolean {
        synchronized(seenIncomingMessageIds) {
            val added = seenIncomingMessageIds.add(messageId)
            while (seenIncomingMessageIds.size > maxTrackedMessages) {
                seenIncomingMessageIds.remove(seenIncomingMessageIds.first())
            }
            return added
        }
    }

    override fun init() {
        if (!context.config.messaging.conversationSoundEffects.get()) return

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            val activeConversationId = currentConversationId() ?: return@subscribe
            if (event.destinations.conversations?.none { it.toString() == activeConversationId } != false) return@subscribe

            event.addCallbackResult("onSuccess") {
                val spec = styleSpec()
                playPattern(spec.sendPattern)
            }
        }

        context.event.subscribe(ConversationUpdateEvent::class) { event ->
            val activeConversationId = currentConversationId() ?: return@subscribe
            if (event.conversationId != activeConversationId) return@subscribe

            val myUserId = context.database.myUserId ?: return@subscribe
            val spec = styleSpec()

            event.messages
                .asSequence()
                .filter { it.senderId?.toString() != myUserId }
                .mapNotNull { it.messageDescriptor?.messageId }
                .filter { markSeen(it) }
                .firstOrNull()
                ?.let {
                    playPattern(spec.receivePattern)
                }
        }
    }
}
