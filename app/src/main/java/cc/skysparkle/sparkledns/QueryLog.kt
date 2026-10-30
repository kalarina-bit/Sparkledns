package cc.skysparkle.sparkledns

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transform

enum class QueryStatus { OK, CACHED, BLOCKED, FAILED }

class QueryEntry(
    val id: Long,
    val time: Long,
    val domain: String,
    val type: Int,
    val status: QueryStatus,
    val latencyMs: Int,
)

data class QueryStats(
    val total: Long,
    val cached: Long,
    val blocked: Long,
    val failed: Long,
    val avgLatencyMs: Int,
)

// In-memory only: nothing is written to disk or sent anywhere. StatsStore keeps hourly
// numbers without domains for the charts.
object QueryLog {
    private const val MAX_ENTRIES = 500

    private val entries = ArrayDeque<QueryEntry>()
    private var nextId = 0L
    private var total = 0L
    private var cached = 0L
    private var blocked = 0L
    private var failed = 0L
    private var latencySum = 0L
    private var latencyCount = 0L
    private val version = MutableStateFlow(0L)

    // Fires right away, then at most once a second while queries keep coming: a busy log refreshes
    // the screen no more often than the old one-second polling did, and an idle one not at all
    fun changes(): Flow<Long> = version.transform {
        emit(it)
        delay(1000)
    }

    fun add(question: DnsMessage.Question?, status: QueryStatus, latencyMs: Int) {
        if (question == null) return
        synchronized(this) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(
                QueryEntry(nextId++, System.currentTimeMillis(), question.name.ifEmpty { "." }, question.type, status, latencyMs)
            )
            total++
            when (status) {
                QueryStatus.OK -> { latencySum += latencyMs; latencyCount++ }
                QueryStatus.CACHED -> cached++
                QueryStatus.BLOCKED -> blocked++
                QueryStatus.FAILED -> failed++
            }
            version.value++
        }
        // Hourly numbers for the charts; outside the lock above, as it takes a lock of its own
        StatsStore.record(status, latencyMs, Companies.of(question.name))
    }

    // Newest first. Builds a real copy: on API 35+ List.reversed() resolves to a live JDK view,
    // and on older devices that method does not exist at all
    @Synchronized
    fun snapshot(): List<QueryEntry> = List(entries.size) { entries[entries.size - 1 - it] }

    @Synchronized
    fun stats() = QueryStats(
        total = total,
        cached = cached,
        blocked = blocked,
        failed = failed,
        avgLatencyMs = if (latencyCount == 0L) 0 else (latencySum / latencyCount).toInt(),
    )

    @Synchronized
    fun clear() {
        entries.clear()
        total = 0; cached = 0; blocked = 0; failed = 0; latencySum = 0; latencyCount = 0
        version.value++
    }
}
