package cc.skysparkle.sparkledns

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SparkleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Thread({
            // Reads what the first screen needs before it asks, so its first frame does not wait on storage
            Prefs(this).providerId
            CrashLog.preload(this)
            runCatching { Blocklist.reload(this) }
        }, "startup-load").start()
    }
}

// Saves the last crash so it can be shown and copied on the next launch
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file.writeText(
                    "Thread: ${thread.name}\n" +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n\n$trace"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Volatile
    private var preloaded = false

    @Volatile
    private var report: String? = null

    fun preload(context: Context) {
        report = load(context)
        preloaded = true
    }

    fun read(context: Context): String? = if (preloaded) report else load(context)

    fun clear(context: Context) {
        report = null
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun load(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() }.getOrNull()
}
