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
	private var spoofedDeviceInfo: DeviceInfo? = null
	private var spoofedFingerprint: String? = null

	private fun generateAndroidId(): String {
		// Always check custom ID first - this ensures changes take effect immediately
		val customId = context.config.experimental.spoof.spoofDeviceId.customAndroidId.getNullable()
		if (!customId.isNullOrEmpty()) {
			val normalizedId = customId.lowercase().trim()
			if (normalizedId.length == 16 && normalizedId.all { it in '0'..'9' || it in 'a'..'f' }) {
				// Only log when ID actually changes
				if (spoofedAndroidId != normalizedId) {
					spoofedAndroidId = normalizedId
					context.log.info("Using custom Android ID: $spoofedAndroidId")
				}
				return spoofedAndroidId!!
			} else {
				context.log.warn("Invalid custom Android ID format (must be 16 hex chars), generating new one")
			}
		}

		// No custom ID set - use stored or generate new one
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val storedId = sharedPrefs.getString("android_id", null)
		if (storedId == null || storedId.length != 16) {
			spoofedAndroidId = DeviceSpoofer.generateAndroidId()
			sharedPrefs.edit().putString("android_id", spoofedAndroidId).apply()
			context.log.info("Generated new Android ID: $spoofedAndroidId")
		} else {
			// Only use cached value if it matches stored value (handles regeneration)
			if (spoofedAndroidId != storedId) {
				spoofedAndroidId = storedId
				context.log.info("Using stored Android ID: $spoofedAndroidId")
			}
		}
		return spoofedAndroidId!!
	}

	private fun getDeviceInfo(modelName: String): DeviceInfo? {
		return DeviceSpoofer.getDeviceInfo(modelName)
	}

	private fun getSpoofedDeviceInfo(): DeviceInfo? {
		val selectedModel = context.config.experimental.spoof.deviceModel.getNullable() ?: return null
		if (selectedModel == "random") {
			val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
			val randomDevice = sharedPrefs.getString("random_device", null)
			if (randomDevice == null) {
				val availableDevices = DeviceSpoofer.getAvailableDevices()
				val newRandomDevice = availableDevices.random()
				sharedPrefs.edit().putString("random_device", newRandomDevice).apply()
				context.log.info("Randomly selected device: $newRandomDevice")
				spoofedDeviceInfo = getDeviceInfo(newRandomDevice)
			} else {
				context.log.info("Using stored random device: $randomDevice")
				spoofedDeviceInfo = getDeviceInfo(randomDevice)
			}
			return spoofedDeviceInfo
		}
		if (selectedModel == "none" || selectedModel == "null") return null
		spoofedDeviceInfo = getDeviceInfo(selectedModel)
		return spoofedDeviceInfo
	}

	private fun getSpoofedFingerprint(deviceInfo: DeviceInfo): String {
		val sharedPrefs = context.androidContext.getSharedPreferences("purrfectsnap_spoof", 0)
		val storedFingerprint = sharedPrefs.getString("device_fingerprint", null)
		if (storedFingerprint == null) {
			val buildVersion = Build.VERSION.RELEASE
			spoofedFingerprint = DeviceSpoofer.generateFingerprint(deviceInfo, buildVersion)
			sharedPrefs.edit().putString("device_fingerprint", spoofedFingerprint).apply()
			context.log.info("Generated new device fingerprint: $spoofedFingerprint")
		} else {
			spoofedFingerprint = storedFingerprint
			context.log.info("Using stored device fingerprint: $spoofedFingerprint")
		}
		return spoofedFingerprint!!
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

	private fun hookInstallerPackageName() {
		context.androidContext.packageManager::class.java.hook("getInstallerPackageName", HookStage.BEFORE) { param ->
			param.setResult("com.android.vending")
		}
	}

	@SuppressLint("MissingPermission")
	override fun init() {
		val spoofDevice by context.config.experimental.spoof.spoofDevice
		if (spoofDevice) {
			val deviceInfo = getSpoofedDeviceInfo()
			if (deviceInfo != null) {
				getSpoofedFingerprint(deviceInfo)
				context.log.info("Device spoofing initialized: ${deviceInfo.manufacturer} ${deviceInfo.model}")
			}
		}

		if (LSPatchUpdater.HAS_LSPATCH) {
			hookInstallerPackageName()
		}

		if (context.config.experimental.spoof.globalState != true) return

		val removeMockLocationFlag by context.config.experimental.spoof.removeMockLocationFlag
		val overridePlayStoreInstallerPackageName by context.config.experimental.spoof.overridePlayStoreInstallerPackageName
		val removeVpnTransportFlag by context.config.experimental.spoof.removeVpnTransportFlag
		val forceWifiTransportFlag by context.config.experimental.spoof.forceWifiTransportFlag
		val spoofAndroidId by context.config.experimental.spoof.spoofDeviceId.spoofAndroidId

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

		if (spoofDevice) {
			val deviceInfo = getSpoofedDeviceInfo()
			if (deviceInfo != null) {
				val fingerprint = getSpoofedFingerprint(deviceInfo)

				context.log.info("Device spoofing active: ${deviceInfo.manufacturer} ${deviceInfo.model}")

				Build::class.java.apply {
					fields.forEach { field ->
						if (!field.isAccessible) field.isAccessible = true
						runCatching {
							val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
							modifiersField.isAccessible = true
							modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
						}
						when (field.name) {
							"MANUFACTURER" -> field.set(null, deviceInfo.manufacturer)
							"MODEL" -> field.set(null, deviceInfo.model)
							"BRAND" -> field.set(null, deviceInfo.brand)
							"DEVICE" -> field.set(null, deviceInfo.device)
							"PRODUCT" -> field.set(null, deviceInfo.product)
							"HARDWARE" -> field.set(null, deviceInfo.hardware)
							"FINGERPRINT" -> field.set(null, fingerprint)
							"BOARD" -> try { field.set(null, deviceInfo.board) } catch (_: Exception) {}
							"BOOTLOADER" -> try { field.set(null, deviceInfo.bootloader) } catch (_: Exception) {}
							"DISPLAY" -> try { field.set(null, deviceInfo.display) } catch (_: Exception) {}
							"HOST" -> try { field.set(null, deviceInfo.host) } catch (_: Exception) {}
							"TIME" -> try {
								val currentTime = System.currentTimeMillis()
								val randomDaysAgo = (30..180).random()
								val buildTime = currentTime - (randomDaysAgo * 24L * 60L * 60L * 1000L)
								field.setLong(null, buildTime)
							} catch (_: Exception) {}
						}
					}
				}

				runCatching {
					findClass("android.os.SystemProperties").hook("get", HookStage.BEFORE) { param ->
						val key = param.argNullable<String>(0) ?: return@hook
						when (key) {
							"ro.product.manufacturer" -> param.setResult(deviceInfo.manufacturer)
							"ro.product.model" -> param.setResult(deviceInfo.model)
							"ro.product.brand" -> param.setResult(deviceInfo.brand)
							"ro.product.device" -> param.setResult(deviceInfo.device)
							"ro.product.name" -> param.setResult(deviceInfo.product)
							"ro.product.board" -> param.setResult(deviceInfo.board)
							"ro.hardware" -> param.setResult(deviceInfo.hardware)
							"ro.build.fingerprint" -> param.setResult(fingerprint)
							"ro.bootloader" -> param.setResult(deviceInfo.bootloader)
							"ro.build.display.id" -> param.setResult(deviceInfo.display)
						}
					}
					context.log.info("SystemProperties hooks installed successfully")
				}.onFailure {
					context.log.warn("Failed to hook SystemProperties: ${it.message}")
				}
			}
		}
	}
}
