package me.eternal.purrfectsnap.common.bridge.wrapper

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import me.eternal.purrfectsnap.bridge.storage.FileHandleManager
import me.eternal.purrfectsnap.common.BuildConfig
import me.eternal.purrfectsnap.common.Constants
import me.eternal.purrfectsnap.common.bridge.FileHandleScope
import me.eternal.purrfectsnap.common.bridge.InternalFileHandleType
import me.eternal.purrfectsnap.common.bridge.InternalFileWrapper
import me.eternal.purrfectsnap.common.logger.AbstractLogger
import me.eternal.purrfectsnap.common.util.LazyBridgeValue
import me.eternal.purrfectsnap.mapper.AbstractClassMapper
import me.eternal.purrfectsnap.mapper.ClassMapper
import kotlin.reflect.KClass

class MappingsWrapper(
    private val fileHandleManager: LazyBridgeValue<FileHandleManager>
): InternalFileWrapper(fileHandleManager, InternalFileHandleType.MAPPINGS, defaultValue = "{}") {
    private lateinit var context: Context
    private var mappingUniqueHash: Long = 0
    var isMappingsLoaded = false
        private set

    private val mappers = ClassMapper.DEFAULT_MAPPERS.associateBy { it::class }

    private fun getUniqueBuildId() = (getSnapchatPackageInfo()?.longVersionCode ?: -1) xor BuildConfig.BUILD_HASH.hashCode().toLong()

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

    fun getGeneratedBuildNumber() = mappingUniqueHash
    fun isMappingsOutdated() = mappingUniqueHash != getUniqueBuildId() || isMappingsLoaded.not()

    private fun loadCached() {
        if (!exists()) {
            throw Exception("Mappings file does not exist")
        }
        mappers.values.forEach { mapper ->
            mapper.classLoader = context.classLoader
        }
        val mappingsObject = JsonParser.parseString(readBytes().toString(Charsets.UTF_8)).asJsonObject.also {
            mappingUniqueHash = it["unique_hash"].asLong
        }

        mappingsObject.entrySet().forEach { (key, value) ->
            mappers.values.firstOrNull { it.mapperName == key }?.let { mapper ->
                mapper.readFromJson(value.asJsonObject)
            }
        }
        isMappingsLoaded = true
    }

    fun refresh(): List<String> {
        mappingUniqueHash = getUniqueBuildId()

        // reset native signature cache
        fileHandleManager.value.getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.NATIVE_SIG_CACHE.key).delete()

        val classMapper = ClassMapper(*mappers.values.toTypedArray())
        mappers.values.forEach { mapper ->
            mapper.classLoader = context.classLoader
        }

        runCatching {
            classMapper.loadApk(getSnapchatPackageInfo()?.applicationInfo?.sourceDir ?: throw Exception("Failed to get APK"))
        }.onFailure {
            throw Exception("Failed to load APK", it)
        }

        runBlocking {
            val result = classMapper.run().apply {
                addProperty("unique_hash", mappingUniqueHash)
            }
            writeBytes(result.toString().toByteArray())
        }

        loadCached()

        return classMapper.getWarns()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AbstractClassMapper> useMapper(type: KClass<T>, callback: T.() -> Unit) {
        mappers[type]?.let {
            callback(it as? T ?: return)
        } ?: run {
            AbstractLogger.directError("Mapper ${type.simpleName} is not registered", Throwable())
        }
    }
}
