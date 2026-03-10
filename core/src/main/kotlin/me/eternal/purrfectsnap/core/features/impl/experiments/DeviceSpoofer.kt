package me.eternal.purrfectsnap.core.features.impl.experiments

import java.security.SecureRandom

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val bootloader: String,
    val display: String,
    val host: String
)

object DeviceSpoofer {
    private val devices = mapOf(
        "Pixel 8 Pro" to DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            brand = "google",
            device = "husky",
            product = "husky",
            hardware = "husky",
            board = "husky",
            bootloader = "husky-1.0-11003666",
            display = "UQ1A.231205.015",
            host = "abfarm-release-rbe-64-00163"
        ),
        "Pixel 9 Pro XL" to DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 9 Pro XL",
            brand = "google",
            device = "komodo",
            product = "komodo",
            hardware = "komodo",
            board = "komodo",
            bootloader = "komodo-1.0-12110753",
            display = "AP3A.241105.008",
            host = "abfarm-release-rbe-64-00163"
        ),
        "Pixel 10" to DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 10",
            brand = "google",
            device = "frankel",
            product = "frankel",
            hardware = "tensor_g5",
            board = "frankel",
            bootloader = "frankel-1.0-12345678",
            display = "BP1A.250105.002",
            host = "abfarm-release-rbe-65-00200"
        ),
        "Pixel 10 Pro" to DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 10 Pro",
            brand = "google",
            device = "blazer",
            product = "blazer",
            hardware = "tensor_g5",
            board = "blazer",
            bootloader = "blazer-1.0-12345679",
            display = "BP1A.250105.002",
            host = "abfarm-release-rbe-65-00201"
        ),
        "Pixel 10 Pro XL" to DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 10 Pro XL",
            brand = "google",
            device = "mustang",
            product = "mustang",
            hardware = "tensor_g5",
            board = "mustang",
            bootloader = "mustang-1.0-12345680",
            display = "BP1A.250105.002",
            host = "abfarm-release-rbe-65-00202"
        ),
        "Pixel 10 Pro Fold" to DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 10 Pro Fold",
            brand = "google",
            device = "rango",
            product = "rango",
            hardware = "tensor_g5",
            board = "rango",
            bootloader = "rango-1.0-12345681",
            display = "BP1A.250105.002",
            host = "abfarm-release-rbe-65-00203"
        ),
        "Galaxy S23 Ultra" to DeviceInfo(
            manufacturer = "Samsung",
            model = "SM-S918B",
            brand = "samsung",
            device = "dm3q",
            product = "dm3qxx",
            hardware = "qcom",
            board = "kalama",
            bootloader = "S918BXXU3BWJM",
            display = "UP1A.231005.007.S918BXXU3BWJM",
            host = "21DH7R2P"
        ),
        "Galaxy S24 Ultra" to DeviceInfo(
            manufacturer = "Samsung",
            model = "SM-S928B",
            brand = "samsung",
            device = "e9q",
            product = "e9qxx",
            hardware = "qcom",
            board = "pineapple",
            bootloader = "S928BXXU1AXB5",
            display = "UP1A.231005.007.S928BXXU1AXB5",
            host = "21DH7R2P"
        ),
        "Galaxy S25 Ultra" to DeviceInfo(
            manufacturer = "Samsung",
            model = "SM-S938B",
            brand = "samsung",
            device = "e3q",
            product = "e3qxx",
            hardware = "qcom",
            board = "s5e9945",
            bootloader = "S938BXXU1AXL2",
            display = "UP1A.231005.007.S938BXXU1AXL2",
            host = "21DH7R2P"
        ),
        "OnePlus 15" to DeviceInfo(
            manufacturer = "OnePlus",
            model = "CPH2651",
            brand = "OnePlus",
            device = "OP5929L1",
            product = "OP5929L1_EEA",
            hardware = "qcom",
            board = "taro",
            bootloader = "unknown",
            display = "CPH2651_15.0.0.503(EX01)",
            host = "ubuntu-build"
        ),
        "OnePlus Open" to DeviceInfo(
            manufacturer = "OnePlus",
            model = "CPH2551",
            brand = "OnePlus",
            device = "OP594DL1",
            product = "OP594DL1_EEA",
            hardware = "qcom",
            board = "taro",
            bootloader = "unknown",
            display = "CPH2551_14.0.0.600(EX01)",
            host = "ubuntu-build"
        ),
        "Xiaomi 15 Ultra" to DeviceInfo(
            manufacturer = "Xiaomi",
            model = "25010PN30G",
            brand = "Xiaomi",
            device = "xuanyuan",
            product = "xuanyuan_global",
            hardware = "qcom",
            board = "taro",
            bootloader = "unknown",
            display = "VK.15.0.3.0.VNGMIXM",
            host = "c3-miui-ota-bd164.bj"
        ),
        "OPPO Find X9 Pro" to DeviceInfo(
            manufacturer = "OPPO",
            model = "PHY110",
            brand = "OPPO",
            device = "OP595DL1",
            product = "OP595DL1_EEA",
            hardware = "mt6989",
            board = "k6989v1_64",
            bootloader = "unknown",
            display = "PHY110_15.0.0.100(EX01)",
            host = "ubuntu-build-server"
        ),
        "vivo X100 Pro" to DeviceInfo(
            manufacturer = "vivo",
            model = "V2309A",
            brand = "vivo",
            device = "V2309A",
            product = "PD2309",
            hardware = "mt6989",
            board = "k6989v1_64",
            bootloader = "unknown",
            display = "OP557L.PD2309.14.0.0.100",
            host = "compiler-server"
        ),
        "realme GT 6" to DeviceInfo(
            manufacturer = "realme",
            model = "RMX3851",
            brand = "realme",
            device = "RMX3851",
            product = "RMX3851_11_A.13",
            hardware = "qcom",
            board = "taro",
            bootloader = "unknown",
            display = "RMX3851_14.0.0.700(EX01)",
            host = "ubuntu-server"
        )
    )

    fun getAvailableDevices(): List<String> = devices.keys.toList()

    fun getDeviceInfo(modelName: String): DeviceInfo? {
        return devices[modelName]
    }

    fun generateFingerprint(deviceInfo: DeviceInfo, buildVersion: String): String {
        val id = "AP3A.${System.currentTimeMillis().toString().take(6)}.005"
        val incremental = System.nanoTime().toString().take(8)
        return "${deviceInfo.brand}/${deviceInfo.product}/${deviceInfo.device}:$buildVersion/$id/$incremental:user/release-keys"
    }

    fun generateAndroidId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun androidIdToBytes(androidId: String): ByteArray {
        return try {
            val len = androidId.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(androidId[i], 16) shl 4) + Character.digit(androidId[i + 1], 16)).toByte()
                i += 2
            }
            data
        } catch (e: Exception) {
            ByteArray(8)
        }
    }
}
