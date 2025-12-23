package me.eternal.purrfectsnap.nativelib

import android.annotation.SuppressLint
import android.util.Log
import kotlin.math.absoluteValue
import kotlin.random.Random

class NativeLib {
    var nativeUnaryCallCallback: (NativeRequestData) -> Unit = {}
    var signatureCache: String? = null

    companion object {
        var initialized = false
            private set
        private var libraryLoaded = false

        fun tryEnsureLibraryLoaded(): Boolean {
            if (libraryLoaded) return true

            val candidates = linkedSetOf(
                BuildConfig.NATIVE_NAME,
                // Some repackagers restore the original Cargo output name.
                "purrfectsnap",
            ).filter { it.isNotBlank() }

            var lastError: Throwable? = null
            for (name in candidates) {
                val ok = runCatching {
                    System.loadLibrary(name)
                    libraryLoaded = true
                    true
                }.onFailure { lastError = it }.getOrDefault(false)
                if (ok) return true
            }

            Log.e(
                "PurrfectSnap",
                "Failed to load native library (tried: ${candidates.joinToString()})",
                lastError
            )
            return false
        }

        fun ensureLibraryLoaded() {
            if (!tryEnsureLibraryLoaded()) {
                throw UnsatisfiedLinkError("Failed to load native library: ${BuildConfig.NATIVE_NAME}")
            }
        }
    }

    fun initOnce(callback: NativeLib.() -> Unit): () -> Unit {
        if (initialized) throw IllegalStateException("NativeLib already initialized")
        return runCatching {
            ensureLibraryLoaded()
            initialized = true
            callback(this)
            preInit()
            setChecksums(com.google.gson.Gson().toJson(Checksums.checksums))
            return@runCatching {
                signatureCache = init(signatureCache) ?: throw IllegalStateException("NativeLib init failed. Check logcat for more info")
            }
        }.onFailure {
            initialized = false
            Log.e("PurrfectSnap", "NativeLib init failed", it)
        }.getOrThrow()
    }

    @Suppress("unused")
    private fun onNativeUnaryCall(uri: String, buffer: ByteArray): NativeRequestData? {
        val nativeRequestData = NativeRequestData(uri, buffer)
        runCatching {
            nativeUnaryCallCallback(nativeRequestData)
        }.onFailure {
            Log.e("PurrfectSnap", "nativeUnaryCallCallback failed", it)
        }
        if (nativeRequestData.canceled || !nativeRequestData.buffer.contentEquals(buffer)) return nativeRequestData
        return null
    }

    fun loadNativeConfig(config: NativeConfig) {
        if (!initialized) return
        loadConfig(config)
    }

    fun lockNativeDatabase(name: String, callback: () -> Unit) {
        if (!initialized) return
        lockDatabase(name) {
            runCatching {
                callback()
            }.onFailure {
                Log.e("PurrfectSnap", "lockNativeDatabase callback failed", it)
            }
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadSharedLibrary(content: ByteArray) {
        if (!initialized) throw IllegalStateException("NativeLib not initialized")
        val generatedPath = "/data/app/${Random.nextLong().absoluteValue.toString(16)}.so"
        addLinkerSharedLibrary(generatedPath, content)
        System.load(generatedPath)
    }

    private external fun preInit()
    private external fun init(signatureCache: String?): String?
    private external fun loadConfig(config: NativeConfig)
    external fun verifyKey(key: String): Boolean
    private external fun lockDatabase(name: String, callback: Runnable)
    external fun setComposerLoader(code: String)
    external fun composerEval(code: String): String?
    private external fun addLinkerSharedLibrary(path: String, content: ByteArray)
    private external fun evaluateEndpointNative(uri: String, arg0: String, hasAttestation: Boolean, outDecision: NativeDecision)
    external fun shouldBlockDuplexClient(path: String): Boolean
    private external fun evaluateAuthContextNative(requestPath: String, attestationRequired: Boolean, outDecision: NativeDecision)
    private external fun evaluateApiInvocationNative(methodId: String, annotations: String, outDecision: NativeDecision)
    private external fun runEndpointSelfTest(testMode: Boolean): Boolean
    private external fun setChecksums(checksums: String)
    external fun setTestMode(testMode: Boolean)
    external fun setInLoginSignup(inLoginSignup: Boolean)


    fun evaluateEndpoint(uri: String, arg0: String, hasAttestation: Boolean): NativeDecision {
        return NativeDecision().also { evaluateEndpointNative(uri, arg0, hasAttestation, it) }
    }

    fun evaluateAuthContext(requestPath: String, attestationRequired: Boolean): NativeDecision {
        return NativeDecision().also { evaluateAuthContextNative(requestPath, attestationRequired, it) }
    }

    fun evaluateApiInvocation(methodId: String, annotations: String): NativeDecision {
        return NativeDecision().also { evaluateApiInvocationNative(methodId, annotations, it) }
    }

    fun isEndpointBlockerHealthy(testMode: Boolean = false): Boolean {
        if (!initialized) return false
        return runEndpointSelfTest(testMode)
    }
}
