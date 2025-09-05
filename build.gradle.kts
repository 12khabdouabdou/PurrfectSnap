// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.rust.android) apply false
}
var versionName = "2.1.0"
var versionCode = 210
rootProject.ext.set("appVersionName", versionName)
rootProject.ext.set("appVersionCode", versionCode)
rootProject.ext.set("applicationId", "me.rhunk.snapenhance")

// --- Use this for dynamic, in-sync hashed build (upstream style). ---
rootProject.ext.set(
    "buildHash",
    if (properties.containsKey("debug_build_hash"))
        properties["debug_build_hash"]
    else
        java.security.SecureRandom().nextLong(Long.MAX_VALUE / 1000L, Long.MAX_VALUE).toString(16)
)

// If you want a static library name everywhere (no hashes, safe if all code is manually updated):
// rootProject.ext.set("buildHash", "snapenhance_native")

tasks.register("getVersion") {
    doLast {
        val versionFile = File("app/build/version.txt")
        versionFile.parentFile.mkdirs()
        if (!versionFile.exists()) {
            versionFile.createNewFile()
        }
        versionFile.writeText(versionName)
    }
}
