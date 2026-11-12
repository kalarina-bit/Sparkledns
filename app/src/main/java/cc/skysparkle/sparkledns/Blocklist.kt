package cc.skysparkle.sparkledns

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// A switched-off list keeps its file, so switching it on again needs no download
data class RemoteList(val url: String, val count: Int, val updated: Long, val enabled: Boolean = true)

// Blocks a domain together with all its subdomains. Changes apply instantly, no reconnect needed.
// Domains are kept as sorted 64-bit hashes in files that are memory-mapped, not read into the heap:
// lists with millions of entries cost page cache, not app memory, so low-end phones cope too.
object Blocklist {
    const val ALL_LISTS = "*"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e("SparkleDNS", "Blocklist task failed", e) }
    )
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

    // Downloaded lists and own domains are kept apart, so editing a few own domains
    // does not rebuild a set that can hold millions of entries
    @Volatile
    private var listHashes: LongBuffer = EMPTY_SET

    @Volatile
    private var customHashes = LongArray(0)
    private var loaded = false

    // False until the saved lists have been read at start-up
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    fun isBlocked(name: String): Boolean = _enabled.value && isBlockedIn(name, listHashes, customHashes)

    // Reads the saved lists unless something did already, such as the background update job
    fun ensureLoaded(context: Context) {
        synchronized(lock) { ensureLoaded(BlocklistStore(context)) }
    }

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
        updateCustomHashes()
        true
    }

    fun removeCustom(context: Context, domain: String) = synchronized(lock) {
        val store = BlocklistStore(context)
        ensureLoaded(store)
        val updated = _custom.value - domain
        store.saveCustom(updated)
        _custom.value = updated
        updateCustomHashes()
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

    fun setListEnabled(context: Context, url: String, value: Boolean) = synchronized(lock) {
        val store = BlocklistStore(context)
        ensureLoaded(store)
        val updated = _lists.value.map { if (it.url == url) it.copy(enabled = value) else it }
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
        val previous = _lists.value.firstOrNull { it.url == target }
        scope.launch {
            try {
                val list = store.download(target, previous)
                commit(store, listOf(list))
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = null
            }
        }
        return true
    }

    // Switched-off lists are skipped: nobody uses them until they are switched on again
    fun updateAll(context: Context): Boolean {
        val current = _lists.value.filter { it.enabled }
        if (current.isEmpty() || !_busy.compareAndSet(null, ALL_LISTS)) return false
        _error.value = null
        val store = BlocklistStore(context.applicationContext)
        scope.launch {
            try {
                val fresh = current.mapNotNull { list ->
                    try {
                        store.download(list.url, list)
                    } catch (e: Exception) {
                        if (_error.value == null) _error.value = e.message ?: e.javaClass.simpleName
                        null
                    }
                }
                commit(store, fresh)
            } catch (e: IOException) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = null
            }
        }
        return true
    }

    // Blocking, for the background update job. Returns false when a list could not be downloaded.
    fun updateAllNow(context: Context): Boolean {
        val store = BlocklistStore(context.applicationContext)
        synchronized(lock) { ensureLoaded(store) }
        val current = _lists.value.filter { it.enabled }
        // Nothing to do, or the user is updating right now
        if (current.isEmpty() || !_busy.compareAndSet(null, ALL_LISTS)) return true
        var ok = true
        try {
            val fresh = current.mapNotNull { list ->
                try {
                    store.download(list.url, list)
                } catch (e: Exception) {
                    Log.w("SparkleDNS", "Cannot update ${list.url}", e)
                    ok = false
                    null
                }
            }
            commit(store, fresh)
        } catch (e: IOException) {
            Log.w("SparkleDNS", "Cannot save updated lists", e)
            ok = false
        } finally {
            _busy.value = null
        }
        return ok
    }

    private fun commit(store: BlocklistStore, fresh: List<RemoteList>) = synchronized(lock) {
        ensureLoaded(store)
        val byUrl = fresh.associateBy { it.url }
        val kept = _lists.value.map { old -> byUrl[old.url]?.copy(enabled = old.enabled) ?: old }
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
        val custom = _custom.value
        customHashes = LongArray(custom.size) { DomainHash.of(custom[it]) }.sortUnique()
        reloadListHashes(store)
        loaded = true
        _ready.value = true
    }

    private fun reloadListHashes(store: BlocklistStore) {
        try {
            listHashes = store.mergedHashes(_lists.value.filter { it.enabled })
        } catch (e: IOException) {
            // Out of storage: the lists in use stay active until the next change
            Log.w("SparkleDNS", "Cannot merge blocklists", e)
        }
        _total.value = unionSize(listHashes, customHashes)
    }

    private fun updateCustomHashes() {
        val custom = _custom.value
        customHashes = LongArray(custom.size) { DomainHash.of(custom[it]) }.sortUnique()
        _total.value = unionSize(listHashes, customHashes)
    }
}

