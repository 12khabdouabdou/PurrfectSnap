package me.eternal.purrfectsnap.core.wrapper.impl

import me.eternal.purrfectsnap.core.PurrfectSnap
import me.eternal.purrfectsnap.core.util.ktx.getObjectField
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import java.nio.ByteBuffer
import java.util.UUID

fun String.toSnapUUID() = SnapUUID(this)
fun ByteArray.toSnapUUID() = SnapUUID(this)

fun UUID.toBytes(): ByteArray =
    ByteBuffer.allocate(16).let {
        it.putLong(this.mostSignificantBits)
        it.putLong(this.leastSignificantBits)
        it.array()
    }

class SnapUUID(
    private val obj: Any?
) : AbstractWrapper(obj) {
    private val uuidBytes by lazy {
        when {
            obj is String -> {
                UUID.fromString(obj).toBytes()
            }
            obj is ByteArray -> {
                assert(obj.size == 16)
                obj
            }
            obj is UUID -> obj.toBytes()
            PurrfectSnap.classCache.snapUUID.isInstance(obj) -> {
                obj?.getObjectField("mId") as ByteArray
            }
            PurrfectSnap.classCache.snapShimsUUID?.isInstance(obj) == true -> {
                val any = obj as Any
                runCatching { any.javaClass.getMethod("getId").invoke(any) as ByteArray }.getOrElse {
                    any.getObjectField("mId") as ByteArray
                }
            }
            else -> ByteArray(16)
        }
    }

    private val uuidString by lazy { ByteBuffer.wrap(uuidBytes).run { UUID(long, long) }.toString() }

    override var instance: Any?
        set(_) {}
        get() = PurrfectSnap.classCache.snapUUID.getConstructor(ByteArray::class.java).newInstance(uuidBytes)

    override fun toString(): String {
        return uuidString
    }

    fun toBytes() = uuidBytes

    override fun equals(other: Any?): Boolean {
        return other is SnapUUID && other.uuidBytes.contentEquals(this.uuidBytes)
    }

    override fun hashCode(): Int {
        return uuidBytes.contentHashCode()
    }
}

