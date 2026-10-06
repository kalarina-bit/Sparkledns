package cc.skysparkle.sparkledns

import java.io.ByteArrayOutputStream
import kotlin.random.Random

class UdpPacket(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object Packet {

    // IPv4 total length is 16 bits: 65535 minus the 20-byte IP and 8-byte UDP headers
    const val MAX_UDP_PAYLOAD = 65507

    // Returns the DNS payload of an IPv4/UDP packet addressed to port 53, null for anything else
    fun parseDnsQuery(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 28 || ((buf[0].toInt() shr 4) and 0x0F) != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || buf[9].toInt() != 17 || len < ihl + 8) return null
        if ((u16(buf, 6) and 0x3FFF) != 0) return null // fragmented

        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return null
        val end = minOf(len, ihl + u16(buf, ihl + 4))
        if (end <= ihl + 8) return null

        return UdpPacket(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = dstPort,
            payload = buf.copyOfRange(ihl + 8, end),
        )
    }

    fun buildReply(query: UdpPacket, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)

        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40 // don't fragment
        p[8] = 64
        p[9] = 17
        query.dstIp.copyInto(p, 12)
        query.srcIp.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20, 0))

        put16(p, 20, query.dstPort)
        put16(p, 22, query.srcPort)
        put16(p, 24, udpLen)
        payload.copyInto(p, 28)

        val pseudo = sum16(p, 12, 8) + 17 + udpLen
        val udpSum = checksum(p, 20, udpLen, pseudo)
        put16(p, 26, if (udpSum == 0) 0xFFFF else udpSum)
        return p
    }

    private fun sum16(b: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial + sum16(b, off, len)
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

object DnsMessage {

    fun buildQuery(name: String, type: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0x10000)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // recursion desired
        out.write(0x00); out.write(0x01) // one question
        repeat(6) { out.write(0) }
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray()
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0x00); out.write(0x01) // class IN
        return out.toByteArray()
    }

    fun rcode(message: ByteArray): Int = if (message.size >= 4) message[3].toInt() and 0x0F else -1

    fun isResponse(message: ByteArray): Boolean = message.size >= 12 && (message[2].toInt() and 0x80) != 0

    // Copies the transaction ID of the query, so the client always matches the answer
    fun withId(response: ByteArray, query: ByteArray): ByteArray {
        if (response.size < 2 || query.size < 2) return response
        if (response[0] == query[0] && response[1] == query[1]) return response
        val out = response.copyOf()
        out[0] = query[0]
        out[1] = query[1]
        return out
    }

    // SERVFAIL lets the system resolver fail fast instead of waiting for a timeout
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    class Question(val name: String, val type: Int)

    fun question(msg: ByteArray): Question? {
        if (msg.size < 12 || u16(msg, 4) < 1) return null
        val name = StringBuilder()
        var p = 12
        while (true) {
            if (p >= msg.size) return null
            val len = msg[p].toInt() and 0xFF
            if (len == 0) { p++; break }
            if ((len and 0xC0) != 0 || p + 1 + len > msg.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in p + 1..p + len) name.append((msg[i].toInt() and 0xFF).toChar().lowercaseChar())
            p += len + 1
        }
        if (p + 2 > msg.size) return null
        return Question(name.toString(), u16(msg, p))
    }

    fun typeName(type: Int): String = when (type) {
        1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 6 -> "SOA"; 12 -> "PTR"; 15 -> "MX"; 16 -> "TXT"
        28 -> "AAAA"; 33 -> "SRV"; 64 -> "SVCB"; 65 -> "HTTPS"
        else -> "TYPE$type"
    }

    fun servfail(query: ByteArray): ByteArray? = errorReply(query, RCODE_SERVFAIL)

    fun errorReply(query: ByteArray, rcode: Int): ByteArray? {
        if (query.size < 12) return null
        var pos = 12
        repeat(u16(query, 4)) {
            pos = skipName(query, pos) ?: return null
            pos += 4
            if (pos > query.size) return null
        }
        val out = query.copyOf(pos)
        out[2] = (out[2].toInt() or 0x80).toByte()
        out[3] = (0x80 or rcode).toByte()
        for (i in 6 until 12) out[i] = 0
        return out
    }
}

internal fun u16(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

internal fun put16(b: ByteArray, off: Int, value: Int) {
    b[off] = (value shr 8).toByte()
    b[off + 1] = value.toByte()
}

internal fun put32(b: ByteArray, off: Int, value: Int) {
    b[off] = (value ushr 24).toByte()
    b[off + 1] = (value ushr 16).toByte()
    b[off + 2] = (value ushr 8).toByte()
    b[off + 3] = value.toByte()
}

internal fun u32(b: ByteArray, off: Int): Long =
    ((u16(b, off).toLong()) shl 16) or u16(b, off + 2).toLong()

// Returns the offset right after a (possibly compressed) DNS name
internal fun skipName(b: ByteArray, start: Int): Int? {
    var p = start
    while (p < b.size) {
        val len = b[p].toInt() and 0xFF
        if (len == 0) return p + 1
        if ((len and 0xC0) == 0xC0) return if (p + 2 <= b.size) p + 2 else null
        p += len + 1
    }
    return null
}