// True when the name or one of its parent domains is in either sorted hash set. Runs for every
// query, so trailing dots are skipped by index rather than trimmed off a copy of the name.
internal fun isBlockedIn(name: String, lists: LongBuffer, custom: LongArray): Boolean {
    if (lists.limit() == 0 && custom.isEmpty()) return false
    val end = nameEnd(name)
    var start = 0
    while (true) {
        val hash = DomainHash.of(name, start, end)
        if (lists.containsSorted(hash) || custom.binarySearch(hash) >= 0) return true
        val dot = name.indexOf('.', start)
        if (dot < 0 || dot >= end) return false
        start = dot + 1
    }
}

// Length of the name without trailing dots
internal fun nameEnd(name: String): Int {
    var end = name.length
    while (end > 0 && name[end - 1] == '.') end--
    return end
}

internal val EMPTY_SET: LongBuffer = LongBuffer.wrap(LongArray(0))

// Binary search by absolute index, so several threads can read the same buffer at once
internal fun LongBuffer.containsSorted(value: Long): Boolean {
    var low = 0
    var high = limit() - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val v = get(mid)
        when {
            v < value -> low = mid + 1
            v > value -> high = mid - 1
            else -> return true
        }
    }
    return false
}

// Number of distinct entries in both sorted sets; the custom set is small
internal fun unionSize(lists: LongBuffer, custom: LongArray): Int =
    lists.limit() + custom.count { !lists.containsSorted(it) }

// Merges sorted, duplicate-free sets in one pass and hands each value to [emit] once, in order.
// Only the read positions are kept in memory, so inputs and output can be files of any size.
internal fun mergeSortedInto(parts: List<LongBuffer>, emit: (Long) -> Unit): Int {
    val sets = parts.filter { it.limit() > 0 }
    val pos = IntArray(sets.size)
    var count = 0
    while (true) {
        var min = 0L
        var found = false
        for (i in sets.indices) {
            if (pos[i] < sets[i].limit()) {
                val v = sets[i].get(pos[i])
                if (!found || v < min) {
                    min = v
                    found = true
                }
            }
        }
        if (!found) return count
        for (i in sets.indices) if (pos[i] < sets[i].limit() && sets[i].get(pos[i]) == min) pos[i]++
        emit(min)
        count++
    }
}

// In-memory variant for small sets; inputs that are not sorted yet are sorted first
internal fun mergeSorted(parts: List<LongArray>): LongArray {
    val sorted = parts.map { LongBuffer.wrap(if (it.isSortedUnique()) it else it.copyOf().sortUnique()) }
    val out = LongList()
    mergeSortedInto(sorted) { out.add(it) }
    return out.toArray()
}

private fun LongArray.isSortedUnique(): Boolean {
    for (i in 1 until size) if (this[i - 1] >= this[i]) return false
    return true
}

