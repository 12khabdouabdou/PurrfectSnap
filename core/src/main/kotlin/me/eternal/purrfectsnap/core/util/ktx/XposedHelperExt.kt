package me.eternal.purrfectsnap.core.util.ktx

fun Any.getObjectField(fieldName: String): Any? {
    return KavaRefFieldBridge.getField(this, fieldName)
}

fun Any.findFieldNamesByType(type: Class<*>): List<String> {
    return KavaRefFieldBridge.findFieldNamesByType(this, type).toList()
}

fun Any.allFieldNames(): List<String> {
    return KavaRefFieldBridge.getAllFieldNames(this).toList()
}

fun Class<*>.getStaticObjectField(fieldName: String): Any? {
    return KavaRefFieldBridge.getStaticField(this, fieldName)
}

fun Class<*>.findStaticObjectFieldByType(type: Class<*>): Any? {
    return KavaRefFieldBridge.findStaticFieldByType(this, type)
}

fun Any.setEnumField(fieldName: String, value: String) {
    val enumType = KavaRefFieldBridge.getFieldType(this, fieldName)
    enumType.enumConstants?.firstOrNull { it.toString() == value }?.let { enum ->
        setObjectField(fieldName, enum)
    }
}

fun Any.setObjectField(fieldName: String, value: Any?) {
    KavaRefFieldBridge.setField(this, fieldName, value)
}

fun Any.getObjectFieldOrNull(fieldName: String): Any? {
    return try {
        getObjectField(fieldName)
    } catch (t: Throwable) {
        null
    }
}

