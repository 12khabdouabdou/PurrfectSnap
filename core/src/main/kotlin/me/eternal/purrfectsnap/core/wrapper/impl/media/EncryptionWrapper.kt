package me.eternal.purrfectsnap.core.wrapper.impl.media

import android.util.Base64
import android.util.Log
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

// Cipher mode enum

enum class SnapCipherMode {
    CBC,
    CTR
}

// Encryption Wrapper

class EncryptionWrapper(
    instance: Any? = null,
    private val mode: SnapCipherMode = SnapCipherMode.CBC
) : AbstractWrapper(instance) {

    // Manual key injection (story fallback)

    private var manualKey: ByteArray? = null
    private var manualIv: ByteArray? = null

    constructor(
        key: ByteArray,
        iv: ByteArray,
        mode: SnapCipherMode = SnapCipherMode.CBC
    ) : this(null, mode) {
        manualKey = key
        manualIv = iv
    }

    // Key + IV extraction

    val keySpec: ByteArray by lazy {
        manualKey ?: searchByteArrayField(32)[instance] as ByteArray
    }

    val ivKeyParameterSpec: ByteArray by lazy {
        manualIv ?: searchByteArrayField(16)[instance] as ByteArray
    }

    private fun searchByteArrayField(length: Int): Field {
        return instanceNonNull()::class.java.declaredFields.first { field ->
            try {
                field.isAccessible = true

                if (!field.type.isArray ||
                    field.type.componentType != Byte::class.javaPrimitiveType
                ) return@first false

                val value = field.get(instanceNonNull()) as? ByteArray
                value?.size == length

            } catch (_: Exception) {
                false
            }
        }
    }

    // Cipher builders

    /** Chat media */
    private fun buildCBCCipherPKCS5(mode: Int): Cipher {
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                mode,
                SecretKeySpec(keySpec, "AES"),
                IvParameterSpec(ivKeyParameterSpec)
            )
        }
    }

    /** Story images */
    private fun buildCBCCipherNoPadding(mode: Int): Cipher {
        return Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(
                mode,
                SecretKeySpec(keySpec, "AES"),
                IvParameterSpec(ivKeyParameterSpec)
            )
        }
    }

    /** Videos + DASH */
    private fun buildCTRCipher(mode: Int): Cipher {
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                mode,
                SecretKeySpec(keySpec, "AES"),
                IvParameterSpec(ivKeyParameterSpec)
            )
        }
    }

    // Generic decrypt (auto detect)

    fun decrypt(data: ByteArray): ByteArray {

        Log.d(
            "PurrfectSnap",
            "Decrypt → keySize=${keySpec.size} ivSize=${ivKeyParameterSpec.size}"
        )

        return try {
            buildCBCCipherPKCS5(Cipher.DECRYPT_MODE).doFinal(data)

        } catch (cbcError: Exception) {

            Log.d("PurrfectSnap", "PKCS5 failed → trying CTR")

            buildCTRCipher(Cipher.DECRYPT_MODE).doFinal(data)
        }
    }

    // Image decrypt (story + chat safe)

    fun decryptImage(data: ByteArray): ByteArray {

        Log.d(
            "PurrfectSnap",
            "Image Decrypt → keySize=${keySpec.size} ivSize=${ivKeyParameterSpec.size}"
        )

        return try {
            // Story images
            buildCBCCipherNoPadding(Cipher.DECRYPT_MODE).doFinal(data)

        } catch (noPadError: Exception) {

            Log.d("PurrfectSnap", "CBC NoPadding failed → trying PKCS5")

            try {
                // Chat images
                buildCBCCipherPKCS5(Cipher.DECRYPT_MODE).doFinal(data)

            } catch (pkcsError: Exception) {

                Log.d("PurrfectSnap", "PKCS5 failed → trying CTR")

                // Edge fallback
                buildCTRCipher(Cipher.DECRYPT_MODE).doFinal(data)
            }
        }
    }

    // Stream decrypt

    fun decrypt(input: InputStream): InputStream {
        val cipher = try {
            buildCBCCipherPKCS5(Cipher.DECRYPT_MODE)
        } catch (_: Exception) {
            buildCTRCipher(Cipher.DECRYPT_MODE)
        }

        return CipherInputStream(input, cipher)
    }

    fun decryptImage(input: InputStream): InputStream {

        val cipher = try {
            buildCBCCipherNoPadding(Cipher.DECRYPT_MODE)
        } catch (_: Exception) {
            buildCBCCipherPKCS5(Cipher.DECRYPT_MODE)
        }

        return CipherInputStream(input, cipher)
    }

    fun decrypt(output: OutputStream): OutputStream {

        val cipher = try {
            buildCBCCipherPKCS5(Cipher.DECRYPT_MODE)
        } catch (_: Exception) {
            buildCTRCipher(Cipher.DECRYPT_MODE)
        }

        return CipherOutputStream(output, cipher)
    }
}

// KeyPair Extensions

/** Standard Base64 */
fun EncryptionWrapper.toKeyPair(): MediaEncryptionKeyPair {
    return MediaEncryptionKeyPair(
        key = Base64.encodeToString(this.keySpec, Base64.NO_WRAP),
        iv = Base64.encodeToString(this.ivKeyParameterSpec, Base64.NO_WRAP),
        urlSafe = false
    )
}

/** URL Safe (story media) */
fun EncryptionWrapper.toKeyPairUrlSafe(): MediaEncryptionKeyPair {
    return MediaEncryptionKeyPair(
        key = Base64.encodeToString(
            this.keySpec,
            Base64.URL_SAFE or Base64.NO_WRAP
        ),
        iv = Base64.encodeToString(
            this.ivKeyParameterSpec,
            Base64.URL_SAFE or Base64.NO_WRAP
        ),
        urlSafe = true
    )
}
