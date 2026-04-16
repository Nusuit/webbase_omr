package com.gradesnap.omr

import android.content.Context

/**
 * YOLO flavor: YOLOv8n ONNX Runtime detector.
 * Singleton — ONNX session is expensive to create, reuse across sheets.
 */
object PaperDetectorProvider {
    @Volatile private var instance: YoloPaperDetector? = null

    fun get(context: Context): PaperDetector =
        instance ?: synchronized(this) {
            instance ?: YoloPaperDetector(context.applicationContext).also { instance = it }
        }
}
