package com.gradesnap.omr

import org.opencv.core.Mat

/**
 * Contract for Stage 1: normalize a raw camera BGR Mat into a 1700×2400 BGR Mat.
 *
 * Implementations:
 *   traditional flavor → TraditionalPaperDetector (pure OpenCV corner detection)
 *   yolo flavor        → YoloPaperDetector (YOLOv8n + ONNX Runtime)
 *
 * OmrProcessor.cropByMarkers() (Stage 2) refines the output of this stage
 * by re-detecting the 4 registration corner squares and re-warping precisely.
 */
fun interface PaperDetector {
    /**
     * @param img  input camera frame as BGR [rows × cols] Mat (any resolution)
     * @return     1700×2400 BGR Mat ready for Stage 2 → threshold → bubble read
     */
    fun normalize(img: Mat): Mat
}
