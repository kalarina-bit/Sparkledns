package cc.skysparkle.sparkledns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsCacheTest {

    private var now = 1_000_000L
    private val cache = DnsCache(clock = { now })

    @Test
    fun answersWithTheNewQueryIdAndAgedTtl() {
        val first = DnsMessage.buildQuery("example.com")
        cache.put(first, dnsResponse(first, ttls = listOf(300)))
        now += 10_000

        val second = DnsMessage.buildQuery("EXAMPLE.com")
        val hit = requireNotNull(cache.get(second))

        assertEquals(second[0], hit[0])
        assertEquals(second[1], hit[1])
        assertEquals(290L, answerTtl(hit))
    }

    @Test
    fun expiresWithTheShortestTtl() {
        val query = DnsMessage.buildQuery("example.com")
        cache.put(query, dnsResponse(query, ttls = listOf(300, 60)))

        now += 59_000
        val hit = requireNotNull(cache.get(query))
        assertEquals(241L, answerTtl(hit, 0))
        assertEquals(1L, answerTtl(hit, 1))

        now += 1_000
        assertNull(cache.get(query))
    }

    @Test
    fun capsTtlAtOneHour() {
        val query = DnsMessage.buildQuery("example.com")
        cache.put(query, dnsResponse(query, ttls = listOf(86_400)))

        now += 3_599_000
        assertNotNull(cache.get(query))
        now += 1_000
        assertNull(cache.get(query))
    }

    @Test
    fun keepsEmptyNxdomainForThirtySeconds() {
        val query = DnsMessage.buildQuery("nothing.example")
        cache.put(query, dnsResponse(query, rcode = 3, ttls = emptyList()))

        now += 29_000
        assertEquals(3, DnsMessage.rcode(requireNotNull(cache.get(query))))
        now += 1_000
        assertNull(cache.get(query))
    }

    @Test
    fun skipsFailuresTruncatedAndZeroTtlAnswers() {
        val failed = DnsMessage.buildQuery("a.example")
        val truncated = DnsMessage.buildQuery("b.example")
        val zero = DnsMessage.buildQuery("c.example")

        cache.put(failed, dnsResponse(failed, rcode = 2))
        cache.put(truncated, dnsResponse(truncated, truncated = true))
        cache.put(zero, dnsResponse(zero, ttls = listOf(0)))

        assertNull(cache.get(failed))
        assertNull(cache.get(truncated))
        assertNull(cache.get(zero))
    }

    @Test
    fun leavesTheOptRecordAlone() {
        val query = DnsMessage.buildQuery("example.com")
        val response = dnsResponse(query, withOpt = true)
        cache.put(query, response)
        now += 10_000

        val hit = requireNotNull(cache.get(query))

        assertArrayEquals(response.copyOfRange(response.size - 11, response.size), hit.copyOfRange(hit.size - 11, hit.size))
    }

    @Test
    fun separatesRecordTypes() {
        val a = DnsMessage.buildQuery("example.com", type = 1)
        val aaaa = DnsMessage.buildQuery("example.com", type = 28)
        cache.put(a, dnsResponse(a))

        assertNull(cache.get(aaaa))
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntry() {
        val small = DnsCache(maxEntries = 2, clock = { now })
        val a = DnsMessage.buildQuery("a.example")
        val b = DnsMessage.buildQuery("b.example")
        val c = DnsMessage.buildQuery("c.example")
        small.put(a, dnsResponse(a))
        small.put(b, dnsResponse(b))
        small.get(a)
        small.put(c, dnsResponse(c))

        assertNotNull(small.get(a))
        assertNull(small.get(b))
        assertNotNull(small.get(c))
    }
}
