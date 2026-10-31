package cc.skysparkle.sparkledns

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StrictMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SparkleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        CrashLog.install(this)
        Thread({
            // Reads what the first screen needs before it asks, so its first frame does not wait on storage
            Prefs(this).providerId
            CrashLog.load(this)
            StatsStore.init(this)
            AutoUpdate.apply(this)
            // Lists can hold millions of entries; loading them should not take CPU from the first frames
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { Blocklist.ensureLoaded(this) }
        }, "startup-load").start()
    }

    // The VPN keeps the process running long after the screens are gone; decoded app icons
    // (up to 10 MB) and the logo can be rendered again the next time they are shown
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            clearAppIconCache()
            AppLogo.clear()
        }
    }

    // Debug builds only: logs disk and network access on the main thread, leaked closeables and the like
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}

// Saves the last crash so it can be shown and copied on the next launch
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    // Null when there is no report, and until the startup thread has read it
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                // Looked up only now: install() runs on the main thread at start-up
                File(appContext.filesDir, FILE_NAME).writeText(
                    "Thread: ${thread.name}\n" +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n\n$trace"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    // Runs on the startup thread, so the main thread never reads the file
    fun load(context: Context) {
        _report.value = runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        _report.value = null
        val appContext = context.applicationContext
        Thread({ File(appContext.filesDir, FILE_NAME).delete() }, "crash-clear").start()
    }
}
