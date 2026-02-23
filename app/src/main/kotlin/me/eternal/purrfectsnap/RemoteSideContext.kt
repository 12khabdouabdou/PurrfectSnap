package me.eternal.purrfectsnap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.CoreComponentFactory
import androidx.documentfile.provider.DocumentFile
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.eternal.purrfectsnap.bridge.BridgeService
import me.eternal.purrfectsnap.common.BuildConfig
import me.eternal.purrfectsnap.common.Constants
import me.eternal.purrfectsnap.common.action.EnumAction
import me.eternal.purrfectsnap.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfectsnap.common.bridge.wrapper.LoggerWrapper
import me.eternal.purrfectsnap.common.bridge.wrapper.MappingsWrapper
import me.eternal.purrfectsnap.common.config.ModConfig
import me.eternal.purrfectsnap.common.logger.fatalCrash
import me.eternal.purrfectsnap.common.util.constantLazyBridge
import me.eternal.purrfectsnap.common.util.getPurgeTime
import me.eternal.purrfectsnap.e2ee.E2EEImplementation
import me.eternal.purrfectsnap.scripting.RemoteScriptManager
import me.eternal.purrfectsnap.storage.AppDatabase
import me.eternal.purrfectsnap.task.RemoteTaskInterface
import me.eternal.purrfectsnap.task.TaskManager
import me.eternal.purrfectsnap.ui.manager.MainActivity
import me.eternal.purrfectsnap.ui.manager.data.InstallationSummary
import me.eternal.purrfectsnap.ui.manager.data.ModInfo
import me.eternal.purrfectsnap.ui.manager.data.PlatformInfo
import me.eternal.purrfectsnap.ui.manager.data.SnapchatAppInfo
import me.eternal.purrfectsnap.ui.overlay.RemoteOverlay
import me.eternal.purrfectsnap.ui.setup.Requirements
import me.eternal.purrfectsnap.ui.setup.SetupActivity
import me.eternal.purrfectsnap.task.AnnouncementCheckWorker
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import java.util.concurrent.TimeUnit


