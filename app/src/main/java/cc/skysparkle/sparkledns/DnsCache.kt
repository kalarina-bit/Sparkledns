package cc.skysparkle.sparkledns

import android.os.SystemClock

// TTL-aware LRU cache of DNS responses, keyed by the question section
class DnsCache(private val maxEntries: Int = 2000) {

    private class Entry(
        val response: ByteArray,
        val ttlOffsets: IntArray,
        val ttls: IntArray,
        val storedAt: Long,
        val expiresAt: Long,
    )

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    fun get(query: ByteArray): ByteArray? {
        val key = keyOf(query) ?: return null
        val now = SystemClock.elapsedRealtime()
        val entry = synchronized(map) {
            val e = map[key] ?: return null
            if (now >= e.expiresAt) {
                map.remove(key)
                return null
            }
            e
        }
        val out = entry.response.copyOf()
        out[0] = query[0]
        out[1] = query[1]
        val elapsed = ((now - entry.storedAt) / 1000).toInt()
        entry.ttlOffsets.forEachIndexed { i, off -> put32(out, off, maxOf(entry.ttls[i] - elapsed, 0)) }
        return out
    }

    fun put(query: ByteArray, response: ByteArray) {
        val key = keyOf(query) ?: return
        if (response.size < 12 || (response[2].toInt() and 0x02) != 0) return // truncated
        val rcode = response[3].toInt() and 0x0F
        if (rcode != 0 && rcode != 3) return // only NOERROR and NXDOMAIN

        val records = parseTtls(response) ?: return
        val minTtl = if (records.first.isEmpty()) NO_RECORDS_TTL else records.second.min()
        val ttl = minOf(minTtl, MAX_TTL)
        if (ttl <= 0) return

        val now = SystemClock.elapsedRealtime()
        val entry = Entry(response.copyOf(), records.first, records.second, now, now + ttl * 1000L)
        synchronized(map) { map[key] = entry }
    }

    // Offsets and values of every TTL field except the EDNS OPT pseudo-record
    private fun parseTtls(msg: ByteArray): Pair<IntArray, IntArray>? {
        var pos = 12
        repeat(u16(msg, 4)) {
            pos = skipName(msg, pos) ?: return null
            pos += 4
        }
        val total = u16(msg, 6) + u16(msg, 8) + u16(msg, 10)
        val offsets = ArrayList<Int>(total)
        val values = ArrayList<Int>(total)
        repeat(total) {
            pos = skipName(msg, pos) ?: return null
            if (pos + 10 > msg.size) return null
            val type = u16(msg, pos)
            val ttlOffset = pos + 4
            val rdLength = u16(msg, pos + 8)
            if (type != TYPE_OPT) {
                offsets += ttlOffset
                values += minOf(u32(msg, ttlOffset), Int.MAX_VALUE.toLong()).toInt()
            }
            pos += 10 + rdLength
            if (pos > msg.size) return null
        }
        return offsets.toIntArray() to values.toIntArray()
    }

    private fun keyOf(query: ByteArray): String? {
        if (query.size < 12 || u16(query, 4) != 1) return null
        val end = (skipName(query, 12) ?: return null) + 4
        if (end > query.size) return null
        val key = query.copyOfRange(12, end)
        // DNS names are case-insensitive; label length bytes never fall in A..Z
        for (i in key.indices) if (key[i] in 'A'.code.toByte()..'Z'.code.toByte()) key[i] = (key[i] + 32).toByte()
        return String(key, Charsets.ISO_8859_1)
    }

    private companion object {
        const val TYPE_OPT = 41
        const val MAX_TTL = 3600
        const val NO_RECORDS_TTL = 30
    }
}
