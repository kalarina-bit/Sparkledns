package cc.skysparkle.sparkledns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {

    private val client = byteArrayOf(10, 111, 222.toByte(), 1)
    private val server = byteArrayOf(10, 111, 222.toByte(), 2)

    @Test
    fun parsesDnsQuery() {
        val query = DnsMessage.buildQuery("example.com")
        val packet = ipv4Udp(client, server, 40000, 53, query)

        val parsed = requireNotNull(Packet.parseDnsQuery(packet, packet.size))

        assertArrayEquals(query, parsed.payload)
        assertEquals(i32(client, 0), parsed.srcIp)
        assertEquals(i32(server, 0), parsed.dstIp)
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
    }

    @Test
    fun usesOnlyTheBytesThatWereRead() {
        val query = DnsMessage.buildQuery("example.com")
        val packet = ipv4Udp(client, server, 40000, 53, query)
        val buffer = packet + ByteArray(100) { 0x55 }

        val parsed = requireNotNull(Packet.parseDnsQuery(buffer, packet.size))

        assertArrayEquals(query, parsed.payload)
    }

    @Test
    fun ignoresEverythingThatIsNotADnsQuery() {
        val query = DnsMessage.buildQuery("example.com")
        val otherPort = ipv4Udp(client, server, 40000, 443, query)
        val tcp = ipv4Udp(client, server, 40000, 53, query, protocol = 6)
        val fragment = ipv4Udp(client, server, 40000, 53, query, flagsAndOffset = 0x2000)
        val ipv6 = ipv4Udp(client, server, 40000, 53, query).also { it[0] = 0x60 }

        assertNull(Packet.parseDnsQuery(otherPort, otherPort.size))
        assertNull(Packet.parseDnsQuery(tcp, tcp.size))
        assertNull(Packet.parseDnsQuery(fragment, fragment.size))
        assertNull(Packet.parseDnsQuery(ipv6, ipv6.size))
        assertNull(Packet.parseDnsQuery(otherPort, 27))
    }

    @Test
    fun replySwapsEndpointsAndHasValidChecksums() {
        val query = DnsMessage.buildQuery("example.com")
        val packet = ipv4Udp(client, server, 40000, 53, query)
        val parsed = requireNotNull(Packet.parseDnsQuery(packet, packet.size))
        // Odd length exercises the checksum padding
        val answer = dnsResponse(query) + byteArrayOf(7)

        val reply = Packet.buildReply(parsed, answer)

        assertEquals(28 + answer.size, reply.size)
        assertEquals(0x45, reply[0].toInt())
        assertEquals(reply.size, u16(reply, 2))
        assertArrayEquals(server, reply.copyOfRange(12, 16))
        assertArrayEquals(client, reply.copyOfRange(16, 20))
        assertEquals(53, u16(reply, 20))
        assertEquals(40000, u16(reply, 22))
        assertArrayEquals(answer, reply.copyOfRange(28, reply.size))
        assertEquals(0xFFFF, onesSum(reply.copyOfRange(0, 20)))

        val udp = reply.copyOfRange(20, reply.size)
        val pseudo = reply.copyOfRange(12, 20) + byteArrayOf(0, 17) + byteArrayOf((udp.size shr 8).toByte(), udp.size.toByte())
        assertEquals(0xFFFF, onesSum(pseudo, udp))
    }
}
