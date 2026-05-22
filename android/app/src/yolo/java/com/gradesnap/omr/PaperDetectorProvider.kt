package com.gradesnap.omr

import android.content.Context

/** YOLO flavor: always returns the singleton YoloPaperDetector. */
object PaperDetectorProvider {
    fun get(context: Context): PaperDetector = YoloPaperDetector.getInstance(context)
}
