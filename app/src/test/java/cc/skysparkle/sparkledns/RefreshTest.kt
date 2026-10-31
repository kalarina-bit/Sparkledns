package cc.skysparkle.sparkledns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

// The screens refresh once a second; a refresh that brings the same object has nothing to update
class RefreshTest {

    @Test
    fun logSnapshotIsReusedUntilTheLogChanges() {
        QueryLog.clear()
        val empty = QueryLog.snapshot()
        assertSame(empty, QueryLog.snapshot())

        QueryLog.add(DnsMessage.Question("example.com", 1), QueryStatus.OK, 10)
        QueryLog.add(DnsMessage.Question("example.org", 28), QueryStatus.CACHED, 0)
        val filled = QueryLog.snapshot()

        assertNotSame(empty, filled)
        assertEquals(listOf("example.org", "example.com"), filled.map { it.domain })
        assertSame(filled, QueryLog.snapshot())

        QueryLog.clear()
        assertEquals(0, QueryLog.snapshot().size)
    }

    @Test
    fun summaryIsReusedUntilSomethingChanges() {
        val now = 1_000 * 3_600_000L
        val first = StatsStore.summary(24, now)
        assertSame(first, StatsStore.summary(24, now))
        assertNotSame(first, StatsStore.summary(24 * 7, now))

        StatsStore.record(QueryStatus.OK, 5, null, now)
        val after = StatsStore.summary(24, now)

        assertNotSame(first, after)
        assertEquals(first.total + 1, after.total)
        assertSame(after, StatsStore.summary(24, now))
    }
}
