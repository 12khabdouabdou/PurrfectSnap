package me.rhunk.snapenhance.manager.data

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.UnknownHostException
import kotlin.math.absoluteValue

// Import DoH client
import me.rhunk.snapenhance.manager.data.dohOkHttp

@Parcelize
data class DownloadItem(
    val title: String,
    val releaseDate: String,
    val downloadPage: String
): Parcelable {
    @IgnoredOnParcel
    val shortTitle = title.substringBefore("(").trim()
    @IgnoredOnParcel
    val hash = (title + releaseDate + downloadPage).hashCode().absoluteValue.toString(16)
    @IgnoredOnParcel
    val isBeta = title.contains("Beta", ignoreCase = true)
}

class APKMirror {
    // Use Cloudflare DoH for ALL OkHttp requests (no direct InetAddress usage here!)
    val okhttpClient: OkHttpClient = dohOkHttp.newBuilder().addInterceptor {
        it.proceed(
            it.request().newBuilder()
                .addHeader("User-Agent", System.getProperty("http.agent")!!)
                .build()
        )
    }.build()

    companion object {
        private const val BASE_URL = "https://www.apkmirror.com"
        private const val FETCH_BUILD_URL =
            "$BASE_URL/apk/snap-inc/snapchat/variant-%7B%22arches_slug%22%3A%5B%22arm64-v8a%22%2C%22armeabi-v7a%22%5D%2C%22dpis_slug%22%3A%5B%22nodpi%22%5D%7D/page/{page}/"
    }

    fun fetchDownloadLink(downloadPageUri: String): String? {
        try {
            okhttpClient.newCall(
                Request.Builder()
                    .url("$BASE_URL$downloadPageUri")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val finalDownloadPageUri = Jsoup.parse(bodyString).getElementsByClass("downloadButton").first()?.attr("href")

                if (finalDownloadPageUri.isNullOrEmpty()) return null

                okhttpClient.newCall(
                    Request.Builder()
                        .url("$BASE_URL$finalDownloadPageUri")
                        .build()
                ).execute().use { response2 ->
                    if (!response2.isSuccessful) return null
                    val bodyString2 = response2.body?.string() ?: return null
                    val document = Jsoup.parse(bodyString2)
                    val downloadLink = document.getElementById("download-link")?.attr("href") ?: return null
                    return BASE_URL + downloadLink
                }
            }
        } catch (e: UnknownHostException) {
            throw DNSBlockedException(e)
        }
    }

    fun fetchSnapchatVersions(page: Int = 1): List<DownloadItem>? {
        try {
            val versions = mutableListOf<DownloadItem>()
            okhttpClient.newCall(
                Request.Builder()
                    .url(FETCH_BUILD_URL.replace("{page}", page.toString()))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val document = Jsoup.parse(bodyString)
                document.getElementById("primary")?.getElementsByClass("appRow")?.forEach { app ->
                    val title = app.getElementsByTag("h5").first()?.attr("title") ?: return@forEach
                    val releaseDate = app.getElementsByClass("dateyear_utc").attr("data-utcdate") ?: return@forEach
                    val downloadPage = app.getElementsByClass("downloadLink").first()?.attr("href") ?: return@forEach

                    versions.add(DownloadItem(title, releaseDate, downloadPage))
                }
            }
            return versions
        } catch (e: UnknownHostException) {
            throw DNSBlockedException(e)
        }
    }
}

// Custom exception so UI layer can show dialog specifically for DNS issues
class DNSBlockedException(e: Throwable): Exception(e)
