package cc.skysparkle.sparkledns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class HourlyCountersTest {

    private val hours = HourlyCounters.HOURS

    @Test
    fun countsQueriesPerHour() {
        val counters = HourlyCounters()
        counters.record(QueryStatus.OK, 40, Company.GOOGLE, hour = 100)
        counters.record(QueryStatus.OK, 20, null, hour = 100)
        counters.record(QueryStatus.BLOCKED, 0, Company.META, hour = 100)
        counters.record(QueryStatus.CACHED, 0, null, hour = 99)
        counters.record(QueryStatus.FAILED, 5000, null, hour = 99)

        val day = counters.summary(24, currentHour = 100)
        assertEquals(5L, day.total)
        assertEquals(1L, day.blocked)
        assertEquals(1L, day.cached)
        assertEquals(30, day.avgLatencyMs)
        assertEquals(1L, day.companies[Company.GOOGLE.ordinal])
        assertEquals(1L, day.companies[Company.META.ordinal])
        assertEquals(3L, counters.summary(1, currentHour = 100).total)

        val points = counters.hourly(3, currentHour = 100)
        assertEquals(listOf(0L, 2L, 3L), points.map { it.total })
        assertEquals(listOf(0L, 0L, 1L), points.map { it.blocked })
    }

    @Test
    fun aSlotStartsOverAWeekLater() {
        val counters = HourlyCounters()
        counters.record(QueryStatus.OK, 10, null, hour = 5)
        counters.record(QueryStatus.OK, 10, null, hour = 5L + hours)

        assertEquals(1L, counters.summary(hours, currentHour = 5L + hours).total)
        assertEquals(0L, counters.summary(1, currentHour = 5).total)
    }

    @Test
    fun savedNumbersReadBackUnchanged() {
        val counters = HourlyCounters()
        counters.record(QueryStatus.BLOCKED, 0, Company.XIAOMI, hour = 1000)
        counters.record(QueryStatus.OK, 12, Company.APPLE, hour = 1001)

        val copy = requireNotNull(HourlyCounters.decode(counters.encode()))

        assertArrayEquals(counters.hourIds, copy.hourIds)
        for (index in 0 until hours) assertArrayEquals(counters.rows[index], copy.rows[index])
    }

    @Test
    fun writesFormatOne() {
        val bytes = HourlyCounters().encode()
        val input = DataInputStream(ByteArrayInputStream(bytes))

        assertEquals(1, input.readInt())
        assertEquals(HourlyCounters.FIELDS, input.readInt())
        assertEquals(hours, input.readInt())
        assertEquals(-1L, input.readLong())
        assertEquals(12 + hours * (HourlyCounters.FIELDS + 1) * 8, bytes.size)
    }

    @Test
    fun readsFilesFromVersionsWithFewerCompanies() {
        // A file from a version that knew two companies: field n of hour 7 holds n + 1
        val fields = HourlyCounters.FIRST_COMPANY + 2
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(fields)
            out.writeInt(hours)
            for (index in 0 until hours) {
                out.writeLong(if (index == 7) 7L else -1L)
                for (field in 0 until fields) out.writeLong(if (index == 7) field + 1L else 0L)
            }
        }

        val summary = requireNotNull(HourlyCounters.decode(bytes.toByteArray())).summary(1, currentHour = 7)

        assertEquals(1L, summary.total)
        assertEquals(2L, summary.blocked)
        assertEquals(3L, summary.cached)
        assertEquals(0, summary.avgLatencyMs) // latency sum 4, count 5
        assertArrayEquals(longArrayOf(6, 7) + LongArray(Company.entries.size - 2), summary.companies)
    }

    @Test
    fun refusesOtherFormats() {
        val bytes = HourlyCounters().encode()
        bytes[3] = 2

        assertNull(HourlyCounters.decode(bytes))
    }

    @Test
    fun mergeKeepsWhatWasCountedBeforeTheFileWasRead() {
        val saved = HourlyCounters()
        saved.record(QueryStatus.OK, 10, null, hour = 50)
        saved.record(QueryStatus.OK, 10, null, hour = 49)

        val live = HourlyCounters()
        live.record(QueryStatus.BLOCKED, 0, null, hour = 50)
        live.merge(saved)

        assertEquals(2L, live.summary(1, currentHour = 50).total)
        assertEquals(1L, live.summary(1, currentHour = 50).blocked)
        assertEquals(3L, live.summary(2, currentHour = 50).total)

        // A slot already holding a newer hour keeps it
        val later = HourlyCounters()
        later.record(QueryStatus.OK, 10, null, hour = 50L + hours)
        later.merge(saved)

        assertEquals(1L, later.summary(1, currentHour = 50L + hours).total)
    }
}
