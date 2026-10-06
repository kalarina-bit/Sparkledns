package cc.skysparkle.sparkledns

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class RemoteList(val url: String, val count: Int, val updated: Long)

// Blocks a domain together with all its subdomains. Changes apply instantly, no reconnect needed.
// Domains are kept as sorted 64-bit hashes: about 8 bytes per entry, so million-entry lists fit easily.
object Blocklist {
    const val ALL_LISTS = "*"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private val _custom = MutableStateFlow<List<String>>(emptyList())
    val custom: StateFlow<List<String>> = _custom

    private val _lists = MutableStateFlow<List<RemoteList>>(emptyList())
    val lists: StateFlow<List<RemoteList>> = _lists

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total

    // URL being downloaded, ALL_LISTS while updating everything, null when idle
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    // Reason of the last failed download, null after a success
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile
    private var hashes = LongArray(0)
    private var listHashes = LongArray(0)
    private var loaded = false

    fun isBlocked(name: String): Boolean {
        if (!_enabled.value) return false
        val set = hashes
        if (set.isEmpty()) return false
        val domain = name.trimEnd('.')
        var start = 0
        while (true) {
            if (set.binarySearch(DomainHash.of(domain, start)) >= 0) return true
            val dot = domain.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
    }

    fun reload(context: Context) = synchronized(lock) { load(BlocklistStore(context)) }

    fun setEnabled(context: Context, value: Boolean) {
        BlocklistStore(context).enabled = value
        _enabled.value = value
    }

    fun addCustom(context: Context, input: String): Boolean = synchronized(lock) {
        val domain = BlocklistParser.normalize(input) ?: return false
        val store = BlocklistStore(context)
        ensureLoaded(store)
        val updated = (_custom.value + domain).distinct().sorted()
        store.saveCustom(updated)
        _custom.value = updated
        combine()
        true
    }

    fun removeCustom(context: Context, domain: String) = synchronized(lock) {
        val store = BlocklistStore(context)
        ensureLoaded(store)
        val updated = _custom.value - domain
        store.saveCustom(updated)
        _custom.value = updated
        combine()
    }

    fun removeList(context: Context, url: String) = synchronized(lock) {
        val store = BlocklistStore(context)
        ensureLoaded(store)
        store.deleteListFile(url)
        val updated = _lists.value.filter { it.url != url }
        store.saveLists(updated)
        _lists.value = updated
        reloadListHashes(store)
    }

    // Downloads run in the background, outside the lock, so editing own domains never waits for them.
    // Returns false when another download is already running.
    fun download(context: Context, url: String): Boolean {
        val target = url.trim()
        if (target.isEmpty() || !_busy.compareAndSet(null, target)) return false
        _error.value = null
        val store = BlocklistStore(context.applicationContext)
        scope.launch {
            try {
                val list = store.download(target)
                commit(store, listOf(list))
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = null
            }
        }
        return true
    }

    fun updateAll(context: Context): Boolean {
        if (_lists.value.isEmpty() || !_busy.compareAndSet(null, ALL_LISTS)) return false
        _error.value = null
        val store = BlocklistStore(context.applicationContext)
        val urls = _lists.value.map { it.url }
        scope.launch {
            try {
                val fresh = urls.mapNotNull { url ->
                    try {
                        store.download(url)
                    } catch (e: Exception) {
                        if (_error.value == null) _error.value = e.message ?: e.javaClass.simpleName
                        null
                    }
                }
                commit(store, fresh)
            } finally {
                _busy.value = null
            }
        }
        return true
    }

    private fun commit(store: BlocklistStore, fresh: List<RemoteList>) = synchronized(lock) {
        ensureLoaded(store)
        val byUrl = fresh.associateBy { it.url }
        val kept = _lists.value.map { byUrl[it.url] ?: it }
        val added = fresh.filter { new -> kept.none { it.url == new.url } }
        val updated = kept + added
        store.saveLists(updated)
        _lists.value = updated
        reloadListHashes(store)
    }

    private fun ensureLoaded(store: BlocklistStore) {
        if (!loaded) load(store)
    }

    private fun load(store: BlocklistStore) {
        _enabled.value = store.enabled
        _custom.value = store.loadCustom()
        _lists.value = store.loadLists()
        reloadListHashes(store)
        loaded = true
    }

    private fun reloadListHashes(store: BlocklistStore) {
        listHashes = merge(_lists.value.map { store.loadListHashes(it.url) })
        combine()
    }

    private fun combine() {
        val custom = _custom.value
        hashes = if (custom.isEmpty()) {
            listHashes
        } else {
            merge(listOf(listHashes, LongArray(custom.size) { DomainHash.of(custom[it]) }))
        }
        _total.value = hashes.size
    }

    private fun merge(parts: List<LongArray>): LongArray {
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.size <= 1) return nonEmpty.firstOrNull() ?: LongArray(0) // stored lists are already sorted
        val all = LongArray(nonEmpty.sumOf { it.size })
        var pos = 0
        nonEmpty.forEach { it.copyInto(all, pos); pos += it.size }
        return all.sortUnique()
    }
}

internal object DomainHash {
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    // FNV-1a over the domain starting at [start], so parent domains are hashed without substrings
    fun of(s: String, start: Int = 0): Long {
        var h = FNV_OFFSET
        for (i in start until s.length) {
            h = h xor s[i].code.toLong()
            h *= FNV_PRIME
        }
        return h
    }
}

