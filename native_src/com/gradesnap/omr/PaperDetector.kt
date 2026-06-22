package com.gradesnap.omr

import org.opencv.core.Mat

fun interface PaperDetector {
    fun normalize(img: Mat): Mat  // img=BGR, returns 1700×2400 BGR
}
