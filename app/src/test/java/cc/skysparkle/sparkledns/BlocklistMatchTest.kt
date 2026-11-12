package cc.skysparkle.sparkledns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.LongBuffer

class BlocklistMatchTest {

    private fun set(vararg domains: String) = domains.map { DomainHash.of(it) }.sorted().toLongArray()
    private fun buf(vararg domains: String) = LongBuffer.wrap(set(*domains))
    private fun buf(values: LongArray) = LongBuffer.wrap(values)

    @Test
    fun blocksDomainsAndTheirSubdomains() {
        val lists = buf("ads.example.com")
        val custom = set("tracker.net")

        assertTrue(isBlockedIn("ads.example.com", lists, custom))
        assertTrue(isBlockedIn("a.b.ads.example.com.", lists, custom))
        assertTrue(isBlockedIn("cdn.tracker.net", lists, custom))
        assertFalse(isBlockedIn("example.com", lists, custom))
        assertFalse(isBlockedIn("badads.example.com", lists, custom))
        assertFalse(isBlockedIn("tracker.network", lists, custom))
        assertFalse(isBlockedIn("ads.example.com", EMPTY_SET, LongArray(0)))
    }

    @Test
    fun skipsTrailingDots() {
        val lists = buf("example.com")

        assertTrue(isBlockedIn("www.example.com..", lists, LongArray(0)))
        assertFalse(isBlockedIn("example.org.", lists, LongArray(0)))
        assertFalse(isBlockedIn("...", lists, LongArray(0)))
    }

    @Test
    fun mergesSortedListsWithoutDuplicates() {
        val a = longArrayOf(-5, 1, 4, 9)
        val b = longArrayOf(1, 2, 9, 12)
        val c = longArrayOf(3)

        assertArrayEquals(longArrayOf(-5, 1, 2, 3, 4, 9, 12), mergeSorted(listOf(a, b, c, LongArray(0))))
        assertArrayEquals(LongArray(0), mergeSorted(emptyList()))
        assertArrayEquals(a, mergeSorted(listOf(a)))
    }

    @Test
    fun sortsInputsThatAreNotSorted() {
        assertArrayEquals(longArrayOf(1, 2, 3, 7), mergeSorted(listOf(longArrayOf(3, 1, 3), longArrayOf(7, 2))))
    }

    @Test
    fun countsEntriesInBothSetsOnce() {
        assertEquals(5, unionSize(buf(longArrayOf(1, 2, 3, 4)), longArrayOf(2, 5)))
        assertEquals(2, unionSize(EMPTY_SET, longArrayOf(2, 5)))
        assertEquals(4, unionSize(buf(longArrayOf(1, 2, 3, 4)), LongArray(0)))
    }

    @Test
    fun streamsMergedValuesInOrderOnce() {
        val out = ArrayList<Long>()
        val count = mergeSortedInto(
            listOf(buf(longArrayOf(-5, 1, 4, 9)), buf(longArrayOf(1, 2, 9, 12)), EMPTY_SET, buf(longArrayOf(12, 40)))
        ) { out.add(it) }

        assertEquals(listOf(-5L, 1, 2, 4, 9, 12, 40), out)
        assertEquals(7, count)
        assertEquals(0, mergeSortedInto(emptyList()) { out.add(it) })
    }

    @Test
    fun findsValuesInSortedBuffer() {
        val values = buf(longArrayOf(Long.MIN_VALUE, -3, 0, 7, Long.MAX_VALUE))
        listOf(Long.MIN_VALUE, -3L, 0L, 7L, Long.MAX_VALUE).forEach { assertTrue(values.containsSorted(it)) }
        listOf(-4L, 1L, 8L).forEach { assertFalse(values.containsSorted(it)) }
        assertFalse(EMPTY_SET.containsSorted(0))
    }
}
