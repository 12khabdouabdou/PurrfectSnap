package me.eternal.purrfectsnap.core.wrapper.impl

import me.eternal.purrfectsnap.common.data.QuotedMessageContentStatus
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import org.mozilla.javascript.annotations.JSGetter
import org.mozilla.javascript.annotations.JSSetter

class QuotedMessage(obj: Any?) : AbstractWrapper(obj) {
    @get:JSGetter @set:JSSetter
    var content by field("mContent") { QuotedMessageContent(it) }
    @get:JSGetter
    val status by enum("mStatus", QuotedMessageContentStatus.UNKNOWN)
}