package me.eternal.purrfectsnap.mapper.impl

import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ext.findConstString
import me.eternal.purrfectsnap.mapper.ext.getClassName
import me.eternal.purrfectsnap.mapper.ext.searchNextFieldReference

class PlusSubscriptionMapper : AbstractClassMapper("PlusSubscription"){
    val classReference = classReference("class")
    val tierField = string("tierField")
    val statusField = string("statusField")
    val originalSubscriptionTimeMillisField = string("originalSubscriptionTimeMillisField")
    val expirationTimeMillisField = string("expirationTimeMillisField")

    init {
        mapper {
            for (clazz in classes) {
                if (clazz.directMethods.filter { it.name == "<init>" }.none {
                    it.parameterTypes.size > 3
                }) continue

                val toStringMethod = clazz.virtualMethods.firstOrNull { it.name == "toString" }?.implementation ?: continue
                if (!toStringMethod.let {
                    it.findConstString("SubscriptionInfo", contains = true) && it.findConstString("expirationTimeMillis", contains = true)
                }) continue

                classReference.set(clazz.getClassName())

                toStringMethod.apply {
                    searchNextFieldReference("tier", contains = true)?.let { tierField.set(it.name) }
                    searchNextFieldReference("status", contains = true)?.let { statusField.set(it.name) }
                    searchNextFieldReference("original", contains = true)?.let { originalSubscriptionTimeMillisField.set(it.name) }
                    searchNextFieldReference("expirationTimeMillis", contains = true)?.let { expirationTimeMillisField.set(it.name) }
                }

                return@mapper
            }
        }
    }
}