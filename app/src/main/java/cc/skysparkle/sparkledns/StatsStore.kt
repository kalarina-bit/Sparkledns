package cc.skysparkle.sparkledns

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

class StatsSummary(
    val total: Long,
    val blocked: Long,
    val cached: Long,
    val avgLatencyMs: Int,
    // Requests per company, indexed by Company.ordinal
    val companies: LongArray,
)

class HourPoint(val total: Long, val blocked: Long)

// Hourly counters of the last 7 days for the charts. Only numbers are kept, never domains,
// and they never leave the device.
object StatsStore {
    private const val HOUR_MS = 3_600_000L
    private const val FILE = "stats.bin"
    private const val SAVE_INTERVAL_MS = 60_000L

    private val counters = HourlyCounters()
    private var file: File? = null

    // Bumped when the numbers change without a new query: loaded from disk
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var lastSave = 0L
    private var dirty = false

    // Counts every change, so summary() can hand out its last result while nothing changed
    private var changes = 0L
    private var lastSummary: StatsSummary? = null
    private var summaryChanges = 0L
    private var summaryHours = 0
    private var summaryHour = 0L

    // Saves happen off the VPN threads, one at a time
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "stats-writer").apply { isDaemon = true } }

    // The file is read outside the lock, so neither the DNS threads nor the screens wait for
    // storage. Queries counted before it was read are added to the saved numbers.
    fun init(context: Context) {
        val target = File(context.filesDir, FILE)
        val saved = read(target)
        synchronized(this) {
            file = target
            if (saved != null) counters.merge(saved)
            changes++
        }
        _version.value++
    }

    fun record(status: QueryStatus, latencyMs: Int, company: Company?, now: Long = System.currentTimeMillis()) {
        val save = synchronized(this) {
            counters.record(status, latencyMs, company, now / HOUR_MS)
            changes++
            dirty = true
            if (file != null && now - lastSave >= SAVE_INTERVAL_MS) {
                lastSave = now
                true
            } else {
                false
            }
        }
        // The writer thread encodes the numbers itself, so this DNS thread only hands the job over
        if (save) writer.execute(::save)
    }

    // Totals of the last [hours] hours, the current one included. While nothing changed the same
    // object comes back, so a screen refreshed with it has nothing to recompose.
    fun summary(hours: Int, now: Long = System.currentTimeMillis()): StatsSummary = synchronized(this) {
        val current = now / HOUR_MS
        lastSummary?.takeIf { summaryChanges == changes && summaryHours == hours && summaryHour == current }
            ?: counters.summary(hours, current).also {
                lastSummary = it
                summaryChanges = changes
                summaryHours = hours
                summaryHour = current
            }
    }

    // One point per hour, oldest first, ending with the current hour
    fun hourly(hours: Int, now: Long = System.currentTimeMillis()): List<HourPoint> =
        synchronized(this) { counters.hourly(hours, now / HOUR_MS) }

    // Saves what is not on disk yet; called when the VPN stops
    fun flush() {
        val save = synchronized(this) {
            if (file != null && dirty) {
                lastSave = System.currentTimeMillis()
                true
            } else {
                false
            }
        }
        if (save) writer.execute(::save)
    }

    // Runs on the writer thread
    private fun save() {
        val (target, bytes) = synchronized(this) {
            val current = file ?: return
            dirty = false
            current to counters.encode()
        }
        try {
            val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            Log.w("SparkleDNS", "Cannot save statistics", e)
        }
    }

    private fun read(source: File): HourlyCounters? {
        if (!source.exists()) return null
        return try {
            HourlyCounters.decode(source.readBytes())
        } catch (e: Exception) {
            Log.w("SparkleDNS", "Cannot read statistics", e)
            null
        }
    }
}

// The numbers behind StatsStore: a ring of hourly rows, each remembering which hour (since the
// epoch) it holds. Plain data without locks or Android; StatsStore takes care of both.
internal class HourlyCounters {
    val hourIds = LongArray(HOURS) { -1L }
    val rows = Array(HOURS) { LongArray(FIELDS) }

