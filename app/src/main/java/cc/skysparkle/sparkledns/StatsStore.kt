package cc.skysparkle.sparkledns

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
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
    private const val HOURS = 24 * 7
    private const val HOUR_MS = 3_600_000L
    private const val FORMAT = 1
    private const val FILE = "stats.bin"
    private const val SAVE_INTERVAL_MS = 60_000L

    // Per hour: total, blocked, cached, latency sum and count, then one counter per company
    private const val TOTAL = 0
    private const val BLOCKED = 1
    private const val CACHED = 2
    private const val LATENCY_SUM = 3
    private const val LATENCY_COUNT = 4
    private const val FIRST_COMPANY = 5
    private val FIELDS = FIRST_COMPANY + Company.entries.size

    // A ring of slots; each remembers which hour (since the epoch) it currently holds
    private val hourIds = LongArray(HOURS) { -1L }
    private val counters = Array(HOURS) { LongArray(FIELDS) }
    private var file: File? = null

    // Bumped when the numbers change without a new query: loaded from disk or cleared
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var lastSave = 0L
    private var dirty = false

    // Saves happen off the VPN threads, one at a time
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "stats-writer").apply { isDaemon = true } }

    fun init(context: Context) {
        val target = File(context.filesDir, FILE)
        synchronized(this) {
            file = target
            load(target)
        }
        _version.value++
    }

    fun record(status: QueryStatus, latencyMs: Int, company: Company?, now: Long = System.currentTimeMillis()) {
        val snapshot = synchronized(this) {
            val row = row(now / HOUR_MS)
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
            dirty = true
            if (file != null && now - lastSave >= SAVE_INTERVAL_MS) {
                lastSave = now
                dirty = false
                encode()
            } else {
                null
            }
        }
        snapshot?.let(::save)
    }

    // Totals of the last [hours] hours, the current one included
    fun summary(hours: Int, now: Long = System.currentTimeMillis()): StatsSummary = synchronized(this) {
        val current = now / HOUR_MS
        val sums = LongArray(FIELDS)
        for (back in 0 until hours.coerceIn(1, HOURS)) {
            val id = current - back
            val index = slot(id)
            if (hourIds[index] != id) continue
            val row = counters[index]
            for (field in 0 until FIELDS) sums[field] += row[field]
        }
        StatsSummary(
            total = sums[TOTAL],
            blocked = sums[BLOCKED],
            cached = sums[CACHED],
            avgLatencyMs = if (sums[LATENCY_COUNT] == 0L) 0 else (sums[LATENCY_SUM] / sums[LATENCY_COUNT]).toInt(),
            companies = sums.copyOfRange(FIRST_COMPANY, FIELDS),
        )
    }

    // One point per hour, oldest first, ending with the current hour
    fun hourly(hours: Int, now: Long = System.currentTimeMillis()): List<HourPoint> = synchronized(this) {
        val count = hours.coerceIn(1, HOURS)
        val current = now / HOUR_MS
        List(count) { position ->
            val id = current - (count - 1 - position)
            val index = slot(id)
            if (hourIds[index] == id) HourPoint(counters[index][TOTAL], counters[index][BLOCKED]) else HourPoint(0, 0)
        }
    }

    fun clear() {
        val snapshot = synchronized(this) {
            hourIds.fill(-1L)
            counters.forEach { it.fill(0L) }
            dirty = false
            lastSave = System.currentTimeMillis()
            if (file != null) encode() else null
        }
        snapshot?.let(::save)
        _version.value++
    }

    // Saves what is not on disk yet; called when the VPN stops
    fun flush() {
        val snapshot = synchronized(this) {
            if (file != null && dirty) {
                dirty = false
                lastSave = System.currentTimeMillis()
                encode()
            } else {
                null
            }
        }
        snapshot?.let(::save)
    }

    private fun slot(id: Long): Int = (((id % HOURS) + HOURS) % HOURS).toInt()

    private fun row(id: Long): LongArray {
        val index = slot(id)
        if (hourIds[index] != id) {
            hourIds[index] = id
            counters[index].fill(0L)
        }
        return counters[index]
    }

    private fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(FIELDS)
            out.writeInt(HOURS)
            for (index in 0 until HOURS) {
                out.writeLong(hourIds[index])
                counters[index].forEach { out.writeLong(it) }
            }
        }
        return bytes.toByteArray()
    }

    private fun save(bytes: ByteArray) {
        val target = file ?: return
        writer.execute {
            try {
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            } catch (e: Exception) {
                Log.w("SparkleDNS", "Cannot save statistics", e)
            }
        }
    }

    // Files from a version with fewer companies load fine: the new counters start at zero
    private fun load(source: File) {
        if (!source.exists()) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
                if (input.readInt() != FORMAT) return
                val fields = input.readInt()
                if (input.readInt() != HOURS || fields < FIRST_COMPANY) return
                for (index in 0 until HOURS) {
                    hourIds[index] = input.readLong()
                    for (field in 0 until fields) {
                        val value = input.readLong()
                        if (field < FIELDS) counters[index][field] = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SparkleDNS", "Cannot read statistics", e)
            hourIds.fill(-1L)
            counters.forEach { it.fill(0L) }
        }
    }
}