private class LongList {
    private var data = LongArray(4096)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray(): LongArray = data.copyOf(size)
}

// Sorts in place and drops duplicates
private fun LongArray.sortUnique(): LongArray {
    if (size < 2) return this
    sort()
    var n = 1
    for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
    return if (n == size) this else copyOf(n)
}

private class BlocklistStore(context: Context) {
    private val dir = File(context.filesDir, "blocklist").apply { mkdirs() }
    private val sp = context.getSharedPreferences("blocklist", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    fun loadCustom(): List<String> {
        val file = File(dir, "custom.txt")
        return if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    fun saveCustom(domains: List<String>) {
        val tmp = File(dir, "custom.txt.tmp")
        tmp.writeText(domains.joinToString("\n"))
        if (!tmp.renameTo(File(dir, "custom.txt"))) throw IOException("Cannot save domains")
    }

    fun loadLists(): List<RemoteList> {
        val raw = sp.getString("lists", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                RemoteList(o.getString("url"), o.getInt("count"), o.getLong("updated"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveLists(lists: List<RemoteList>) {
        val array = JSONArray()
        lists.forEach {
            array.put(JSONObject().put("url", it.url).put("count", it.count).put("updated", it.updated))
        }
        sp.edit().putString("lists", array.toString()).commit()
    }

    fun loadListHashes(url: String): LongArray {
        val file = binFile(url)
        if (!file.exists()) migrateText(url)?.let { return it }
        if (!file.exists()) return LongArray(0)
        return runCatching {
            FileInputStream(file).use { stream ->
                val channel = stream.channel
                val result = LongArray((channel.size() / 8).toInt())
                channel.map(FileChannel.MapMode.READ_ONLY, 0, result.size * 8L).asLongBuffer().get(result)
                result
            }
        }.getOrDefault(LongArray(0))
    }

    fun deleteListFile(url: String) {
        binFile(url).delete()
        textFile(url).delete()
    }

    fun download(url: String): RemoteList {
        val request = runCatching { Request.Builder().url(url).build() }
            .getOrElse { throw IOException("Invalid URL") }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val parsed = LongList()
            body.charStream().buffered().useLines { lines ->
                lines.forEach { line -> BlocklistParser.parseLine(line)?.let { parsed.add(DomainHash.of(it)) } }
            }
            val result = parsed.toArray().sortUnique()
            if (result.isEmpty()) throw IOException("No domains found")
            write(url, result)
            return RemoteList(url, result.size, System.currentTimeMillis())
        }
    }

    private fun write(url: String, data: LongArray) {
        val tmp = File.createTempFile("list_", ".tmp", dir)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                data.forEach { out.writeLong(it) }
            }
            if (!tmp.renameTo(binFile(url))) throw IOException("Cannot save list")
        } finally {
            tmp.delete()
        }
    }

    // Lists saved by older versions as plain text are converted once
    private fun migrateText(url: String): LongArray? {
        val text = textFile(url)
        if (!text.exists()) return null
        val result = text.readLines().map { DomainHash.of(it) }.toLongArray().sortUnique()
        runCatching { write(url, result) }
        text.delete()
        return result
    }

    private fun binFile(url: String) = File(dir, "list_${hash(url)}.bin")
    private fun textFile(url: String) = File(dir, "list_${hash(url)}.txt")

    private fun hash(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    companion object {
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

// Understands hosts files, plain domain lists, AdBlock rules (||domain^) and dnsmasq / unbound syntax
object BlocklistParser {
    private val WHITESPACE = Regex("\\s+")
    private val IGNORED = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )
    private val DNSMASQ = listOf("address=/", "server=/", "local=/")

    fun parseLine(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return null

        // Fast path for AdBlock-style lists such as HaGeZi: ||domain^
        if (line.startsWith("||")) {
            val end = line.indexOfAny(charArrayOf('^', '$', '/'), 2)
            return normalize(if (end >= 0) line.substring(2, end) else line.substring(2))
        }
        if (DNSMASQ.any { line.startsWith(it) }) return normalize(line.substringAfter("=/").substringBefore('/'))
        if (line.startsWith("local-zone:")) return normalize(line.substringAfter('"').substringBefore('"'))
        if (line.startsWith("@@") || "##" in line || "#@#" in line || "#?#" in line) return null

        val parts = line.substringBefore('#').trim().split(WHITESPACE)
        val candidate = if (parts.size >= 2 && looksLikeIp(parts[0])) parts[1] else parts[0]
        if (candidate.lowercase() in IGNORED) return null
        return normalize(candidate)
    }

    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore(':')
        s = s.removePrefix("||").removeSuffix("^").removePrefix("*.").removePrefix(".").trimEnd('.')
        return s.takeIf { isValidDomain(it) && !looksLikeIp(it) }
    }

    private fun isValidDomain(s: String): Boolean {
        if (s.isEmpty() || s.length > 253 || s[0] == '.' || s[0] == '-' || s.last() == '.') return false
        var hasDot = false
        var prev = ' '
        for (c in s) {
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.'
            if (!ok) return false
            if (c == '.') {
                if (prev == '.') return false
                hasDot = true
            }
            prev = c
        }
        return hasDot
    }

    private fun looksLikeIp(s: String): Boolean =
        ':' in s || s.all { it.isDigit() || it == '.' }
}
