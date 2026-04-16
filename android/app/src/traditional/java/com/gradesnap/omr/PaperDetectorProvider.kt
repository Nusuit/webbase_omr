package com.gradesnap.omr

import android.content.Context
import org.opencv.core.Mat

/**
 * Traditional flavor: pure OpenCV heuristic marker-corner detection.
 * No ONNX dependency, no model file.
 */
object PaperDetectorProvider {
    fun get(context: Context): PaperDetector = TraditionalPaperDetector()
}

private class TraditionalPaperDetector : PaperDetector {
    override fun normalize(img: Mat): Mat = NormalizePaper.normalize(img)
}