    fun record(status: QueryStatus, latencyMs: Int, company: Company?, hour: Long) {
        val row = row(hour)
        row[TOTAL]++
        when (status) {
            QueryStatus.BLOCKED -> row[BLOCKED]++
            QueryStatus.CACHED -> row[CACHED]++
            QueryStatus.OK -> {
                row[LATENCY_SUM] += latencyMs.toLong()
                row[LATENCY_COUNT]++
            }
            QueryStatus.FAILED -> Unit
        }
        if (company != null) row[FIRST_COMPANY + company.ordinal]++
    }

    // Totals of the last [hours] hours up to [currentHour]
    fun summary(hours: Int, currentHour: Long): StatsSummary {
        val sums = LongArray(FIELDS)
        for (back in 0 until hours.coerceIn(1, HOURS)) {
            val id = currentHour - back
            val index = slot(id)
            if (hourIds[index] != id) continue
            val row = rows[index]
            for (field in 0 until FIELDS) sums[field] += row[field]
        }
        return StatsSummary(
            total = sums[TOTAL],
            blocked = sums[BLOCKED],
            cached = sums[CACHED],
            avgLatencyMs = if (sums[LATENCY_COUNT] == 0L) 0 else (sums[LATENCY_SUM] / sums[LATENCY_COUNT]).toInt(),
            companies = sums.copyOfRange(FIRST_COMPANY, FIELDS),
        )
    }

    // One point per hour, oldest first, ending with [currentHour]
    fun hourly(hours: Int, currentHour: Long): List<HourPoint> {
        val count = hours.coerceIn(1, HOURS)
        return List(count) { position ->
            val id = currentHour - (count - 1 - position)
            val index = slot(id)
            if (hourIds[index] == id) HourPoint(rows[index][TOTAL], rows[index][BLOCKED]) else HourPoint(0, 0)
        }
    }

    // Saved hours are added to the ones counted since the start; a slot keeps the newer hour
    fun merge(saved: HourlyCounters) {
        for (index in 0 until HOURS) {
            val id = saved.hourIds[index]
            when {
                id < 0 || id < hourIds[index] -> Unit
                id == hourIds[index] -> for (field in 0 until FIELDS) rows[index][field] += saved.rows[index][field]
                else -> {
                    hourIds[index] = id
                    saved.rows[index].copyInto(rows[index])
                }
            }
        }
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(12 + HOURS * (FIELDS + 1) * 8)
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(FIELDS)
            out.writeInt(HOURS)
            for (index in 0 until HOURS) {
                out.writeLong(hourIds[index])
                rows[index].forEach { out.writeLong(it) }
            }
        }
        return bytes.toByteArray()
    }

    private fun slot(id: Long): Int = (((id % HOURS) + HOURS) % HOURS).toInt()

    private fun row(id: Long): LongArray {
        val index = slot(id)
        if (hourIds[index] != id) {
            hourIds[index] = id
            rows[index].fill(0L)
        }
        return rows[index]
    }

    companion object {
        const val HOURS = 24 * 7
        private const val FORMAT = 1

        // Per hour: total, blocked, cached, latency sum and count, then one counter per company
        private const val TOTAL = 0
        private const val BLOCKED = 1
        private const val CACHED = 2
        private const val LATENCY_SUM = 3
        private const val LATENCY_COUNT = 4
        const val FIRST_COMPANY = 5
        val FIELDS = FIRST_COMPANY + Company.entries.size

        // Files from a version with fewer companies load fine: the new counters start at zero.
        // Null for a file of another format.
        fun decode(bytes: ByteArray): HourlyCounters? {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != FORMAT) return null
                val fields = input.readInt()
                if (input.readInt() != HOURS || fields < FIRST_COMPANY) return null
                val result = HourlyCounters()
                for (index in 0 until HOURS) {
                    result.hourIds[index] = input.readLong()
                    for (field in 0 until fields) {
                        val value = input.readLong()
                        if (field < FIELDS) result.rows[index][field] = value
                    }
                }
                return result
            }
        }
    }
}
