package me.rhunk.snapenhance.manager.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl

// Cloudflare DNS-over-HTTPS endpoint
private const val CLOUDFLARE_DOH_URL = "https://cloudflare-dns.com/dns-query"

private val bootstrapDns = Dns.SYSTEM

val dohOkHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(
            DnsOverHttps.Builder()
                .client(OkHttpClient())
                .url(CLOUDFLARE_DOH_URL.toHttpUrl())
                .bootstrapDnsHosts(bootstrapDns.lookup("1.1.1.1"))
                .build()
        ).build()
}
