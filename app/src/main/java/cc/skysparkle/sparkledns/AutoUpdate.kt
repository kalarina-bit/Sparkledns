package cc.skysparkle.sparkledns

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

enum class UpdateInterval(val days: Long) { OFF(0), DAILY(1), WEEKLY(7) }

// Downloads newer versions of the user's blocklists in the background. Uses the system
// JobScheduler, so the app needs no extra library and nothing runs while the conditions are not met.
object AutoUpdate {
    private const val JOB_ID = 4201

    // JobInfo.networkType is deprecated in favour of NetworkRequest, which needs API 28
    @Suppress("DEPRECATION")
    fun apply(context: Context) {
        val prefs = Prefs(context)
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val interval = prefs.updateInterval
        if (interval == UpdateInterval.OFF) {
            scheduler.cancel(JOB_ID)
            return
        }
        val period = TimeUnit.DAYS.toMillis(interval.days)
        val network = if (prefs.updateWifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        // Scheduling the same job again would start its period over, so an unchanged one is kept
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == period && existing.networkType == network) return

        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BlocklistUpdateJob::class.java))
            .setPeriodic(period)
            .setRequiredNetworkType(network)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        try {
            scheduler.schedule(job)
        } catch (e: RuntimeException) {
            Log.w("SparkleDNS", "Cannot schedule blocklist updates", e)
        }
    }
}

class BlocklistUpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val ok = try {
                Blocklist.updateAllNow(applicationContext)
            } catch (e: Exception) {
                Log.w("SparkleDNS", "Blocklist update failed", e)
                false
            }
            // A failed run is retried later with the system's back-off
            jobFinished(params, !ok)
        }, "blocklist-update").start()
        return true
    }

    // Downloads cannot be cut short halfway; the next run picks up where this one stopped
    override fun onStopJob(params: JobParameters): Boolean = true
}
