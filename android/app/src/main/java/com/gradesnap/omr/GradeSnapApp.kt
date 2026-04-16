package com.gradesnap.omr

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash handler: bắt mọi uncaught exception, ghi vào file crash_log.txt
 * trong external files dir. HomeActivity sẽ check file này khi khởi động và
 * hiện dialog cho user biết + nút Share để gửi log.
 */
class GradeSnapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(this, throwable, thread.name)
            } catch (_: Exception) {
                // Đừng để crash handler crash
            }
            // Chạy handler gốc (Android sẽ hiện dialog "App dừng")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG_FILE = "crash_log.txt"

        fun getCrashLogFile(context: Context): File =
            File(context.getExternalFilesDir(null), CRASH_LOG_FILE)

        fun hasCrashLog(context: Context): Boolean =
            getCrashLogFile(context).exists()

        fun readCrashLog(context: Context): String =
            runCatching { getCrashLogFile(context).readText() }.getOrDefault("(không đọc được)")

        fun clearCrashLog(context: Context) =
            runCatching { getCrashLogFile(context).delete() }

        private fun saveCrashLog(context: Context, throwable: Throwable, threadName: String) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())

            val content = buildString {
                appendLine("=== GradeSnap Crash Log ===")
                appendLine("Time   : $time")
                appendLine("Thread : $threadName")
                appendLine("Error  : ${throwable::class.java.name}: ${throwable.message}")
                appendLine()
                appendLine("--- Stack Trace ---")
                appendLine(sw.toString())
            }

            getCrashLogFile(context).writeText(content)
        }
    }
}
