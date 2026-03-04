package me.eternal.purrfectsnap.core.wrapper.impl.media.dash

import me.eternal.purrfectsnap.core.util.ktx.findFieldNamesByType
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper

class SnapChapter (obj: Any?) : AbstractWrapper(obj) {
    private val longFields by lazy {
        instanceNonNull().findFieldNamesByType(Long::class.javaPrimitiveType ?: Long::class.java)
    }
    val snapId by lazy {
        instanceNonNull().getObjectField(longFields.first()) as Long
    }
    val startTimeMs by lazy {
        instanceNonNull().getObjectField(longFields[1]) as Long
    }
}
