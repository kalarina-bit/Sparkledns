package cc.skysparkle.sparkledns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistMatchTest {

    private fun set(vararg domains: String) = domains.map { DomainHash.of(it) }.sorted().toLongArray()

    @Test
    fun blocksDomainsAndTheirSubdomains() {
        val lists = set("ads.example.com")
        val custom = set("tracker.net")

        assertTrue(isBlockedIn("ads.example.com", lists, custom))
        assertTrue(isBlockedIn("a.b.ads.example.com.", lists, custom))
        assertTrue(isBlockedIn("cdn.tracker.net", lists, custom))
        assertFalse(isBlockedIn("example.com", lists, custom))
        assertFalse(isBlockedIn("badads.example.com", lists, custom))
        assertFalse(isBlockedIn("tracker.network", lists, custom))
        assertFalse(isBlockedIn("ads.example.com", LongArray(0), LongArray(0)))
    }

    @Test
    fun skipsTrailingDots() {
        val lists = set("example.com")

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
        assertEquals(5, unionSize(longArrayOf(1, 2, 3, 4), longArrayOf(2, 5)))
        assertEquals(2, unionSize(LongArray(0), longArrayOf(2, 5)))
        assertEquals(4, unionSize(longArrayOf(1, 2, 3, 4), LongArray(0)))
    }
}
