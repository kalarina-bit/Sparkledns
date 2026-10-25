package cc.skysparkle.sparkledns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    @Test
    fun questionIsReadBackLowercase() {
        val question = requireNotNull(DnsMessage.question(DnsMessage.buildQuery("WWW.Example.COM.", type = 28)))

        assertEquals("www.example.com", question.name)
        assertEquals(28, question.type)
    }

    @Test
    fun questionRejectsCompressedOrCutNames() {
        val query = DnsMessage.buildQuery("example.com")
        val compressed = query.copyOf(12) + byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1)

        assertNull(DnsMessage.question(compressed))
        assertNull(DnsMessage.question(query.copyOf(16)))
        assertNull(DnsMessage.question(query.copyOf(11)))
    }

    @Test
    fun withIdCopiesTheQueryId() {
        val query = DnsMessage.buildQuery("example.com")
        val response = dnsResponse(query)
        response[0] = (query[0] + 1).toByte()

        val fixed = DnsMessage.withId(response, query)

        assertEquals(query[0], fixed[0])
        assertEquals(query[1], fixed[1])
        assertSame(fixed, DnsMessage.withId(fixed, query))
    }

    @Test
    fun errorReplyKeepsOnlyTheQuestion() {
        val query = DnsMessage.buildQuery("example.com")
        // An EDNS OPT record in the additional section must not be echoed back
        val withOpt = query.copyOf().also { it[11] = 1 } + byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)

        val reply = requireNotNull(DnsMessage.errorReply(withOpt, DnsMessage.RCODE_NXDOMAIN))

        assertEquals(query.size, reply.size)
        assertTrue(DnsMessage.isResponse(reply))
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(reply))
        assertEquals(1, u16(reply, 4))
        assertArrayEquals(ByteArray(6), reply.copyOfRange(6, 12))
        assertArrayEquals(query.copyOfRange(12, query.size), reply.copyOfRange(12, reply.size))
    }

    @Test
    fun servfailAndResponseFlags() {
        val query = DnsMessage.buildQuery("example.com")

        assertFalse(DnsMessage.isResponse(query))
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(requireNotNull(DnsMessage.servfail(query))))
        assertNull(DnsMessage.servfail(ByteArray(5)))
        assertEquals(-1, DnsMessage.rcode(ByteArray(2)))
    }
}
