package cc.skysparkle.sparkledns

import java.io.ByteArrayOutputStream

// Hand-built DNS messages and IPv4/UDP packets for the tests

internal fun dnsResponse(
    query: ByteArray,
    rcode: Int = 0,
    ttls: List<Int> = listOf(300),
    withOpt: Boolean = false,
    truncated: Boolean = false,
): ByteArray {
    val questionEnd = requireNotNull(skipName(query, 12)) + 4
    val out = ByteArrayOutputStream()
    out.write(query, 0, 2)
    out.write(0x81 or if (truncated) 0x02 else 0) // QR + RD (+ TC)
    out.write(0x80 or rcode) // RA + RCODE
    out.write(byteArrayOf(0, 1, 0, ttls.size.toByte(), 0, 0, 0, (if (withOpt) 1 else 0).toByte()))
    out.write(query, 12, questionEnd - 12)
    for (ttl in ttls) {
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1))
        out.write(be32(ttl))
        out.write(byteArrayOf(0, 4, 93, 184.toByte(), 216.toByte(), 34))
    }
    if (withOpt) {
        // Root name, type OPT, UDP size 4096, extended flags with the DO bit, no data
        out.write(byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0x80.toByte(), 0, 0, 0))
    }
    return out.toByteArray()
}

internal fun ipv4Udp(
    src: ByteArray,
    dst: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
    protocol: Int = 17,
    flagsAndOffset: Int = 0,
): ByteArray {
    val p = ByteArray(28 + payload.size)
    p[0] = 0x45
    put16(p, 2, p.size)
    put16(p, 6, flagsAndOffset)
    p[8] = 64
    p[9] = protocol.toByte()
    src.copyInto(p, 12)
    dst.copyInto(p, 16)
    put16(p, 20, srcPort)
    put16(p, 22, dstPort)
    put16(p, 24, 8 + payload.size)
    payload.copyInto(p, 28)
    return p
}

// One's complement sum of 16-bit words; a correct checksum makes it 0xFFFF
internal fun onesSum(vararg parts: ByteArray): Int {
    var sum = 0L
    for (part in parts) {
        var i = 0
        while (i + 1 < part.size) {
            sum += u16(part, i)
            i += 2
        }
        if (i < part.size) sum += (part[i].toInt() and 0xFF) shl 8
    }
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return sum.toInt()
}

internal fun be32(value: Int) = ByteArray(4).also { put32(it, 0, value) }

// TTL field of the n-th answer of a message built by dnsResponse
internal fun answerTtl(message: ByteArray, index: Int = 0): Long {
    val first = requireNotNull(skipName(message, 12)) + 4
    return u32(message, first + index * 16 + 6)
}
