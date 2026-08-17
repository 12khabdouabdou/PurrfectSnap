package me.eternal.purrfect.core.event.events.impl

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import me.eternal.purrfect.common.database.impl.ConversationMessage
import me.eternal.purrfect.core.event.Event

class BindViewEvent(
    val prevModel: Any,
    val nextModel: Any?,
    var view: View
): Event() {
    val databaseMessage by lazy {
        var message: ConversationMessage? = null
        chatMessage { _, messageId ->
            message = context.database.getConversationMessageFromId(messageId.toLong())
        }
        message
    }

    inline fun chatMessage(block: (conversationId: String, messageId: String) -> Unit) {
        val modelToString = prevModel.toString()
        if (!modelToString.contains("ViewModel") || !modelToString.contains("messageId=")) return
        if (view !is LinearLayout) {
            val container = view as? ViewGroup ?: return
            if (container.childCount <= 0) return
            view = container.getChildAt(0)
        }
        modelToString.substringAfter("messageId=").substringBefore(",").substringBefore(" ").split(":").apply {
            if (size != 3) return
            block(this[0], this[2])
        }
    }

    inline fun friendFeedItem(block: (conversationId: String) -> Unit) {
        val modelToString = prevModel.toString()
        if (!modelToString.startsWith("FriendFeedItemViewModel")) return
        val conversationId = modelToString.substringAfter("conversationId: ").substringBefore("\n")
        block(conversationId)
    }
}