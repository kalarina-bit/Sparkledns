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
        Thread({ runCatching { Blocklist.reload(this) } }, "blocklist-load").start()
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

    fun read(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
