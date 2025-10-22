package me.rhunk.snapenhance.download

import android.content.Context
import com.tonyodev.fetch2.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.RemoteSideContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object BypassDownloader {
    private const val CHALLENGE_ENDPOINT_URL = "https://bypass-endpoint.purrfectsnap-bypass.workers.dev"
    private const val BYPASS_SHA256 = "E82887493771D61838CC51F48E78177EBDBB5C06036E35BB384A7CD1AE40407E"

    enum class DownloadState {
        IDLE,
        DOWNLOADING,
        DECRYPTING,
        COMPLETED,
        FAILED
    }

    val downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadProgress = MutableStateFlow(0f)
    val errorMessage = MutableStateFlow<String?>(null)

    private fun verifyChecksum(file: File): Boolean {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hexHash = digest.digest().joinToString("") { "%02x".format(it) }.uppercase()
            return hexHash == BYPASS_SHA256
        } catch (e: Exception) {
            return false
        }
    }

    private fun createConnection(urlString: String): HttpsURLConnection {
        return URL(urlString).openConnection() as HttpsURLConnection
    }

    private fun decryptBypass(context: Context, encryptedFile: File, decryptedFile: File) {
        val password = me.rhunk.snapenhance.SharedContextHolder.remote(context).native.getSecretKey().toCharArray()

        encryptedFile.inputStream().use { fis ->
            val saltHeader = ByteArray(8)
            fis.read(saltHeader)
            val salt = ByteArray(8)
            fis.read(salt)

            val keySpec = PBEKeySpec(password, salt, 100000, 256 + 128)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = factory.generateSecret(keySpec)

            val keyBytes = key.encoded.copyOfRange(0, 32)
            val ivBytes = key.encoded.copyOfRange(32, 48)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivParameterSpec = IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)

            decryptedFile.outputStream().use { fos ->
                val cipherInputStream = CipherInputStream(fis, cipher)
                cipherInputStream.copyTo(fos)
            }
        }
    }

    fun download(context: Context, scope: CoroutineScope) {
        val log = me.rhunk.snapenhance.SharedContextHolder.remote(context).log
        scope.launch(Dispatchers.IO) {
            val encryptedFile = File(context.filesDir, "bypass.dex.enc")

            try {
                log.info("Starting bypass download...")
                errorMessage.value = null
                downloadState.value = DownloadState.DOWNLOADING

                log.info("Request URL: $CHALLENGE_ENDPOINT_URL")
                val apiKey = me.rhunk.snapenhance.SharedContextHolder.remote(context).native.getSecretKey()

                val connection = createConnection(CHALLENGE_ENDPOINT_URL)
                connection.setRequestProperty("X-API-Key", apiKey)
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                val responseCode = connection.responseCode
                log.info("Response Code: $responseCode")
                log.info("Response Headers: ${connection.headerFields}")

                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    connection.inputStream.use { input ->
                        encryptedFile.outputStream().use { output ->
                            val totalBytes = connection.contentLength
                            var bytesCopied = 0L
                            val buffer = ByteArray(8192)
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytesCopied += bytes
                                if (totalBytes > 0) {
                                    downloadProgress.value = (bytesCopied.toFloat() / totalBytes.toFloat())
                                }
                                bytes = input.read(buffer)
                            }
                        }
                    }
                    log.info("Download completed. File size: ${encryptedFile.length()} bytes")

                    if (verifyChecksum(encryptedFile)) {
                        log.info("Checksum verification successful.")
                        downloadState.value = DownloadState.COMPLETED
                        log.info("Bypass download successful.")
                    } else {
                        errorMessage.value = "Checksum verification failed. Expected: $BYPASS_SHA256"
                        log.error("Checksum verification failed.")
                        downloadState.value = DownloadState.FAILED
                        encryptedFile.delete()
                    }
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                    errorMessage.value = "Server returned status: $responseCode - $errorBody"
                    log.error("Server returned non-OK status: $responseCode - $errorBody")
                    downloadState.value = DownloadState.FAILED
                }
            } catch (e: CertificateException) {
                errorMessage.value = "Certificate pinning validation failed."
                log.error("Certificate pinning validation failed.", e)
                downloadState.value = DownloadState.FAILED
            } catch (e: Exception) {
                errorMessage.value = "Download error: ${e.message ?: "Unknown error"}"
                log.error("An error occurred during bypass download.", e)
                downloadState.value = DownloadState.FAILED
            } finally {
                if (downloadState.value == DownloadState.FAILED && encryptedFile.exists()) {
                    encryptedFile.delete()
                }
            }
        }
    }
}
