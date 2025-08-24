package me.rhunk.snapenhance.manager.data

import me.rhunk.snapenhance.manager.data.cloudflareOkHttp
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object APKMirror {
    // Use Cloudflare OkHttp for ALL requests in this object
    private val okhttpClient: OkHttpClient = cloudflareOkHttp.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("User-Agent", System.getProperty("http.agent") ?: "SnapEnhanceManager/Android")
                    .build()
            )
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    // Helper: fetches and parses Snapchat versions list
    fun fetchSnapchatVersions(): List<String> {
        val url = "https://www.apkmirror.com/uploads/?appcategory=snapchat"
        val req = Request.Builder().url(url).build()
        val resp = okhttpClient.newCall(req).execute()
        if (!resp.isSuccessful) throw Exception("Failed to fetch Snapchat versions: ${resp.code}")
        val doc = Jsoup.parse(resp.body!!.string())
        return doc.select("div.listWidget div.appRow span.listAppTitle").map { it.text() }
    }

    // Fetches the direct download link for a Snapchat version from apkmirror
    fun fetchDownloadLink(versionPageUrl: String): String {
        // 1. Fetch version page
        val resp1 = okhttpClient.newCall(Request.Builder().url(versionPageUrl).build()).execute()
        if (!resp1.isSuccessful) throw Exception("Failed to fetch version page")
        val doc1 = Jsoup.parse(resp1.body!!.string())

        // 2. Get the "Download APK" button link
        val downloadButton = doc1.selectFirst("a[class*=downloadButton]")?.attr("href")
            ?: throw Exception("No Download button found")
        val downloadPageUrl = "https://www.apkmirror.com$downloadButton"

        // 3. Fetch download page, get the final link
        val resp2 = okhttpClient.newCall(Request.Builder().url(downloadPageUrl).build()).execute()
        if (!resp2.isSuccessful) throw Exception("Failed to fetch download page")
        val doc2 = Jsoup.parse(resp2.body!!.string())

        val finalLink = doc2.selectFirst("a[class=accent_bg btn btn-flat downloadButton]")?.attr("href")
            ?: throw Exception("No final download link found")
        // Sometimes this is a relative URL, make it absolute
        return "https://www.apkmirror.com${finalLink}"
    }

    // Downloads the APK via Cloudflare DNS
    fun downloadApk(apkUrl: String, output: File, progress: (Float) -> Unit) {
        val request = Request.Builder().url(apkUrl).build()
        val response = okhttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
        response.body!!.byteStream().use { input ->
            output.outputStream().use { outputStream ->
                val buffer = ByteArray(8192)
                var read: Int
                var total = 0L
                val contentLength = response.body!!.contentLength()
                while (input.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    total += read
                    if (contentLength > 0) progress(total / contentLength.toFloat())
                }
            }
        }
    }
}
