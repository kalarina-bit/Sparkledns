package cc.skysparkle.sparkledns

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DohProvider(
    val id: String,
    val name: String,
    val url: String,
    val bootstrap: List<String> = emptyList(),
    val http3: Boolean = false,
    val policy: String? = null,
)

object Providers {
    const val CUSTOM_ID = "custom"

    // Shipped with the app and used until the list from the repository is downloaded
    val builtIn = listOf(
        DohProvider(
            "skysparkle", "SkySparkle", "https://blog.skysparkle.cc/dns-query",
            http3 = true, policy = "https://skysparkle.cc/privacy?lang=en",
        ),
        DohProvider(
            "quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"),
            policy = "https://quad9.net/service/privacy/",
        ),
        DohProvider(
            "digitale-gesellschaft", "Digitale Gesellschaft", "https://dns.digitale-gesellschaft.ch/dns-query",
            listOf("185.95.218.42", "185.95.218.43"), http3 = true,
            policy = "https://www.digitale-gesellschaft.ch/dns/",
        ),
        DohProvider(
            "uncensoreddns", "UncensoredDNS", "https://anycast.uncensoreddns.org/dns-query", listOf("91.239.100.100"),
            policy = "https://blog.uncensoreddns.org/faq/",
        ),
        DohProvider(
            "ffmuc", "Freifunk München", "https://doh.ffmuc.net/dns-query",
            listOf("5.1.66.255", "185.150.99.255"), http3 = true,
            policy = "https://ffmuc.net/dns-privacy/",
        ),
    )

    val list: List<DohProvider> get() = ServerList.servers.value

    val default: DohProvider get() = list.first()

    fun byId(id: String): DohProvider = list.firstOrNull { it.id == id } ?: default
}

// The server list kept in the project repository, so servers can change without an app update
object ServerList {
    const val SOURCE = "https://git.skysparkle.cc/kalarina/Sparkledns/raw/branch/main/servers/servers.txt"
    private const val FILE = "servers.txt"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_SERVERS = 50
    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")

    private val _servers = MutableStateFlow(Providers.builtIn)
    val servers: StateFlow<List<DohProvider>> = _servers

    @Volatile private var loaded = false

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val cached = runCatching { File(context.filesDir, FILE).readText() }.getOrNull()
            cached?.let(::parse)?.takeIf { it.isNotEmpty() }?.let { _servers.value = it }
            loaded = true
        }
    }

    // Blocking: call off the main thread. Returns the number of servers in the new list.
    fun sync(context: Context): Int {
        val request = Request.Builder().url(SOURCE).build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            if (body.contentLength() > MAX_BYTES) throw IOException("List too large")
            val bytes = body.byteStream().readNBytesCompat(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) throw IOException("List too large")
            String(bytes, Charsets.UTF_8)
        }
        val parsed = parse(text)
        if (parsed.isEmpty()) throw IOException("No servers found")
        val file = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot save list")
        }
        _servers.value = parsed
        loaded = true
        return parsed.size
    }

    // Format per line: id | name | DoH URL | bootstrap IPs | h3 | privacy policy URL
    fun parse(text: String): List<DohProvider> {
        val builtInById = Providers.builtIn.associateBy { it.id }
        val seen = HashSet<String>()
        val result = ArrayList<DohProvider>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split('|').map { it.trim() }
            if (cols.size < 3) continue
            val id = cols[0].lowercase()
            if (!ID.matches(id) || id == Providers.CUSTOM_ID || !seen.add(id)) continue
            // Shipped servers keep their address, so an edited list cannot redirect them
            val pinned = builtInById[id]
            if (pinned != null) {
                result += pinned
            } else {
                val name = cols[1].take(40)
                val url = cols[2]
                if (name.isEmpty() || url.toHttpUrlOrNull()?.isHttps != true) continue
                val bootstrap = parseIps(cols.getOrElse(3) { "" }).filter(::isIpLiteral)
                val http3 = cols.getOrElse(4) { "" }.equals("h3", ignoreCase = true)
                val policy = cols.getOrElse(5) { "" }.takeIf { it.toHttpUrlOrNull()?.isHttps == true }
                result += DohProvider(id, name, url, bootstrap, http3, policy)
            }
            if (result.size >= MAX_SERVERS) break
        }
        return result
    }

    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val IPV6 = Regex("[0-9a-fA-F:.]+")

    private fun isIpLiteral(value: String): Boolean =
        IPV4.matches(value) || (value.contains(':') && IPV6.matches(value))
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total))
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

fun parseIps(raw: String): List<String> =
    raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }

fun DohProvider.displayName(context: Context): String =
    if (id == Providers.CUSTOM_ID) context.getString(R.string.custom) else name
