package me.eternal.purrfectsnap.core.wrapper.impl.media

import me.eternal.purrfectsnap.common.data.download.MediaEncryptionKeyPair
import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.ExperimentalEncodingApi
import android.util.Base64

enum class SnapCipherMode {
    CBC,
    CTR
}

class EncryptionWrapper(
    instance: Any?,
    private val mode: SnapCipherMode = SnapCipherMode.CBC
) : AbstractWrapper(instance) {

    fun decrypt(data: ByteArray?): ByteArray {
        return newCipher(Cipher.DECRYPT_MODE).doFinal(data)
    }

    fun decrypt(inputStream: InputStream?): InputStream {
        return CipherInputStream(inputStream, newCipher(Cipher.DECRYPT_MODE))
    }

    fun decrypt(outputStream: OutputStream?): OutputStream {
        return CipherOutputStream(outputStream, newCipher(Cipher.DECRYPT_MODE))
    }

    fun newCipher(modeInt: Int): Cipher {
        val cipher = cipher
        cipher.init(
            modeInt,
            SecretKeySpec(keySpec, "AES"),
            IvParameterSpec(ivKeyParameterSpec)
        )
        return cipher
    }

    /**
     * Dynamic cipher selection
     */
    private val cipher: Cipher
        get() = when (mode) {
            SnapCipherMode.CBC ->
                Cipher.getInstance("AES/CBC/PKCS5Padding")

            SnapCipherMode.CTR ->
                Cipher.getInstance("AES/CTR/NoPadding")
        }

    val keySpec: ByteArray by lazy {
        searchByteArrayField(32)[instance] as ByteArray
    }

    val ivKeyParameterSpec: ByteArray by lazy {
        searchByteArrayField(16)[instance] as ByteArray
    }

    private fun searchByteArrayField(arrayLength: Int): Field {
        return instanceNonNull()::class.java.fields.first { f ->
            try {
                if (!f.type.isArray ||
                    f.type.componentType != Byte::class.javaPrimitiveType
                ) return@first false

                (f.get(instanceNonNull()) as ByteArray).size == arrayLength
            } catch (_: Exception) {
                false
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun EncryptionWrapper.toKeyPair(): MediaEncryptionKeyPair {
    return MediaEncryptionKeyPair(
        key = android.util.Base64.encodeToString(this.keySpec, android.util.Base64.NO_WRAP),
        iv  = android.util.Base64.encodeToString(this.ivKeyParameterSpec, android.util.Base64.NO_WRAP),
        urlSafe = true
    )
}


