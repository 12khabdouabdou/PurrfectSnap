package me.rhunk.snapenhance.manager.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import org.xbill.DNS.Record
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object CloudflareDns : Dns {
    private val resolver = SimpleResolver("1.1.1.1")
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            val lookup = Lookup(hostname, Type.A)
            lookup.setResolver(resolver)
            val records = lookup.run()
            if (records != null) {
                records.map { InetAddress.getByName(it.rdataToString()) }
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        } catch (e: Exception) {
            throw UnknownHostException("Failed DNS lookup for $hostname via 1.1.1.1: $e")
        }
    }
}

val cloudflareOkHttp: OkHttpClient = OkHttpClient.Builder()
    .dns(CloudflareDns)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.MINUTES)
    .build()