class RemoteSideContext(
    val androidContext: Context
) {
    val fetch: Fetch by lazy {
        val fetchConfiguration = FetchConfiguration.Builder(androidContext)
            .setDownloadConcurrentLimit(3)
            .build()
        Fetch.getInstance(fetchConfiguration)
    }
    val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var _activity: WeakReference<ComponentActivity>? = null
    var bridgeService: BridgeService? = null

    var activity: ComponentActivity?
        get() = _activity?.get()
        set(value) { _activity?.clear(); _activity = WeakReference(value) }

    val sharedPreferences: SharedPreferences get() = androidContext.getSharedPreferences("prefs", 0)
    val fileHandleManager = RemoteFileHandleManager(this)
    val database = AppDatabase(this)
    val trackerDataManager = me.eternal.purrfectsnap.storage.TrackerDataManagerImpl(database)
    val config = ModConfig(androidContext, constantLazyBridge { fileHandleManager })
    val translation = LocaleWrapper(androidContext, constantLazyBridge { fileHandleManager })
    val mappings = MappingsWrapper(constantLazyBridge { fileHandleManager })
    val taskManager = TaskManager(this)
    val taskInterface = RemoteTaskInterface(this)
    val streaksReminder = StreaksReminder(this)
    val log = LogManager(this)
    val scriptManager = RemoteScriptManager(this)
    val remoteOverlay = RemoteOverlay(this)
    val e2eeImplementation = E2EEImplementation(this)
    val messageLogger by lazy { LoggerWrapper(androidContext) }
    val tracker = RemoteTracker(this)
    val accountStorage = RemoteAccountStorage(this)
    val locationManager = RemoteLocationManager(this)

    init {
        val prefs = androidContext.getSharedPreferences("prefs", 0)
        if (!prefs.contains("debug_test_mode")) {
            prefs.edit().putBoolean("debug_test_mode", true).apply()
        }
    }

    //used to load bitmoji selfies and download previews
    val imageLoader by lazy {
        ImageLoader.Builder(androidContext)
            .dispatcher(Dispatchers.IO)
            .memoryCache {
                MemoryCache.Builder(androidContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(androidContext.cacheDir.resolve("coil-disk-cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100MB
                    .build()
            }
            .components { add(VideoFrameDecoder.Factory()) }.build()
    }

    val gson: Gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    fun reload() {
        runCatching {
            runBlocking(Dispatchers.IO) {
                log.init()
                log.verbose("Loading RemoteSideContext")
                config.load()
                ensureAutoUpdateCheckOnUpgrade()
                launch {
                    mappings.apply {
                        init(androidContext)
                    }
                }
                translation.apply {
                    userLocale = config.locale
                    load()
                }
                scheduleAnnouncementCheck()
                database.init()
                streaksReminder.init()
                scriptManager.init()
                launch {
                    taskManager.init()
                    config.root.messaging.messageLogger.takeIf {
                        it.globalState == true
                    }?.autoPurge?.let { getPurgeTime(it.getNullable()) }?.let {
                        messageLogger.purgeAll(it)
                    }

                    config.root.friendTracker.takeIf {
                        it.globalState == true
                    }?.autoPurge?.let { getPurgeTime(it.getNullable()) }?.let {
                        messageLogger.purgeTrackerLogs(it)
                    }
                }
            }
        }.onFailure {
            log.error("Failed to load RemoteSideContext", it)
            androidContext.fatalCrash(it)
        }

        scriptManager.runtime.eachModule {
            callFunction("module.onPurrfectSnapLoad", androidContext)
        }
    }

    val installationSummary by lazy {
        InstallationSummary(
            snapchatInfo = mappings.getSnapchatPackageInfo()?.let {
                val packageName = requireNotNull(it.packageName) { "Package name cannot be null" }
                SnapchatAppInfo(
                    packageName = packageName,
                    version = it.versionName ?: "unknown",
                    versionCode = it.longVersionCode,
                    isLSPatched = it.applicationInfo?.appComponentFactory != CoreComponentFactory::class.java.name,
                    isSplitApk = it.splitNames?.isNotEmpty() ?: false
                )
            },
            modInfo = ModInfo(
                loaderPackageName = MainActivity::class.java.`package`?.name,
                buildPackageName = androidContext.packageName,
                buildVersion = BuildConfig.VERSION_NAME,
                buildVersionCode = BuildConfig.VERSION_CODE.toLong(),
                buildIssuer = androidContext.packageManager.getPackageInfo(androidContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.signingInfo?.apkContentsSigners?.firstOrNull()?.let {
                        val certFactory = CertificateFactory.getInstance("X509")
                        val cert = certFactory.generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate
                        cert.issuerDN.toString()
                    } ?: throw Exception("Failed to get certificate info"),
                gitHash = BuildConfig.GIT_HASH,
                isDebugBuild = BuildConfig.DEBUG,
                mappingVersion = mappings.getGeneratedBuildNumber(),
                mappingsOutdated = mappings.isMappingsOutdated()
            ),
            platformInfo = PlatformInfo(
                device = Build.DEVICE,
                androidVersion = Build.VERSION.RELEASE,
                systemAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            )
        )
    }

    fun longToast(message: Any) {
        androidContext.mainExecutor.execute {
            Toast.makeText(androidContext, message.toString(), Toast.LENGTH_LONG).show()
        }
        log.debug(message.toString())
    }

    fun shortToast(message: Any) {
        androidContext.mainExecutor.execute {
            Toast.makeText(androidContext, message.toString(), Toast.LENGTH_SHORT).show()
        }
        log.debug(message.toString())
    }

    fun hasMessagingBridge() = bridgeService != null && bridgeService?.messagingBridge != null && bridgeService?.messagingBridge?.asBinder()?.pingBinder() == true

    fun checkForRequirements(overrideRequirements: Int? = null): Boolean {
        var requirements = overrideRequirements ?: 0
        if (sharedPreferences.getBoolean("setup_in_progress", false)) {
            requirements = requirements or Requirements.FIRST_RUN
        }
        if (!config.wasPresent) {
            requirements = requirements or Requirements.FIRST_RUN
        }

        config.root.downloader.saveFolder.get().let {
            val allowDefaultSaveFolder = sharedPreferences.getBoolean("downloader_use_default_save_folder", false)
            if (it.isEmpty()) {
                if (!allowDefaultSaveFolder) {
                    requirements = requirements or Requirements.SAVE_FOLDER
                }
            } else if (run {
                    val documentFile = runCatching { DocumentFile.fromTreeUri(androidContext, Uri.parse(it)) }.getOrNull()
                    documentFile == null || !documentFile.exists() || !documentFile.canWrite()
                }) {
                requirements = requirements or Requirements.SAVE_FOLDER
            }
        }

        if (!sharedPreferences.getBoolean("debug_disable_mapper", false) && mappings.getSnapchatPackageInfo() != null && mappings.isMappingsOutdated()) {
            requirements = requirements or Requirements.MAPPINGS
        }

        if (requirements == 0) return false

        val currentContext = activity ?: androidContext

        Intent(currentContext, SetupActivity::class.java).apply {
            putExtra("requirements", requirements)
            if (currentContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            currentContext.startActivity(this)
            return true
        }
    }

    fun launchActionIntent(action: EnumAction) {
        val intent = androidContext.packageManager.getLaunchIntentForPackage(
            Constants.SNAPCHAT_PACKAGE_NAME
        )
        if (intent == null) {
            shortToast(translation["toast_snapchat_not_installed"])
            return
        }
        intent.putExtra(EnumAction.ACTION_PARAMETER, action.key)
        androidContext.startActivity(intent)
    }

    private fun scheduleAnnouncementCheck() {
        val workManager = WorkManager.getInstance(androidContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val inputData = Data.Builder()
            .putString("announcements_url", "https://raw.githubusercontent.com/particle-box/PurrfectSnap/dev/announcements.txt")
            .putString("channel_name", "Announcements")
            .putString("channel_description", "Notifications for PurrfectSnap announcements")
            .putString("notification_title", "New announcement available")
            .putString("notification_text", "Tap to open and read.")
            .build()
        val workRequest = PeriodicWorkRequestBuilder<AnnouncementCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "purrfectsnap_announcement_check",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun ensureAutoUpdateCheckOnUpgrade() {
        val currentVersion = BuildConfig.VERSION_CODE.toLong()
        val lastVersion = sharedPreferences.getLong("last_build_version_code", -1L)
        val reenabledOnce = sharedPreferences.getBoolean("auto_update_reenabled_once", false)
        if (lastVersion == currentVersion) return
        if (!reenabledOnce) {
            config.root.global.updateSettings.autoUpdateCheck.set(true)
            config.writeConfig()
            sharedPreferences.edit()
                .putBoolean("auto_update_reenabled_once", true)
                .apply()
        }
        sharedPreferences.edit().putLong("last_build_version_code", currentVersion).apply()
    }
}
