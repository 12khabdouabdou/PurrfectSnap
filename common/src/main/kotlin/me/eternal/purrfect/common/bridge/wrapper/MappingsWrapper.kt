package me.eternal.purrfect.common.bridge.wrapper

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import me.eternal.purrfect.bridge.storage.FileHandleManager
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.InternalFileWrapper
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.common.util.LazyBridgeValue
import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ClassMapper
import me.eternal.purrfect.mapper.impl.CallbackMapper
import java.io.File
import kotlin.reflect.KClass

class MappingsWrapper(
    private val fileHandleManager: LazyBridgeValue<FileHandleManager>
): InternalFileWrapper(fileHandleManager, InternalFileHandleType.MAPPINGS, defaultValue = "{}") {
    private lateinit var context: Context
    private var mappingUniqueHash: Long = 0
    var isMappingsLoaded = false
        private set

    private var mappers = createMappers()

    private fun createMappers(): Map<KClass<out AbstractClassMapper>, AbstractClassMapper> {
        return ClassMapper.DEFAULT_MAPPERS.associateBy { it::class }
    }

    private fun resetMappers() {
        mappers = createMappers()
        if (::context.isInitialized) {
            mappers.values.forEach { mapper ->
                mapper.classLoader = context.classLoader
            }
        }
    }

    private fun getUniqueBuildId(): Long {
        var hash = FNV_64_OFFSET_BASIS

        fun mixLong(value: Long) {
            hash = (hash xor value) * FNV_64_PRIME
        }

        fun mixString(value: String?) {
            val clean = value.orEmpty()
            mixLong(clean.length.toLong())
            clean.forEach { char -> mixLong(char.code.toLong()) }
        }

        val packageInfo = getSnapchatPackageInfo()
        val apkPaths = runCatching { getSnapchatApkPaths() }.getOrDefault(emptyList())
        val apkInfoStr = apkPaths.sorted().map { path ->
            val file = File(path)
            "$path:${runCatching { file.length() }.getOrDefault(-1L)}"
        }.joinToString()

        AbstractLogger.directDebug(
            "[MAPPINGS] getUniqueBuildId details: schema=$MAPPINGS_SCHEMA_VERSION " +
                "appId=${BuildConfig.APPLICATION_ID} verCode=${BuildConfig.VERSION_CODE} " +
                "buildHash=${BuildConfig.BUILD_HASH} gitHash=${BuildConfig.GIT_HASH} " +
                "snapVer=${packageInfo?.longVersionCode} snapName=${packageInfo?.versionName} " +
                "snapUpdated=${packageInfo?.lastUpdateTime} apks=[$apkInfoStr]",
            "PurrfectMapper"
        )

        mixLong(MAPPINGS_SCHEMA_VERSION)
        mixString(BuildConfig.APPLICATION_ID)
        mixLong(BuildConfig.VERSION_CODE.toLong())
        mixLong(packageInfo?.longVersionCode ?: -1L)
        mixString(packageInfo?.versionName)
        mixLong(packageInfo?.lastUpdateTime ?: -1L)

        apkPaths.sorted().forEach { path ->
            val file = File(path)
            mixString(path)
            mixLong(runCatching { file.length() }.getOrDefault(-1L))
        }
        return hash
    }

    fun init(context: Context) {
        this.context = context
        mappingUniqueHash = getUniqueBuildId()

        if (exists()) {
            runCatching {
                loadCached()
            }.onFailure {
                delete()
            }
        }
    }

    fun getSnapchatPackageInfo() = runCatching {
        context.packageManager.getPackageInfo(
            Constants.SNAPCHAT_PACKAGE_NAME,
            0
        )
    }.getOrNull()

    private fun getSnapchatApkPaths(): List<String> {
        val appInfo = getSnapchatPackageInfo()?.applicationInfo
            ?: throw Exception("Failed to get Snapchat package info")
        val apkPaths = mutableListOf<String>()

        appInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { apkPaths.add(it) }
        appInfo.splitSourceDirs?.forEach { splitPath ->
            splitPath?.takeIf { it.isNotBlank() }?.let { apkPaths.add(it) }
        }

        return apkPaths.distinct()
    }

    private var savedSnapchatVersionCode: Long = -1L

    fun getGeneratedBuildNumber() = mappingUniqueHash

    fun isSnapchatVersionChanged(): Boolean {
        val packageInfo = getSnapchatPackageInfo() ?: return false
        val currentVersion = packageInfo.longVersionCode
        val changed = savedSnapchatVersionCode != -1L && savedSnapchatVersionCode != currentVersion
        AbstractLogger.directDebug(
            "[MAPPINGS] isSnapchatVersionChanged: changed=$changed saved=$savedSnapchatVersionCode current=$currentVersion",
            "PurrfectMapper"
        )
        return changed
    }

    fun isModBuildChanged(): Boolean {
        val packageInfo = getSnapchatPackageInfo() ?: return false
        val currentVersion = packageInfo.longVersionCode
        val isSnapchatSame = (savedSnapchatVersionCode == -1L || savedSnapchatVersionCode == currentVersion)
        return isSnapchatSame && isMappingsOutdated()
    }

    fun isMappingsOutdated(): Boolean {
        val currentHash = getUniqueBuildId()
        val isOutdated = mappingUniqueHash != currentHash || isMappingsLoaded.not()
        AbstractLogger.directDebug(
            "[MAPPINGS] isMappingsOutdated: isOutdated=$isOutdated " +
                "mappingUniqueHash=$mappingUniqueHash currentUniqueBuildId=$currentHash " +
                "isMappingsLoaded=$isMappingsLoaded",
            "PurrfectMapper"
        )
        return isOutdated
    }

    private fun loadCached() {
        if (!exists()) {
            throw Exception("Mappings file does not exist")
        }
        resetMappers()
        val mappingsObject = JsonParser.parseString(readBytes().toString(Charsets.UTF_8)).asJsonObject.also {
            mappingUniqueHash = it.get("unique_hash")?.asLong ?: 0L
            savedSnapchatVersionCode = it.get("snapchat_version_code")?.asLong ?: -1L
        }

        mappingsObject.entrySet().forEach { (key, value) ->
            mappers.values.firstOrNull { it.mapperName == key }?.let { mapper ->
                mapper.readFromJson(value.asJsonObject)
            }
        }
        validateCriticalMappings(mappingsObject)
        isMappingsLoaded = true
    }

    fun refresh(): List<String> {
        mappingUniqueHash = getUniqueBuildId()
        val currentSnapVersion = getSnapchatPackageInfo()?.longVersionCode ?: -1L
        AbstractLogger.directDebug(
            "[MAPPINGS] Refresh started package=${Constants.SNAPCHAT_PACKAGE_NAME} uniqueHash=$mappingUniqueHash snapVersion=$currentSnapVersion",
            "PurrfectMapper"
        )

        // reset native signature cache
        fileHandleManager.value.getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.NATIVE_SIG_CACHE.key).delete()
        AbstractLogger.directDebug("[MAPPINGS] Native signature cache reset", "PurrfectMapper")

        resetMappers()
        val classMapper = ClassMapper(*mappers.values.toTypedArray())

        val apkPaths = getSnapchatApkPaths()
        AbstractLogger.directDebug(
            "[MAPPINGS] Snapchat APK paths count=${apkPaths.size} paths=${apkPaths.joinToString()}",
            "PurrfectMapper"
        )

        runCatching {
            apkPaths.forEachIndexed { index, apkPath ->
                val stats = classMapper.loadApk(apkPath)
                AbstractLogger.directDebug(
                    "[MAPPINGS] Loaded APK[$index] path=$apkPath dex=${stats.dexFiles} classes=${stats.classes} origin=${stats.fromLspatchOrigin}",
                    "PurrfectMapper"
                )
            }
        }.onFailure {
            throw Exception("Failed to load Snapchat APK set (${apkPaths.size} files)", it)
        }

        runBlocking {
            val result = classMapper.run().apply {
                addProperty("unique_hash", mappingUniqueHash)
                addProperty("snapchat_version_code", currentSnapVersion)
            }
            validateCriticalMappings(result)

            writeBytes(result.toString().toByteArray())
        }
        AbstractLogger.directDebug(
            "[MAPPINGS] Mapper run complete totalClasses=${classMapper.getLoadedClassCount()} uniqueTypes=${classMapper.getLoadedTypeCount()} warnings=${classMapper.getWarns().size}",
            "PurrfectMapper"
        )

        loadCached()
        AbstractLogger.directDebug("[MAPPINGS] Refresh cached mappings loaded", "PurrfectMapper")

        return classMapper.getWarns()
    }

    private fun validateCriticalMappings(mappingsObject: JsonObject) {
        val callbacks = mappingsObject
            .getAsJsonObject(CallbackMapper.MAPPER_NAME)
            ?.getAsJsonObject(CallbackMapper.CALLBACKS_KEY)
        val authContextDelegate = callbacks
            ?.get("AuthContextDelegate")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw IllegalStateException("Critical mapping missing: ${CallbackMapper.MAPPER_NAME}.${CallbackMapper.CALLBACKS_KEY}.AuthContextDelegate")

        if (context.packageName == Constants.SNAPCHAT_PACKAGE_NAME) {
            runCatching {
                context.classLoader.loadClass(authContextDelegate)
            }.onFailure { throwable ->
                throw IllegalStateException(
                    "Critical mapping class is stale: AuthContextDelegate -> $authContextDelegate",
                    throwable
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AbstractClassMapper> useMapper(type: KClass<T>, callback: T.() -> Unit) {
        mappers[type]?.let {
            callback(it as? T ?: return)
        } ?: run {
            AbstractLogger.directError("Mapper ${type.simpleName} is not registered", Throwable())
        }
    }

    fun hasMapper(type: KClass<out AbstractClassMapper>): Boolean = mappers.containsKey(type)

    private companion object {
        private const val MAPPINGS_SCHEMA_VERSION = 4L
        private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_64_PRIME = 1099511628211L
    }
}
