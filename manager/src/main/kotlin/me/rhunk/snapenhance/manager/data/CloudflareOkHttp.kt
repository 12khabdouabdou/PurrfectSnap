import okhttp3.*
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import org.xbill.DNS.Record

// Add to your build.gradle:
// implementation("dnsjava:dnsjava:3.5.2")

object CloudflareDns : Dns {
    private val resolver = SimpleResolver("1.1.1.1")
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            val lookup = Lookup(hostname, Type.A)
            lookup.setResolver(resolver)
            val records: Array<Record>? = lookup.run()
            if (records != null) {
                records.map {
                    InetAddress.getByName(it.rdataToString())
                }
            } else {
                // fallback to default
                Dns.SYSTEM.lookup(hostname)
            }
        } catch (e: Exception) {
            throw UnknownHostException("Failed DNS lookup for $hostname via 1.1.1.1: $e")
        }
    }
}

// Use this OkHttpClient for snapchat downloads
val cloudflareOkHttp = OkHttpClient.Builder()
    .dns(CloudflareDns)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.MINUTES)
    .build()
