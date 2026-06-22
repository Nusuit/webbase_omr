package com.gradesnap.omr

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PerformanceTelemetry {
    private const val TAG = "EdgeTelemetry"
    private const val FILENAME = "omr_telemetry.csv"

    fun logAndExport(context: Context, durationMs: Long, memoryUsedMb: Float) {
        val model = Build.MODEL ?: "Unknown"
        val osVersion = Build.VERSION.RELEASE ?: "Unknown"
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        // 1. Log to Logcat
        val json = JSONObject().apply {
            put("timestamp", timestamp)
            put("device", model)
            put("os_version", osVersion)
            put("duration_ms", durationMs)
            put("memory_used_mb", memoryUsedMb)
        }
        Log.i(TAG, json.toString())

        // 2. Append to CSV
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILENAME)
            
            val isNewFile = !file.exists()
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                if (isNewFile) {
                    writer.write("TIMESTAMP,DEVICE,OS_VERSION,DURATION_MS,MEMORY_USED_MB\n")
                }
                writer.write("$timestamp,\"$model\",\"$osVersion\",$durationMs,${String.format(Locale.US, "%.2f", memoryUsedMb)}\n")
            }
            Log.i(TAG, "Telemetry saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save telemetry CSV", e)
        }
    }
}
