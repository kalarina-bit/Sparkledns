package cc.skysparkle.sparkledns

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

// In-memory only: nothing is written to disk or sent anywhere
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
        }
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
    }
}
