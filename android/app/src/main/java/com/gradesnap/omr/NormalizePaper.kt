package com.gradesnap.omr

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Port from web C++ omr_warp.cpp NormalizeSheet().
 *
 * Key: downscale to 800×H for marker detection (like web), then scale
 * coordinates back to full resolution for the perspective warp.
 */
object NormalizePaper {
    private const val TAG = "NormalizePaper"
    const val TARGET_W = 1700
    const val TARGET_H = 2400

    /** Analysis width — matches web C++ kAnalysisW = 800 */
    private const val ANALYSIS_W = 800

    fun normalize(img: Mat): Mat {
        // 1. Downscale to 800×H for analysis (matches web C++)
        val scaleX = ANALYSIS_W.toDouble() / img.cols()
        val analysisH = (img.rows() * scaleX).toInt()
        val small = Mat()
        Imgproc.resize(img, small, Size(ANALYSIS_W.toDouble(), analysisH.toDouble()))

        // 2. Find corners on small image
        val smallPts = findMarkerCorners(small) ?: findPaperCorners(small)
        small.release()

        if (smallPts == null) {
            Log.w(TAG, "No corners found — fallback to simple resize")
            val resized = Mat()
            Imgproc.resize(img, resized, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
            return resized
        }

        // 3. Scale corner coordinates back to original image size
        val scaleBackX = img.cols().toDouble() / ANALYSIS_W
        val scaleBackY = img.rows().toDouble() / analysisH
        val fullPts = MatOfPoint2f(
            *smallPts.toList().map { Point(it.x * scaleBackX, it.y * scaleBackY) }.toTypedArray()
        )

        // 4. Validate corners span >= 15% of image (matches web CornersLookValid)
        val ordered = orderPoints(fullPts)
        val pts = ordered.toList()
        if (pts[1].x - pts[0].x < img.cols() * 0.15 ||
            pts[2].x - pts[3].x < img.cols() * 0.15 ||
            pts[2].y - pts[1].y < img.rows() * 0.15 ||
            pts[3].y - pts[0].y < img.rows() * 0.15) {
            Log.w(TAG, "Corners invalid (too small span) — fallback to simple resize")
            val resized = Mat()
            Imgproc.resize(img, resized, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
            return resized
        }

        // 5. Perspective warp to 1700×2400
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(TARGET_W - 1.0, 0.0),
            Point(TARGET_W - 1.0, TARGET_H - 1.0),
            Point(0.0, TARGET_H - 1.0)
        )
        val M = Imgproc.getPerspectiveTransform(ordered, dst)
        val warped = Mat()
        Imgproc.warpPerspective(img, warped, M, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
        Log.i(TAG, "Warp OK: TL=(${pts[0].x.toInt()},${pts[0].y.toInt()}) BR=(${pts[2].x.toInt()},${pts[2].y.toInt()})")
        return warped
    }

    // ─── Order points tl, tr, br, bl ──────────────────────────────────────────
    private fun orderPoints(pts: MatOfPoint2f): MatOfPoint2f {
        val points = pts.toList()
        val sumList = points.map { it.x + it.y }
        val diffList = points.map { it.x - it.y }
        val tl = points[sumList.indexOf(sumList.min())]
        val br = points[sumList.indexOf(sumList.max())]
        val tr = points[diffList.indexOf(diffList.max())]
        val bl = points[diffList.indexOf(diffList.min())]
        return MatOfPoint2f(tl, tr, br, bl)
    }

    // ─── Layer 1: Find 4 black marker corners ────────────────────────────────
    // Operates on 800×H downscaled image — area range 50–2000 matches web C++.
    private fun findMarkerCorners(img: Mat): MatOfPoint2f? {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

        val th = Mat()
        Imgproc.threshold(blur, th, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        // MORPH_OPEN ×2 (matches web: MorphOpen twice)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_OPEN, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(th, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        data class Candidate(val area: Double, val meanVal: Double, val cx: Double, val cy: Double)
        val candidates = mutableListOf<Candidate>()

        // Area range 50–2000 at 800×H scale (matches web C++ exactly)
        val maxMarkerArea = minOf((img.cols() * img.rows()) / 4, 2000)

        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < 50 || area > maxMarkerArea) continue
            val rect = Imgproc.boundingRect(c)
            if (rect.width == 0 || rect.height == 0) continue
            val ar = rect.width.toDouble() / rect.height
            if (ar < 0.7 || ar > 1.4) continue
            val fill = area / (rect.width * rect.height).toDouble()
            if (fill < 0.85) continue
            val roi = gray.submat(rect)
            if (roi.total() == 0L) continue
            val mean = Core.mean(roi).`val`[0]
            if (mean > 80) continue
            candidates.add(Candidate(area, mean, rect.x + rect.width / 2.0, rect.y + rect.height / 2.0))
        }

        gray.release(); blur.release(); th.release()
        Log.d(TAG, "findMarkerCorners: ${contours.size} contours, ${candidates.size} candidates")

        if (candidates.size < 4) return null

        // Sort by area descending, keep blobs >= 50% of largest
        val sorted = candidates.sortedWith(compareByDescending<Candidate> { it.area }.thenBy { it.meanVal })
        val maxArea = sorted[0].area
        val filtered = sorted.filter { it.area >= maxArea * 0.5 }.take(20)

        Log.d(TAG, "findMarkerCorners: ${filtered.size} after area filter (max=${maxArea.toInt()}, threshold=${(maxArea * 0.5).toInt()})")

        val pts = filtered.map { Point(it.cx, it.cy) }
        val centroid = Point(pts.map { it.x }.average(), pts.map { it.y }.average())

        // Assign to quadrants, pick farthest in each
        val quadTL = pts.filter { it.x <= centroid.x && it.y <= centroid.y }
        val quadTR = pts.filter { it.x > centroid.x && it.y <= centroid.y }
        val quadBR = pts.filter { it.x > centroid.x && it.y > centroid.y }
        val quadBL = pts.filter { it.x <= centroid.x && it.y > centroid.y }

        fun farthest(group: List<Point>): Point? =
            group.maxByOrNull { (it.x - centroid.x).pow(2) + (it.y - centroid.y).pow(2) }

        val tl = farthest(quadTL) ?: return null
        val tr = farthest(quadTR) ?: return null
        val br = farthest(quadBR) ?: return null
        val bl = farthest(quadBL) ?: return null

        Log.i(TAG, "findMarkerCorners OK: TL=(${tl.x.toInt()},${tl.y.toInt()}) TR=(${tr.x.toInt()},${tr.y.toInt()}) BR=(${br.x.toInt()},${br.y.toInt()}) BL=(${bl.x.toInt()},${bl.y.toInt()})")
        return MatOfPoint2f(tl, tr, br, bl)
    }

    // ─── Layer 2 fallback: Find paper boundary ───────────────────────────────
    private fun findPaperCorners(img: Mat): MatOfPoint2f? {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(7.0, 7.0), 0.0)

        val th = Mat()
        Imgproc.threshold(blur, th, 0.0, 255.0,
            Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        // MORPH_CLOSE ×4 (matches web: MorphClose four times)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(th, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        gray.release(); blur.release(); th.release()
        if (contours.isEmpty()) return null

        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val rect = Imgproc.minAreaRect(MatOfPoint2f(*largest.toArray()))
        val boxPts = Mat()
        Imgproc.boxPoints(rect, boxPts)
        val pts = MatOfPoint2f(boxPts)
        return if (pts.rows() >= 4) pts else null
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
}