internal object DomainHash {
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    // FNV-1a over the characters from [start] to [end], so parent domains are hashed without substrings
    fun of(s: String, start: Int = 0, end: Int = s.length): Long {
        var h = FNV_OFFSET
        for (i in start until end) {
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

    // Sorts the filled part in place and drops duplicates, without copying; returns the new size
    fun sortUnique(): Int {
        if (size < 2) return size
        java.util.Arrays.sort(data, 0, size)
        var n = 1
        for (i in 1 until size) if (data[i] != data[n - 1]) data[n++] = data[i]
        size = n
        return n
    }

    fun writeTo(out: DataOutputStream) {
        for (i in 0 until size) out.writeLong(data[i])
    }
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
    private val appContext = context.applicationContext

    // Created on first use: switching blocking on or off, which happens on the main thread,
    // touches only the settings
    private val dir by lazy { File(appContext.filesDir, "blocklist").apply { mkdirs() } }
    private val sp = appContext.getSharedPreferences("blocklist", Context.MODE_PRIVATE)

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
                RemoteList(o.getString("url"), o.getInt("count"), o.getLong("updated"), o.getBoolean("enabled"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveLists(lists: List<RemoteList>) {
        val array = JSONArray()
        lists.forEach {
            array.put(
                JSONObject().put("url", it.url).put("count", it.count).put("updated", it.updated).put("enabled", it.enabled)
            )
        }
        sp.edit().putString("lists", array.toString()).commit()
    }

    fun loadListHashes(url: String): LongBuffer = map(binFile(url)) ?: EMPTY_SET

    // One list is used as it is; several are merged into one file, which is reused at the next
    // start as long as the switched-on lists have not changed
    fun mergedHashes(lists: List<RemoteList>): LongBuffer {
        if (lists.size < 2) {
            mergedFile.delete()
            return lists.firstOrNull()?.let { loadListHashes(it.url) } ?: EMPTY_SET
        }
        val signature = hash(lists.sortedBy { it.url }.joinToString("\n") { "${it.url} ${it.count} ${it.updated}" })
        if (sp.getString(MERGED_KEY, null) == signature) map(mergedFile)?.let { return it }

        val parts = lists.map { loadListHashes(it.url) }
        val tmp = File.createTempFile("merged_", ".tmp", dir)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                mergeSortedInto(parts) { out.writeLong(it) }
            }
            if (!tmp.renameTo(mergedFile)) throw IOException("Cannot save lists")
        } finally {
            tmp.delete()
        }
        sp.edit().putString(MERGED_KEY, signature).commit()
        return map(mergedFile) ?: EMPTY_SET
    }

    // The mapping stays valid after the file is replaced or deleted, so readers are never cut off
    private fun map(file: File): LongBuffer? {
        if (!file.exists()) return null
        return runCatching {
            FileInputStream(file).use { stream ->
                val channel = stream.channel
                val bytes = channel.size() / 8 * 8
                if (bytes == 0L) EMPTY_SET else channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes).asLongBuffer()
            }
        }.getOrNull()
    }

    fun deleteListFile(url: String) {
        binFile(url).delete()
        sp.edit().remove(etagKey(url)).remove(modifiedKey(url)).apply()
    }

    // With the list already on disk the server is asked for a changed one only, so updating
    // a list that did not change costs a few hundred bytes instead of megabytes
    fun download(url: String, previous: RemoteList? = null): RemoteList {
        val builder = runCatching { Request.Builder().url(url) }.getOrElse { throw IOException("Invalid URL") }
        if (previous != null && binFile(url).exists()) {
            sp.getString(etagKey(url), null)?.let { builder.header("If-None-Match", it) }
            sp.getString(modifiedKey(url), null)?.let { builder.header("If-Modified-Since", it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 304 && previous != null) return previous.copy(updated = System.currentTimeMillis())
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val parsed = LongList()
            BlocklistParser.readLimitedLines(body.source()) { line ->
                val domain = BlocklistParser.parseLine(line) ?: return@readLimitedLines
                // Far above the largest common lists; keeps a wrong URL from exhausting memory
                if (parsed.size >= MAX_LIST_DOMAINS) throw IOException("List too large")
                parsed.add(DomainHash.of(domain))
            }
            // Sorted inside the download buffer: a list with millions of entries is never copied
            val count = parsed.sortUnique()
            if (count == 0) throw IOException("No domains found")
            write(url, parsed)
            sp.edit()
                .putOrRemove(etagKey(url), response.header("ETag"))
                .putOrRemove(modifiedKey(url), response.header("Last-Modified"))
                .apply()
            return RemoteList(url, count, System.currentTimeMillis())
        }
    }

    private fun write(url: String, data: LongList) {
        val tmp = File.createTempFile("list_", ".tmp", dir)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                data.writeTo(out)
            }
            if (!tmp.renameTo(binFile(url))) throw IOException("Cannot save list")
        } finally {
            tmp.delete()
        }
    }

