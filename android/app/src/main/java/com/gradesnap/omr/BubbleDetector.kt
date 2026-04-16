package com.gradesnap.omr

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.PI

/**
 * Bubble detection — ported from web C++ omr_core.cpp.
 *
 * Key differences from previous Android version:
 *   1. Adaptive threshold (block=31, offset=5) instead of global Otsu
 *   2. Circular density sampling (R=18) at cell center instead of rectangular ratio
 *   3. Adaptive thresholds: global_avg_density + recent_history + per-row max
 */
object BubbleDetector {

    /** Bubble sampling radius in 1700x2400 space (matches web C++) */
    const val BUBBLE_RADIUS = 18

    /** Minimum absolute density to consider a bubble filled */
    const val MIN_DENSITY = 0.20

    /** Density above this is considered a "confident fill" for computing global average */
    const val CONFIDENT_FILL_THRESHOLD = 0.35

    /** MSSV/KEY: density must be >= global_avg * this factor */
    const val NUMERIC_GLOBAL_FACTOR = 0.40

    /** Answer row: skip if max_density < ref_density * this */
    const val ROW_REF_FACTOR = 0.55

    /** Answer option: must be >= max_density * this */
    const val OPTION_MAX_FACTOR = 0.70

    /** Answer option: must be >= ref_density * this */
    const val OPTION_REF_FACTOR = 0.55

    /** Recent history window size for adaptive detection */
    const val HISTORY_SIZE = 5

    /** Threshold for adding to recent history: max_d > global_avg * this */
    const val HISTORY_ADD_FACTOR = 0.60

    /**
     * Preprocess: BGR → gray → GaussianBlur(5×5) → adaptiveThreshold(block=31, offset=5)
     * Matches web C++ omr_core.cpp line 196-197.
     */
    fun preprocess(img: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            blurred, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            31, 5.0
        )
        gray.release(); blurred.release()
        return binary
    }

    /**
     * Compute circular density at a point (cx, cy) with radius R.
     * Returns fraction of white pixels inside the circle on the binary image.
     * Matches web C++ density = countNonZero(ink) / (PI * R * R).
     */
    fun circularDensity(binary: Mat, cx: Int, cy: Int, R: Int = BUBBLE_RADIUS): Double {
        val mask = Mat.zeros(binary.size(), CvType.CV_8UC1)
        Imgproc.circle(mask, Point(cx.toDouble(), cy.toDouble()), R, Scalar(255.0), -1)
        val ink = Mat()
        Core.bitwise_and(binary, binary, ink, mask)
        val count = Core.countNonZero(ink)
        mask.release(); ink.release()
        return count / (PI * R * R)
    }

    /**
     * Compute global average density from all bubble positions.
     * Only includes densities > CONFIDENT_FILL_THRESHOLD (0.35).
     * Default: 0.5 if no confident fills found.
     * Matches web C++ omr_core.cpp lines 264-271.
     */
    fun computeGlobalAvgDensity(allDensities: List<Double>): Double {
        val confident = allDensities.filter { it > CONFIDENT_FILL_THRESHOLD }
        return if (confident.isNotEmpty()) confident.average() else 0.5
    }

    /**
     * Analyze a numeric column (MSSV or KEY) — one column of 10 digits (0-9).
     * Pick the bubble with highest density if it passes thresholds.
     * Matches web C++ omr_core.cpp lines 278-306.
     *
     * @param densities  list of 10 densities (row 0-9)
     * @param globalAvg  global average density
     * @return digit index (0-9) or -1 if no clear fill
     */
    fun analyzeNumericColumn(densities: List<Double>, globalAvg: Double): Int {
        if (densities.isEmpty()) return -1
        val bestIdx = densities.indices.maxByOrNull { densities[it] } ?: return -1
        val bestD = densities[bestIdx]
        return if (bestD > MIN_DENSITY && bestD >= globalAvg * NUMERIC_GLOBAL_FACTOR) {
            bestIdx
        } else -1
    }

    /**
     * Analyze an answer row (5 options A-E).
     * Matches web C++ omr_core.cpp lines 307-357.
     *
     * @param densities    list of 5 densities (one per option)
     * @param globalAvg    global average density
     * @param recentHistory  rolling window of recent max densities
     * @param isAnswerKey  if true, never mark suspicious
     * @return BubbleResult with filled indices and densities
     */
    fun analyzeAnswerRow(
        densities: List<Double>,
        globalAvg: Double,
        recentHistory: MutableList<Double>,
        isAnswerKey: Boolean = false
    ): BubbleResult {
        if (densities.isEmpty()) return BubbleResult(emptySet(), densities, false)

        val maxD = densities.max()

        // Compute ref_density from recent history or fallback to global avg
        val refDensity = if (recentHistory.isNotEmpty()) recentHistory.average() else globalAvg

        // Skip row if max density too low
        if (maxD < MIN_DENSITY || maxD < refDensity * ROW_REF_FACTOR) {
            return BubbleResult(emptySet(), densities, false)
        }

        // Per-option thresholding
        val filled = mutableSetOf<Int>()
        for (i in densities.indices) {
            if (densities[i] > MIN_DENSITY &&
                densities[i] >= maxD * OPTION_MAX_FACTOR &&
                densities[i] >= refDensity * OPTION_REF_FACTOR
            ) {
                filled.add(i)
            }
        }

        // Update recent history
        if (maxD > globalAvg * HISTORY_ADD_FACTOR) {
            recentHistory.add(maxD)
            if (recentHistory.size > HISTORY_SIZE) recentHistory.removeAt(0)
        }

        val suspicious = if (isAnswerKey) false else filled.size > 1
        return BubbleResult(filled, densities, suspicious)
    }

    // ── Legacy compat: old analyzeRow still used by OmrProcessor for now ────────
    // This is kept temporarily but will be replaced in OmrProcessor rewrite below.

    fun analyzeRow(
        binary: Mat,
        rowCells: List<Rect>,
        isAnswerKey: Boolean = false
    ): BubbleResult {
        // Use circular density at cell centers instead of rectangular ratio
        val densities = rowCells.map { rect ->
            val cx = rect.x + rect.width / 2
            val cy = rect.y + rect.height / 2
            circularDensity(binary, cx, cy, BUBBLE_RADIUS)
        }
        if (densities.isEmpty()) return BubbleResult(emptySet(), emptyList(), false)

        val filledIndices = densities.indices.filter { densities[it] >= MIN_DENSITY }.toSet()
        val isSuspicious = if (isAnswerKey) false else filledIndices.size > 1
        return BubbleResult(filledIndices, densities, isSuspicious)
    }
}
