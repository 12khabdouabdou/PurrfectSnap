package me.eternal.purrfectsnap.core.wrapper.impl

import me.eternal.purrfectsnap.common.data.ContentType
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import org.mozilla.javascript.annotations.JSGetter
import org.mozilla.javascript.annotations.JSSetter

class MessageContent(obj: Any?) : AbstractWrapper(obj) {
    @get:JSGetter @set:JSSetter
    var content by field<ByteArray>("mContent")
    @get:JSGetter @set:JSSetter
    var quotedMessage by field("mQuotedMessage") { QuotedMessage(it) }
    @get:JSGetter @set:JSSetter
    var contentType by enum("mContentType", ContentType.UNKNOWN)
    @get:JSGetter @set:JSSetter
    var localMediaReferences by field<ArrayList<*>>("mLocalMediaReferences")
}