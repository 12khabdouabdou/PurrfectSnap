package me.eternal.purrfectsnap.mapper.impl

import me.eternal.purrfectsnap.mapper.ClassMapper
import java.lang.reflect.Field

class EditsMapper : ClassMapper("Edits") {
    
    var durationField: Field? = null

    override fun map() {
        // Find the Edits class by scanning for the hardcoded toString() we saw in the DQi logs
        val editsClass = classes.firstOrNull { clazz ->
            try {
                // The Edits class is massive, so we check for > 10 fields to filter out small classes,
                // and we check if it overrides toString()
                clazz.declaredFields.size > 10 && clazz.declaredMethods.any { method ->
                    method.name == "toString" && method.returnType == String::class.java
                }
            } catch (e: Exception) {
                false
            }
        }

        if (editsClass != null) {
            // The duration is stored as a Long. We grab the Long fields.
            val longFields = editsClass.declaredFields.filter { 
                it.type == Long::class.javaPrimitiveType || it.type == Long::class.javaObjectType 
            }
            
            if (longFields.isNotEmpty()) {
                // Usually, timerOrDurationMs is the very first Long field in the Edits class
                durationField = longFields.first() 
                durationField?.isAccessible = true
                
                // Register it so our SendOverride feature can request it
                putClass("EditsClass", editsClass)
            }
        }
    }
}
