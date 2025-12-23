package me.eternal.purrfectsnap.core.features.impl.experiments

import android.annotation.SuppressLint
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import me.eternal.purrfectsnap.core.features.Feature
import me.eternal.purrfectsnap.core.util.LSPatchUpdater
import me.eternal.purrfectsnap.core.util.hook.HookStage
import me.eternal.purrfectsnap.core.util.hook.hook
import java.security.SecureRandom

class DeviceSpooferHook: Feature("Device Spoofer")  {
	private var spoofedAndroidId: String? = null
	private var hasLoggedId = false
	private var randomizedFingerprints = mutableMapOf<String, String>()

	private fun generateAndroidId(): String {
		if (spoofedAndroidId != null) return spoofedAndroidId!!
		val customId = context.config.experimental.spoof.customAndroidId.getNullable()
		if (!customId.isNullOrEmpty()) {
			spoofedAndroidId = customId.lowercase()
			if (!hasLoggedId) {
				context.log.info("Using custom Android ID: $spoofedAndroidId")
				hasLoggedId = true
			}
			return spoofedAndroidId!!
		}
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		spoofedAndroidId = sharedPrefs.getString("android_id", null)
		if (spoofedAndroidId == null) {
			spoofedAndroidId = generateRandomHexString(16)
			sharedPrefs.edit().putString("android_id", spoofedAndroidId).apply()
			context.log.info("Generated new Android ID: $spoofedAndroidId")
		} else if (!hasLoggedId) {
			context.log.info("Using stored Android ID: $spoofedAndroidId")
		}
		hasLoggedId = true
		return spoofedAndroidId!!
	}

	private fun generateRandomHexString(length: Int): String {
		val random = SecureRandom()
		val bytes = ByteArray(length / 2)
		random.nextBytes(bytes)
		return bytes.joinToString("") { "%02x".format(it) }
	}

	private fun randomizeFingerprintBuildNumber(fingerprint: String, deviceKey: String): String {
		if (randomizedFingerprints.containsKey(deviceKey)) {
			return randomizedFingerprints[deviceKey]!!
		}
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedFingerprint = sharedPrefs.getString("fingerprint_$deviceKey", null)
		if (savedFingerprint != null) {
			randomizedFingerprints[deviceKey] = savedFingerprint
			return savedFingerprint
		}
		val parts = fingerprint.split("/")
		if (parts.size >= 3) {
			val buildPart = parts[2]
			val buildSections = buildPart.split(":")
			if (buildSections.size >= 2) {
				val buildDetails = buildSections[1].split("/")
				if (buildDetails.size >= 2) {
					val randomBuildNumber = generateRandomBuildNumber()
					val newBuildDetails = buildDetails.toMutableList()
					newBuildDetails[1] = randomBuildNumber
					val newBuildPart = "${buildSections[0]}:${newBuildDetails.joinToString("/")}"
					val newFingerprint = "${parts[0]}/${parts[1]}/$newBuildPart"
					randomizedFingerprints[deviceKey] = newFingerprint
					sharedPrefs.edit().putString("fingerprint_$deviceKey", newFingerprint).apply()
					return newFingerprint
				}
			}
		}
		randomizedFingerprints[deviceKey] = fingerprint
		return fingerprint
	}

	private fun generateRandomBuildNumber(): String {
		val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
		val random = SecureRandom()
		return (1..10).map { chars[random.nextInt(chars.length)] }.joinToString("")
	}

