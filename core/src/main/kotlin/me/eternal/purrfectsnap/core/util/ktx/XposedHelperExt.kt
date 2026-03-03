package me.eternal.purrfectsnap.core.util.ktx

private fun findDeclaredFieldRecursive(type: Class<*>, fieldName: String): java.lang.reflect.Field? {
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        current.declaredFields.firstOrNull { it.name == fieldName }?.let { return it }
        current = current.superclass
    }
    return null
}

fun Any.getObjectField(fieldName: String): Any? {
    val field = findDeclaredFieldRecursive(this::class.java, fieldName)
        ?: throw NoSuchFieldException("${this::class.java.name}#$fieldName")
    field.isAccessible = true
    return field.get(this)
}

fun Any.setEnumField(fieldName: String, value: String) {
    this::class.java.getDeclaredField(fieldName)
        .type.enumConstants?.firstOrNull { it.toString() == value }?.let { enum ->
        setObjectField(fieldName, enum)
    }
}

fun Any.setObjectField(fieldName: String, value: Any?) {
    val field = findDeclaredFieldRecursive(this::class.java, fieldName)
        ?: throw NoSuchFieldException("${this::class.java.name}#$fieldName")
    field.isAccessible = true
    field.set(this, value)
}

fun Any.getObjectFieldOrNull(fieldName: String): Any? {
    return try {
        getObjectField(fieldName)
    } catch (t: Throwable) {
        null
    }
}

