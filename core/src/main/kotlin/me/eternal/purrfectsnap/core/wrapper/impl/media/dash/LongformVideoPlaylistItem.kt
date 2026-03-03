package me.eternal.purrfectsnap.core.wrapper.impl.media.dash

import me.eternal.purrfectsnap.core.util.ktx.findFieldNamesByType
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper

class LongformVideoPlaylistItem(obj: Any?) : AbstractWrapper(obj) {
    private val chapterListField by lazy {
        instanceNonNull().findFieldNamesByType(List::class.java).first()
    }
    val chapters: List<SnapChapter>
        get() = (instanceNonNull().getObjectField(chapterListField) as List<*>).map { SnapChapter(it) }
}