	private fun randomizeDisplayId(display: String, deviceKey: String, buildNumber: String): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedDisplay = sharedPrefs.getString("display_$deviceKey", null)
		if (savedDisplay != null) return savedDisplay
		val parts = display.split(".")
		val newDisplay = if (parts.size > 1) {
			"${parts[0]}.${parts[1]}.$buildNumber"
		} else {
			display
		}
		sharedPrefs.edit().putString("display_$deviceKey", newDisplay).apply()
		return newDisplay
	}

	private fun getRandomSerial(deviceKey: String): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedSerial = sharedPrefs.getString("serial_$deviceKey", null)
		if (savedSerial != null) return savedSerial
		val random = SecureRandom()
		val serial = (1..16).map { 
			"0123456789ABCDEF"[random.nextInt(16)]
		}.joinToString("")
		sharedPrefs.edit().putString("serial_$deviceKey", serial).apply()
		return serial
	}

	private fun getRandomBuildId(deviceKey: String): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedBuildId = sharedPrefs.getString("build_id_$deviceKey", null)
		if (savedBuildId != null) return savedBuildId
		val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
		val random = SecureRandom()
		val buildId = (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
		sharedPrefs.edit().putString("build_id_$deviceKey", buildId).apply()
		return buildId
	}

	private fun getRandomGsfId(): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedGsfId = sharedPrefs.getString("gsf_id", null)
		if (savedGsfId != null) return savedGsfId
		val random = SecureRandom()
		val gsfId = (1..16).map { 
			"0123456789abcdef"[random.nextInt(16)]
		}.joinToString("")
		sharedPrefs.edit().putString("gsf_id", gsfId).apply()
		return gsfId
	}

	private fun generateRandomUUID(): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedUuid = sharedPrefs.getString("advertising_id", null)
		if (savedUuid != null) return savedUuid
		val random = SecureRandom()
		val uuid = "%08x-%04x-%04x-%04x-%012x".format(
			random.nextInt(),
			random.nextInt() and 0xFFFF,
			(random.nextInt() and 0x0FFF) or 0x4000,
			(random.nextInt() and 0x3FFF) or 0x8000,
			random.nextLong() and 0xFFFFFFFFFFFFL
		)
		sharedPrefs.edit().putString("advertising_id", uuid).apply()
		return uuid
	}

	private fun generateRandomMacAddress(): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val savedMac = sharedPrefs.getString("bluetooth_address", null)
		if (savedMac != null) return savedMac
		val random = SecureRandom()
		val mac = (1..6).map { 
			"%02x".format(random.nextInt(256))
		}.joinToString(":")
		sharedPrefs.edit().putString("bluetooth_address", mac).apply()
		return mac
	}

	data class DeviceProfile(
		val manufacturer: String,
		val brand: String,
		val model: String,
		val device: String,
		val product: String,
		val hardware: String,
		val board: String,
		val fingerprint: String,
		val display: String
	)

	private val deviceProfiles = mapOf(
		"samsung_s25_ultra" to DeviceProfile(
			manufacturer = "samsung",
			brand = "samsung",
			model = "SM-S938U",
			device = "e3q",
			product = "e3qsqw",
			hardware = "qcom",
			board = "taro",
			fingerprint = "samsung/e3qsqw/e3q:15/AP2A.240805.005/S938USQU1AXL2:user/release-keys",
			display = "AP2A.240805.005.S938USQU1AXL2"
		),
		"google_pixel_10_pro" to DeviceProfile(
			manufacturer = "Google",
			brand = "google",
			model = "Pixel 10 Pro",
			device = "caiman",
			product = "caiman",
			hardware = "caiman",
			board = "caiman",
			fingerprint = "google/caiman/caiman:15/AP2A.240805.005/12345678:user/release-keys",
			display = "AP2A.240805.005"
		),
		"oneplus_13" to DeviceProfile(
			manufacturer = "OnePlus",
			brand = "OnePlus",
			model = "CPH2649",
			device = "OP5B41L1",
			product = "CPH2649_EEA",
			hardware = "qcom",
			board = "kalama",
			fingerprint = "OnePlus/CPH2649_EEA/OP5B41L1:15/SKQ1.240805.001/1730123456789:user/release-keys",
			display = "CPH2649_15.0.0.300(EX01)"
		),
	"xiaomi_15_ultra" to DeviceProfile(
		manufacturer = "Xiaomi",
		brand = "Xiaomi",
		model = "23127PN0CC",
		device = "aurora",
		product = "aurora_global",
		hardware = "qcom",
		board = "taro",
		fingerprint = "Xiaomi/aurora_global/aurora:14/UKQ1.231003.002/V816.0.7.0.UMLMIXM:user/release-keys",
		display = "UKQ1.231003.002"
	)
	)

	private fun hookInstallerPackageName() {
		context.androidContext.packageManager::class.java.hook("getInstallerPackageName", HookStage.BEFORE) { param ->
			param.setResult("com.android.vending")
		}
	}

	@SuppressLint("MissingPermission")
	override fun init() {
		if (LSPatchUpdater.HAS_LSPATCH) {
			hookInstallerPackageName()
		}

		if (context.config.experimental.spoof.globalState != true) return

		val removeMockLocationFlag by context.config.experimental.spoof.removeMockLocationFlag
		val overridePlayStoreInstallerPackageName by context.config.experimental.spoof.overridePlayStoreInstallerPackageName
		val removeVpnTransportFlag by context.config.experimental.spoof.removeVpnTransportFlag
		val forceWifiTransportFlag by context.config.experimental.spoof.forceWifiTransportFlag
		val spoofAndroidId by context.config.experimental.spoof.spoofAndroidId

		if(overridePlayStoreInstallerPackageName) {
			hookInstallerPackageName()
		}

		if (removeMockLocationFlag) {
			Location::class.java.hook("isMock", HookStage.BEFORE) { param ->
				param.setResult(false)
			}
		}

		if (removeVpnTransportFlag) {
			ConnectivityManager::class.java.hook("getAllNetworks", HookStage.AFTER) { param ->
				val instance = param.thisObject() as? ConnectivityManager ?: return@hook
				val networks = param.getResult() as? Array<*> ?: return@hook

				param.setResult(networks.filterIsInstance<Network>().filter { network ->
					val capabilities = instance.getNetworkCapabilities(network) ?: return@filter false
					!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
				}.toTypedArray())
			}
		}

	if (forceWifiTransportFlag) {
		val connectivityManager = context.androidContext.getSystemService(ConnectivityManager::class.java)
		val activeNetwork = connectivityManager?.activeNetwork
		val initialCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
		val isReallyOnWifi = initialCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

		onNextActivityCreate {
			if (isReallyOnWifi) {
				context.inAppOverlay.showStatusToast(
					icon = Icons.Filled.Wifi,
					text = "Connected to Real WiFi",
					durationMs = 3000
				)
			} else {
				context.inAppOverlay.showStatusToast(
					icon = Icons.Filled.Wifi,
					text = "WiFi Spoofing Active",
					durationMs = 3000
				)
			}
		}

		NetworkCapabilities::class.java.apply {
			hook("hasTransport", HookStage.BEFORE) { param ->
				val transportType = param.args().getOrNull(0) as? Int
				val actuallyHasWifi = runCatching { 
					param.invokeOriginal() as Boolean
				}.getOrDefault(false)
				
				if (actuallyHasWifi == true && transportType == NetworkCapabilities.TRANSPORT_WIFI) {
					return@hook
				}
				
				if (transportType == NetworkCapabilities.TRANSPORT_WIFI) {
					param.setResult(true)
				} else if (transportType == NetworkCapabilities.TRANSPORT_CELLULAR) {
					param.setResult(false)
				}
			}
			hook("hasCapability", HookStage.BEFORE) { param ->
				val capability = param.args().getOrNull(0) as? Int
				if (capability == NetworkCapabilities.NET_CAPABILITY_NOT_VPN) {
					param.setResult(true)
				}
			}
		}
		findClass("android.net.NetworkInfo").apply {
			hook("getType", HookStage.BEFORE) { param ->
				val originalType = runCatching { 
					param.invokeOriginal() as Int
				}.getOrDefault(-1)
				
				if (originalType == 1) {
					return@hook
				}
				param.setResult(1)
			}
			hook("getTypeName", HookStage.BEFORE) { param ->
				val originalTypeName = runCatching {
					param.invokeOriginal() as String
				}.getOrNull()
				
				if (originalTypeName?.equals("WIFI", ignoreCase = true) == true) {
					return@hook
				}
				param.setResult("WIFI")
			}
			hook("isConnected", HookStage.BEFORE) { param ->
				param.setResult(true)
			}
		}
		findClass("org.chromium.base.RadioUtils").hook("isWifiConnected", HookStage.BEFORE) { param ->
			val actuallyOnWifi = runCatching {
				val connectivityMgr = context.androidContext.getSystemService(ConnectivityManager::class.java)
				val activeNet = connectivityMgr?.activeNetwork
				val caps = activeNet?.let { connectivityMgr.getNetworkCapabilities(it) }
				caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
			}.getOrDefault(false)
			
			if (!actuallyOnWifi) {
				param.setResult(true)
			}
		}
	}

		if (spoofAndroidId) {
			val gsfId = getRandomGsfId()
			val wifiMac = generateRandomMacAddress()
			findClass("android.provider.Settings\$Secure").hook("getString", HookStage.BEFORE) { param ->
				val settingName = param.argNullable<String>(1)
				when (settingName) {
					"android_id" -> param.setResult(generateAndroidId())
					"advertising_id" -> param.setResult(generateRandomUUID())
					"bluetooth_address" -> param.setResult(wifiMac)
				}
			}
			findClass("android.provider.Settings\$Secure").hook("getLong", HookStage.BEFORE) { param ->
				val settingName = param.argNullable<String>(1)
				if (settingName == "android_id") {
					param.setResult(generateAndroidId().hashCode().toLong())
				}
			}
			runCatching {
				findClass("android.net.wifi.WifiInfo").hook("getMacAddress", HookStage.BEFORE) { param ->
					param.setResult(wifiMac)
				}
			}
			runCatching {
				findClass("android.bluetooth.BluetoothAdapter").hook("getAddress", HookStage.BEFORE) { param ->
					param.setResult(wifiMac)
				}
			}
		}

		val spoofDevice by context.config.experimental.spoof.spoofDevice
		if (spoofDevice) {
			val selectedDevice = context.config.experimental.spoof.deviceModel.getNullable() ?: "samsung_s25_ultra"
			val deviceProfile = deviceProfiles[selectedDevice] ?: deviceProfiles["samsung_s25_ultra"]!!
			val randomizedFingerprint = randomizeFingerprintBuildNumber(deviceProfile.fingerprint, selectedDevice)
			val buildNumber = randomizedFingerprint.split("/").getOrNull(2)?.split(":")?.getOrNull(1)?.split("/")?.getOrNull(1) ?: generateRandomBuildNumber()
			val randomizedDisplay = randomizeDisplayId(deviceProfile.display, selectedDevice, buildNumber)
			val randomSerial = getRandomSerial(selectedDevice)
			val randomBuildId = getRandomBuildId(selectedDevice)
			
			context.log.info("Spoofing device as: ${deviceProfile.model}")

			Build::class.java.apply {
				fields.forEach { field ->
					if (!field.isAccessible) field.isAccessible = true
					runCatching {
						val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
						modifiersField.isAccessible = true
						modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
					}
					when (field.name) {
						"MANUFACTURER" -> field.set(null, deviceProfile.manufacturer)
						"BRAND" -> field.set(null, deviceProfile.brand)
						"MODEL" -> field.set(null, deviceProfile.model)
						"DEVICE" -> field.set(null, deviceProfile.device)
						"PRODUCT" -> field.set(null, deviceProfile.product)
						"HARDWARE" -> field.set(null, deviceProfile.hardware)
						"BOARD" -> field.set(null, deviceProfile.board)
						"FINGERPRINT" -> field.set(null, randomizedFingerprint)
						"DISPLAY" -> field.set(null, randomizedDisplay)
						"SERIAL" -> field.set(null, randomSerial)
						"ID" -> field.set(null, randomBuildId)
						"TAGS" -> field.set(null, "release-keys")
						"TYPE" -> field.set(null, "user")
						"USER" -> field.set(null, "android-build")
						"HOST" -> field.set(null, "build-host")
					}
				}
			}

			findClass("android.os.SystemProperties").apply {
				hook("get", HookStage.BEFORE) { param ->
					val key = param.arg<String>(0)
					when (key) {
						"ro.product.manufacturer", "ro.product.vendor.manufacturer", "ro.product.system.manufacturer", "ro.product.odm.manufacturer" -> param.setResult(deviceProfile.manufacturer)
						"ro.product.brand", "ro.product.vendor.brand", "ro.product.system.brand", "ro.product.odm.brand" -> param.setResult(deviceProfile.brand)
						"ro.product.model", "ro.product.vendor.model", "ro.product.system.model", "ro.product.odm.model" -> param.setResult(deviceProfile.model)
						"ro.product.device", "ro.product.vendor.device", "ro.product.system.device", "ro.product.odm.device" -> param.setResult(deviceProfile.device)
						"ro.product.name", "ro.product.vendor.name", "ro.product.system.name", "ro.product.odm.name" -> param.setResult(deviceProfile.product)
						"ro.hardware", "ro.hardware.chipname" -> param.setResult(deviceProfile.hardware)
						"ro.product.board" -> param.setResult(deviceProfile.board)
						"ro.build.fingerprint" -> param.setResult(randomizedFingerprint)
						"ro.build.display.id" -> param.setResult(randomizedDisplay)
						"ro.serialno", "ro.boot.serialno", "ril.serialnumber" -> param.setResult(randomSerial)
						"ro.build.id" -> param.setResult(randomBuildId)
						"ro.build.tags" -> param.setResult("release-keys")
						"ro.build.type" -> param.setResult("user")
						"ro.build.user" -> param.setResult("android-build")
						"ro.build.host" -> param.setResult("build-host")
					}
				}
			}
		}
	}
}
