package me.eternal.purrfectsnap.core.wrapper.impl

import me.eternal.purrfectsnap.core.util.ktx.findFieldNamesByType
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.util.ktx.setObjectField
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper

class ScSize(
    obj: Any?
) : AbstractWrapper(obj) {
    private val intFields by lazy {
        instanceNonNull().findFieldNamesByType(Int::class.javaPrimitiveType ?: Int::class.java)
    }
    private val firstFieldName by lazy { intFields.first() }
    private val secondFieldName by lazy { intFields.last() }

    var first: Int get() = instanceNonNull().getObjectField(firstFieldName) as Int
        set(value) {
            instanceNonNull().setObjectField(firstFieldName, value)
        }

    var second: Int get() = instanceNonNull().getObjectField(secondFieldName) as Int
        set(value) {
            instanceNonNull().setObjectField(secondFieldName, value)
        }
}
