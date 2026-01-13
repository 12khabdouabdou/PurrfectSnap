package me.eternal.purrfectsnap.common.config.impl

import me.eternal.purrfectsnap.common.config.ConfigContainer
import me.eternal.purrfectsnap.common.config.ConfigFlag

class Spoof : ConfigContainer(hasGlobalState = true) {
    inner class SpoofDeviceIdConfig : ConfigContainer() {
        val spoofAndroidId = boolean("spoof_android_id") { requireRestart() }
        val customAndroidId = string("custom_android_id") {
            requireRestart()
            inputCheck = { it.isEmpty() || (it.length == 16 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) }
        }
    }

    val overridePlayStoreInstallerPackageName = boolean("play_store_installer_package_name") { requireRestart() }
    val removeVpnTransportFlag = boolean("remove_vpn_transport_flag") { requireRestart() }
    val removeMockLocationFlag = boolean("remove_mock_location_flag") { requireRestart() }
    val forceWifiTransportFlag = boolean("force_wifi_transport_flag") { requireRestart() }
    val spoofDeviceId = container("spoof_device_id", SpoofDeviceIdConfig()) { requireRestart() }
    val spoofDevice = boolean("spoof_device") { requireRestart() }
    val deviceModel = unique("device_model",
        "samsung_s25_ultra",
        "google_pixel_10_pro",
        "oneplus_13",
        "xiaomi_15_ultra"
    ) { 
        requireRestart()
        customOptionTranslationPath = "features.options.device_model"
    }
}