    private fun etagKey(url: String) = "etag_${hash(url)}"
    private fun modifiedKey(url: String) = "modified_${hash(url)}"

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private fun binFile(url: String) = File(dir, "list_${hash(url)}.bin")
    private val mergedFile get() = File(dir, "merged.bin")

    private fun hash(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_LIST_DOMAINS = 4_000_000

        private const val MERGED_KEY = "merged"

        val client: OkHttpClient by lazy {
            Http.client.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

// Understands hosts files, plain domain lists, AdBlock rules (||domain^) and dnsmasq / unbound syntax
object BlocklistParser {
    /** A list larger than this is refused; the biggest common lists are well below it. */
    const val MAX_LIST_BYTES = 256L * 1024 * 1024

    /** Longer lines (no blocklist entry is that long) are skipped, not read into memory. */
    const val MAX_LINE_BYTES = 4096L

    /**
     * Reads [source] line by line within [MAX_LIST_BYTES] in total and [MAX_LINE_BYTES] per
     * line, so a wrong URL (a binary file, a page with no line breaks) cannot fill the memory.
     */
    fun readLimitedLines(source: BufferedSource, onLine: (String) -> Unit) {
        var total = 0L
        fun consumed(bytes: Long) {
            total += bytes
            if (total > MAX_LIST_BYTES) throw IOException("List too large")
        }
        while (true) {
            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES + 1)
            when {
                newline >= 0 -> {
                    onLine(source.readUtf8(newline))
                    source.skip(1)
                    consumed(newline + 1)
                }
                source.exhausted() -> return
                source.request(MAX_LINE_BYTES + 1) -> {
                    // Overlong line: drop it piece by piece up to its end
                    while (true) {
                        // Only what is already buffered: the next line may start right after the break
                        val end = source.buffer.indexOf('\n'.code.toByte())
                        if (end >= 0) {
                            source.skip(end + 1)
                            consumed(end + 1)
                            break
                        }
                        val chunk = source.buffer.size
                        if (chunk == 0L) return
                        source.skip(chunk)
                        consumed(chunk)
                        if (!source.request(1)) return
                    }
                }
                else -> {
                    // Last line without a line break
                    val rest = source.buffer.size
                    onLine(source.readUtf8())
                    consumed(rest)
                    return
                }
            }
        }
    }

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
            // ||example.com/ads/ blocks one path in a browser; as DNS it would block the whole site
            if (end >= 0 && line[end] == '/' && hasPath(line.substring(end))) return null
            return normalize(if (end >= 0) line.substring(2, end) else line.substring(2))
        }
        if (DNSMASQ.any { line.startsWith(it) }) return normalize(line.substringAfter("=/").substringBefore('/'))
        if (line.startsWith("local-zone:")) return normalize(line.substringAfter('"').substringBefore('"'))
        if (line.startsWith("@@") || "##" in line || "#@#" in line || "#?#" in line) return null

        val parts = line.substringBefore('#').trim().split(WHITESPACE)
        val candidate = if (parts.size >= 2 && looksLikeIp(parts[0])) parts[1] else parts[0]
        if (candidate.lowercase() in IGNORED) return null
        // A URL with a path (|http://example.com/x|) stands for one page, not for the domain
        val afterScheme = candidate.substringAfter("://")
        val slash = afterScheme.indexOf('/')
        if (slash >= 0 && hasPath(afterScheme.substring(slash))) return null
        return normalize(candidate)
    }

    // "/", "/^" or "/|" only end the host; anything longer is a path
    private fun hasPath(fromSlash: String): Boolean =
        fromSlash.trimEnd('^', '|').length > 1

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
