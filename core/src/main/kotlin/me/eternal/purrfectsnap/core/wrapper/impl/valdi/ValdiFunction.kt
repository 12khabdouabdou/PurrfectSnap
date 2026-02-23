package me.eternal.purrfectsnap.core.wrapper.impl.valdi

import me.eternal.purrfectsnap.core.PurrfectSnap
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper

class ValdiFunction(obj: Any): AbstractWrapper(obj) {
    private val performMethod by lazy {
        instanceNonNull().javaClass.getMethod(
            "perform",
            PurrfectSnap.classCache.valdiMarshaller
        )
    }

    fun perform(valdiMarshaller: ValdiMarshaller): Boolean {
        return performMethod.invoke(instanceNonNull(), valdiMarshaller.instanceNonNull()) as Boolean
    }
}
