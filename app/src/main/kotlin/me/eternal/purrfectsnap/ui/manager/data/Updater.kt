package me.eternal.purrfectsnap.ui.manager.data

import com.google.gson.JsonParser
import me.eternal.purrfectsnap.common.BuildConfig
import me.eternal.purrfectsnap.common.logger.AbstractLogger
import okhttp3.OkHttpClient
import okhttp3.Request


object Updater {
    enum class Channel { STABLE, PRERELEASE }

    data class LatestRelease(
        val versionName: String,
        val releaseUrl: String,
        val workflowId: Long?,
        val body: String? = null,
        val assetDownloads: Map<String, String> = emptyMap(),
    )

    private fun isVersionGreater(version: String, current: String): Boolean {
        fun segments(raw: String) = raw
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }

        val v = segments(version)
        val c = segments(current)
        val size = maxOf(v.size, c.size)
        for (i in 0 until size) {
            val vi = v.getOrElse(i) { 0 }
            val ci = c.getOrElse(i) { 0 }
            if (vi > ci) return true
            if (vi < ci) return false
        }
        return false
    }

    private fun fetchLatestRelease(channel: Channel) = runCatching {
        val endpoint = Request.Builder().url("https://api.github.com/repos/particle-box/PurrfectSnap/releases").build()
        val response = OkHttpClient().newCall(endpoint).execute()
        val body = response.body?.string() ?: throw Throwable("Empty response body")

        if (!response.isSuccessful) throw Throwable("Failed to fetch releases: ${response.code}")

        val releases = JsonParser.parseString(body).asJsonArray.also {
            if (it.size() == 0) throw Throwable("No releases found")
        }

        val currentVersion = BuildConfig.VERSION_NAME
        val latestRelease = releases.mapNotNull { it.asJsonObject }.firstOrNull { release ->
            if (release.get("draft")?.asBoolean != false) return@firstOrNull false
            val matchesChannel = when (channel) {
                Channel.STABLE -> release.get("prerelease")?.asBoolean == false
                Channel.PRERELEASE -> release.get("prerelease")?.asBoolean == true
            }
            if (!matchesChannel) return@firstOrNull false
            val latestVersion = release.getAsJsonPrimitive("tag_name")?.asString?.removePrefix("v") ?: return@firstOrNull false
            isVersionGreater(latestVersion, currentVersion)
        } ?: throw Throwable("No matching releases found for $channel channel")

        val latestVersion = latestRelease.getAsJsonPrimitive("tag_name").asString.removePrefix("v")
        if (latestVersion == BuildConfig.VERSION_NAME) return@runCatching null
        val assets = latestRelease.getAsJsonArray("assets")?.mapNotNull { element ->
            val obj = element.asJsonObject
            val name = obj.getAsJsonPrimitive("name")?.asString?.lowercase() ?: return@mapNotNull null
            val url = obj.getAsJsonPrimitive("browser_download_url")?.asString ?: return@mapNotNull null
            name to url
        } ?: emptyList()

        val assetDownloads = buildMap<String, String> {
            assets.forEach { (name, url) ->
                when {
                    name.contains("arm64") || name.contains("armv8") -> put("arm64", url)
                    name.contains("armeabi") || name.contains("armv7") || name.contains("arm32") -> put("armv7", url)
                }
            }
        }

        LatestRelease(
            versionName = latestVersion,
            releaseUrl = latestRelease.getAsJsonPrimitive("html_url")?.asString
                ?: endpoint.url.toString().replace("api.", "").replace("repos/", ""),
            workflowId = null,
            body = latestRelease.get("body")?.asString,
            assetDownloads = assetDownloads
        )
    }.onFailure {
        AbstractLogger.directError("Failed to fetch latest release ($channel)", it)
    }.getOrNull()

    private fun fetchLatestDebugCI() = runCatching {
        val actionRuns = OkHttpClient().newCall(Request.Builder().url("https://api.github.com/repos/particle-box/PurrfectSnap/actions/runs?branch=dev&status=success").build()).execute().use {
            if (!it.isSuccessful) throw Throwable("Failed to fetch CI runs: ${it.code}")
            JsonParser.parseString(it.body?.string()).asJsonObject
        }
        val debugRuns = actionRuns.getAsJsonArray("workflow_runs")?.mapNotNull { it.asJsonObject }?.filter { run ->
            run.getAsJsonPrimitive("name")?.asString == "PurrfectSnap Debug CI"
        } ?: throw Throwable("No debug CI runs found")

        val latestRun = debugRuns.firstOrNull() ?: throw Throwable("No debug CI runs found")
        val headSha = latestRun.getAsJsonPrimitive("head_sha")?.asString ?: throw Throwable("No head sha found")

        if (headSha == BuildConfig.GIT_HASH) return@runCatching null

        LatestRelease(
            versionName = headSha.substring(0, headSha.length.coerceAtMost(7)) + "-debug",
            releaseUrl = latestRun.getAsJsonPrimitive("html_url")?.asString ?: return@runCatching null,
            workflowId = latestRun.getAsJsonPrimitive("id")?.asLong,
            body = latestRun.get("head_commit")?.asJsonObject?.get("message")?.asString
        )
    }.onFailure {
        AbstractLogger.directError("Failed to fetch latest debug CI", it)
    }.getOrNull()

    private val cache = java.util.concurrent.ConcurrentHashMap<Channel, Pair<Long, Result<LatestRelease?>>>()

    fun getLatestRelease(channel: Channel): LatestRelease? {
        val cached = cache[channel]
        val now = System.currentTimeMillis()
        
        if (cached != null) {
            val (timestamp, result) = cached
            // Define Cache TTL: 24 hours for any successful API response, 10 minutes for error
            val ttl = if (result.isSuccess) 24 * 60 * 60 * 1000L else 10 * 60 * 1000L
            if ((now - timestamp) < ttl) {
                return result.getOrNull()
            }
        }
        
        val result = runCatching {
            if (channel == Channel.PRERELEASE) {
                fetchLatestDebugCI() ?: fetchLatestRelease(channel)
            } else {
                fetchLatestRelease(channel)
            }
        }
        
        cache[channel] = now to result
        return result.getOrNull()
    }
}
