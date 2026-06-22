package com.gradesnap.omr

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage 2 — Tight crop by corner markers (port of omr_core.cpp ProcessSheetRgba Stage 2).
 *
 * After Stage 1 (NormalizePaper) produces a rough 1700×2400 warp keyed to the paper
 * boundary, the fixed bubble grid is keyed to the four printed corner MARKERS, not the
 * paper edge. This stage re-detects those markers on the warped image and re-warps so the
 * markers land exactly on the canvas corners. Without it, dark/low-contrast sheets
 * (dataset_4/5, where Stage-1 Layer-1 markers fail and Layer-2 paper detection is used)
 * leave the grid offset and collapse. Mirrors the Web/WASM reference exactly.
 *
 * Input/Output: a BGR Mat sized [TARGET_W × TARGET_H]. Returns a new tightly-cropped BGR
 * Mat of the same size, or the input unchanged when fewer than 4 markers are recovered.
 */
object MarkerCrop {
    private const val W = NormalizePaper.TARGET_W   // 1700
    private const val H = NormalizePaper.TARGET_H   // 2400

    fun cropByMarkers(warped: Mat): Mat {
        val g2 = Mat()
        Imgproc.cvtColor(warped, g2, Imgproc.COLOR_BGR2GRAY)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

        // ── Global pass: BINARY_INV+Otsu → MORPH_OPEN → contours in the 4 corner zones ──
        val th = Mat()
        Imgproc.threshold(g2, th, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val cleaned = Mat()
        Imgproc.morphologyEx(th, cleaned, Imgproc.MORPH_OPEN, kernel)
        th.release()

        val ctrs = ArrayList<MatOfPoint>()
        Imgproc.findContours(cleaned, ctrs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        cleaned.release()

        val zoneW = W / 5   // 340
        val zoneH = H / 5   // 480
        val markers = ArrayList<Point>()
        for (c in ctrs) {
            val bound = Imgproc.boundingRect(c)
            val area = Imgproc.contourArea(c)
            val ar = bound.width.toDouble() / bound.height
            val touchesEdge = bound.x <= 1 || bound.y <= 1 ||
                bound.x + bound.width >= W - 1 || bound.y + bound.height >= H - 1
            val minArea = if (touchesEdge) 150.0 else 1500.0
            if (area < minArea || area > 15000.0) continue
            if (ar < 0.7 || ar > 1.4) continue
            val minFill = if (touchesEdge) 0.55 else 0.7
            if (area / (bound.width.toDouble() * bound.height) < minFill) continue
            val cx = bound.x + bound.width / 2.0
            val cy = bound.y + bound.height / 2.0
            val inLeft = cx < zoneW; val inRight = cx > W - zoneW
            val inTop = cy < zoneH; val inBottom = cy > H - zoneH
            if ((inLeft || inRight) && (inTop || inBottom)) markers.add(Point(cx, cy))
        }
        for (c in ctrs) c.release()

        // ── Fallback: per-corner local search when the global pass finds < 4 markers ──
        var pts = markers
        if (pts.size < 4) {
            val tl = findLocalCornerMarker(g2, kernel, Rect(0, 120, 520, 560), Point(0.0, 0.0))
            val tr = findLocalCornerMarker(g2, kernel, Rect(1180, 120, 520, 560), Point(W.toDouble(), 0.0))
            val bl = findLocalCornerMarker(g2, kernel, Rect(0, 1780, 560, 620), Point(0.0, H.toDouble()))
            val br = findLocalCornerMarker(g2, kernel, Rect(1140, 1780, 560, 620), Point(W.toDouble(), H.toDouble()))
            if (tl != null && tr != null && bl != null && br != null) {
                pts = arrayListOf(tl, tr, bl, br)
            }
        }

        if (pts.size < 4) { g2.release(); return warped }

        // Sort by y, split top/bottom rows, order each L→R → [TL, TR, BR, BL]
        pts.sortBy { it.y }
        val top = arrayListOf(pts[0], pts[1]); if (top[0].x > top[1].x) top.reverse()
        val bot = arrayListOf(pts[pts.size - 2], pts[pts.size - 1]); if (bot[0].x > bot[1].x) bot.reverse()
        val tl = top[0]; val tr = top[1]; val br = bot[1]; val bl = bot[0]

        val widthA = dist(br, bl); val widthB = dist(tr, tl)
        val heightA = dist(tr, br); val heightB = dist(tl, bl)
        val maxW = max(widthA, widthB).toInt()
        val maxH = max(heightA, heightB).toInt()
        g2.release()
        if (maxW <= 100 || maxH <= 100) return warped

        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(maxW - 1.0, 0.0),
            Point(maxW - 1.0, maxH - 1.0), Point(0.0, maxH - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val cropped = Mat()
        Imgproc.warpPerspective(warped, cropped, m, Size(maxW.toDouble(), maxH.toDouble()))
        val outMat = Mat()
        Imgproc.resize(cropped, outMat, Size(W.toDouble(), H.toDouble()))
        src.release(); dst.release(); m.release(); cropped.release()
        return outMat
    }

    private fun dist(a: Point, b: Point): Double =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    // ─── Local per-corner marker search (port findLocalCornerMarker) ─────────────
    private fun findLocalCornerMarker(g2: Mat, kernel: Mat, zone: Rect, target: Point): Point? {
        val safe = zone.clone().intersect(0, 0, W, H) ?: return null
        if (safe.width <= 0 || safe.height <= 0) return null
        val roi = g2.submat(safe)

        val otsuTmp = Mat()
        val otsu = Imgproc.threshold(roi, otsuTmp, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        otsuTmp.release()
        val thresholds = intArrayOf(otsu.toInt(), percentile(roi, 0.10), percentile(roi, 0.20))

        var found = false; var bestScore = 1e18; var best = Point()
        val zoneDiag = sqrt((safe.width.toDouble() * safe.width + safe.height.toDouble() * safe.height))

        for (tv in thresholds) {
            val localBin = Mat()
            Imgproc.threshold(roi, localBin, tv.coerceIn(0, 255).toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
            val localCleaned = Mat()
            Imgproc.morphologyEx(localBin, localCleaned, Imgproc.MORPH_OPEN, kernel)
            localBin.release()
            val lctrs = ArrayList<MatOfPoint>()
            Imgproc.findContours(localCleaned, lctrs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            localCleaned.release()

            for (c in lctrs) {
                val bound = Imgproc.boundingRect(c)
                val area = Imgproc.contourArea(c)
                if (area < 300.0 || area > 6000.0) continue
                val ar = bound.width.toDouble() / bound.height
                if (ar < 0.65 || ar > 1.50) continue
                val fill = area / (bound.width.toDouble() * bound.height)
                if (fill < 0.55) continue
                val center = Point(
                    safe.x + bound.x + bound.width / 2.0,
                    safe.y + bound.y + bound.height / 2.0
                )
                val d = dist(center, target) / max(1.0, zoneDiag)
                val areaBonus = min(area, 2500.0) / 2500.0 * 0.08
                val squarePenalty = abs(ln(ar)) * 0.05
                val score = d + squarePenalty - areaBonus
                if (score < bestScore) { bestScore = score; best = center; found = true }
            }
            for (c in lctrs) c.release()
            if (found && bestScore < 0.50) break
        }
        roi.release()
        return if (found) best else null
    }

    // Cumulative-histogram percentile of a single-channel ROI (port localPercentile).
    private fun percentile(roi: Mat, pct: Double): Int {
        val cont = if (roi.isContinuous) roi else roi.clone()
        val n = (cont.total() * cont.channels()).toInt()
        val buf = ByteArray(n)
        cont.get(0, 0, buf)
        if (cont !== roi) cont.release()
        val hist = IntArray(256)
        for (b in buf) hist[b.toInt() and 0xFF]++
        val targetCount = max(1, (n * pct).toInt())
        var acc = 0
        for (i in 0 until 256) { acc += hist[i]; if (acc >= targetCount) return i }
        return 255
    }

    private fun Rect.intersect(x: Int, y: Int, w: Int, h: Int): Rect? {
        val x1 = max(this.x, x); val y1 = max(this.y, y)
        val x2 = min(this.x + this.width, x + w); val y2 = min(this.y + this.height, y + h)
        if (x2 <= x1 || y2 <= y1) return null
        return Rect(x1, y1, x2 - x1, y2 - y1)
    }
}
