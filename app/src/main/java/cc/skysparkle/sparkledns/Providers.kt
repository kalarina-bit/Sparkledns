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
    const val DEFAULT_ID = "skysparkle"

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

    // The project's own server stays the default whatever order the list uses
    val default: DohProvider get() = list.firstOrNull { it.id == DEFAULT_ID } ?: list.first()

    fun byId(id: String): DohProvider = list.firstOrNull { it.id == id } ?: default
}

// The server list kept in the project repository, so servers can change without an app update
object ServerList {
    const val SOURCE = "https://git.skysparkle.cc/kalarina/Sparkledns/raw/branch/main/servers/servers.txt"
    private const val FILE = "servers.txt"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_SERVERS = 50

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

    // Format per line: Name | DoH URL | privacy policy URL | h3 (optional). See CONTRIBUTING.md.
    fun parse(text: String): List<DohProvider> {
        val builtInByHost = Providers.builtIn.associateBy { it.url.toHttpUrlOrNull()?.host }
        val seen = HashSet<String>()
        val result = ArrayList<DohProvider>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split('|').map { it.trim() }
            if (cols.size < 3) continue
            val name = cols[0].take(40)
            val url = cols[1].toHttpUrlOrNull()
            val policy = cols[2].takeIf { it.toHttpUrlOrNull()?.isHttps == true }
            if (name.isEmpty() || url == null || !url.isHttps) continue
            // Shipped servers keep their address and id, so an edited list cannot redirect them
            val pinned = builtInByHost[url.host]
            val server = pinned
                ?: if (policy == null) continue
                else DohProvider(url.host, name, cols[1], http3 = cols.getOrElse(3) { "" }.equals("h3", ignoreCase = true), policy = policy)
            if (!seen.add(server.id)) continue
            result += server
            if (result.size >= MAX_SERVERS) break
        }
        return result
    }
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
