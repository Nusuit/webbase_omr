package com.gradesnap.omr

import org.opencv.core.Mat

/**
 * Strategy interface for paper localization before OMR processing.
 * Traditional flavor: heuristic marker-corner detection (OpenCV only).
 * YOLO flavor: YOLOv8n ONNX inference → mask → marker-corner detection.
 */
interface PaperDetector {
    /**
     * Accepts a raw BGR Mat from the camera/gallery.
     * Returns a perspective-warped Mat of size 1700×2400.
     */
    fun normalize(img: Mat): Mat
}
