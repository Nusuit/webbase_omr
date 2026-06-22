package com.gradesnap.omr

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Corner-keypoint paper detector — the native mirror of the Web hybrid path
 * (web/js/worker-yolo.js, det=corner). A single-class YOLOv8n model
 * (corner_detect.onnx, 960²) emits the four corner-marker boxes; their centres
 * are fed DIRECTLY into a homography onto the 1700×2400 canonical template,
 * exactly like NormalizePaper but with YOLO-provided corners instead of the
 * hand-written connected-component marker search.
 *
 * This avoids the two failure modes of the older paths:
 *   - YoloPaperDetector cropped+resized a bbox (no perspective correction) → broken;
 *   - NormalizePaper's CV marker search collapses on dark-background sheets
 *     (dataset_4/5) where the printed markers are faint.
 *
 * Tri-provider (CPU 1-thread / CPU 4-thread / NNAPI), same timing contract as
 * YoloPaperDetector so the existing benchmark harness can drive it unchanged.
 */
class CornerKeypointDetector private constructor(context: Context) : PaperDetector {

    enum class Provider { CPU, CPU4T, NNAPI }

    companion object {
        private const val TAG = "CornerKeypointDetector"
        private const val MODEL_NAME = "corner_detect.onnx"
        private const val INPUT_SIZE = 960
        private const val CONF_THR   = 0.05f   // matches web detectCorners
        private const val IOU_THR    = 0.45f
        private const val TARGET_W   = 1700
        private const val TARGET_H   = 2400

        @Volatile private var instance: CornerKeypointDetector? = null
        fun getInstance(ctx: Context): CornerKeypointDetector =
            instance ?: synchronized(this) {
                instance ?: CornerKeypointDetector(ctx.applicationContext).also { instance = it }
            }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val cpuSession: OrtSession
    private val cpu4Session: OrtSession
    private val nnapiSession: OrtSession?

    val nnapiAvailable: Boolean
    var nnapiUseFp16: Boolean = false
        private set

    var activeProvider: Provider = Provider.CPU
    var lastOrtMs: Long = 0L; private set
    var lastPrepMs: Long = 0L; private set
    var lastStageMs: Long = 0L; private set

    init {
        val modelBytes = context.assets.open(MODEL_NAME).readBytes()
        cpuSession = OrtSession.SessionOptions()
            .apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
            .let { env.createSession(modelBytes, it) }
        cpu4Session = OrtSession.SessionOptions()
            .apply { setIntraOpNumThreads(4); setInterOpNumThreads(1) }
            .let { env.createSession(modelBytes, it) }

        var ns: OrtSession? = null
        var nAvail = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            ns = try {
                env.createSession(modelBytes, OrtSession.SessionOptions().apply { addNnapi() })
            } catch (e: Exception) {
                Log.d(TAG, "NNAPI session failed: ${e.message}"); null
            }
            nAvail = ns != null
        }
        nnapiSession = ns
        nnapiAvailable = nAvail
        Log.i(TAG, "init: input=$INPUT_SIZE NNAPI=$nnapiAvailable cores=${Runtime.getRuntime().availableProcessors()}")
    }

    override fun normalize(img: Mat): Mat {
        val session = when (activeProvider) {
            Provider.CPU   -> cpuSession
            Provider.CPU4T -> cpu4Session
            Provider.NNAPI -> nnapiSession ?: cpuSession
        }
        return inferAndWarp(img, session, activeProvider.name)
    }

    private fun inferAndWarp(img: Mat, session: OrtSession, tag: String): Mat {
        val tStage = SystemClock.elapsedRealtime()
        return try {
            inferAndWarpInternal(img, session, tag, tStage)
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] corner stage FAILED: ${e.javaClass.simpleName}: ${e.message}")
            lastOrtMs = 0L
            lastStageMs = SystemClock.elapsedRealtime() - tStage
            Mat().also { Imgproc.resize(img, it, Size(TARGET_W.toDouble(), TARGET_H.toDouble())) }
        }
    }

    private fun inferAndWarpInternal(img: Mat, session: OrtSession, tag: String, tStage: Long): Mat {
        // 1. Preprocess: letterbox → CHW float
        val tPrep = SystemClock.elapsedRealtime()
        val lb  = letterbox(img, INPUT_SIZE)
        val buf = matToCHWFloat(lb.mat)
        lb.mat.release()
        lastPrepMs = SystemClock.elapsedRealtime() - tPrep

        // 2. ORT inference
        val tOrt = SystemClock.elapsedRealtime()
        val inputTensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val ortResult = session.run(mapOf(session.inputNames.first() to inputTensor))
        inputTensor.close()
        val outTensor = ortResult.get(0) as OnnxTensor
        val shape      = outTensor.info.shape       // [1, 5, numAnchors] (cx,cy,w,h,conf)
        val numAnchors = shape[2].toInt()
        val numRows    = shape[1].toInt()
        val flat       = FloatArray(numRows * numAnchors)
        outTensor.floatBuffer.get(flat)
        lastOrtMs = SystemClock.elapsedRealtime() - tOrt
        outTensor.close(); ortResult.close()

        // 3. Candidates (single class: conf at row 4)
        val candidates = mutableListOf<FloatArray>()
        for (i in 0 until numAnchors) {
            val conf = flat[4 * numAnchors + i]
            if (conf < CONF_THR) continue
            val cx = flat[0 * numAnchors + i]; val cy = flat[1 * numAnchors + i]
            val bw = flat[2 * numAnchors + i]; val bh = flat[3 * numAnchors + i]
            candidates += floatArrayOf(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, conf)
        }
        val kept = nms(candidates, IOU_THR).sortedByDescending { it[4] }
        lastStageMs = SystemClock.elapsedRealtime() - tStage

        if (kept.size < 4) {
            Log.w(TAG, "[$tag] only ${kept.size} corners — resize fallback")
            return Mat().also { Imgproc.resize(img, it, Size(TARGET_W.toDouble(), TARGET_H.toDouble())) }
        }

        // 4. Top-4 box centres → original image coords
        val pts = kept.take(4).map { b ->
            val cxLb = (b[0] + b[2]) / 2f
            val cyLb = (b[1] + b[3]) / 2f
            Point(((cxLb - lb.padX) / lb.scale).toDouble(), ((cyLb - lb.padY) / lb.scale).toDouble())
        }
        val ordered = sortCorners(pts)   // [TL, TR, BR, BL]

        // Span check — identical to web C++ CornersLookValid (omr_warp.cpp).
        // If corners are degenerate, fall back to a plain resize like the web path
        // (which returns false → caller falls back) rather than warp garbage.
        if (!cornersLookValid(ordered, img.cols(), img.rows())) {
            Log.w(TAG, "[$tag] corners failed span check — resize fallback")
            return Mat().also { Imgproc.resize(img, it, Size(TARGET_W.toDouble(), TARGET_H.toDouble())) }
        }

        // 5. Homography → canonical 1700×2400. dst corners match web ComputeHomography:
        //    TL→(0,0) TR→(W,0) BR→(W,H) BL→(0,H)  (W,H not W-1,H-1).
        val src = MatOfPoint2f(ordered[0], ordered[1], ordered[2], ordered[3])
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(TARGET_W.toDouble(), 0.0),
            Point(TARGET_W.toDouble(), TARGET_H.toDouble()), Point(0.0, TARGET_H.toDouble())
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(img, warped, m, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
        src.release(); dst.release(); m.release()
        Log.i(TAG, "[$tag] prep=${lastPrepMs} ort=${lastOrtMs} stage=${lastStageMs} corners=4")
        return warped
    }

    /**
     * Geometric sort of 4 points into [TL, TR, BR, BL] — byte-for-byte the same
     * logic as web C++ NormalizeSheetWithCorners (omr_warp.cpp): sort by y to get
     * the top pair and bottom pair, then order each pair left-to-right.
     */
    private fun sortCorners(pts: List<Point>): List<Point> {
        val p = pts.sortedBy { it.y }
        var tl = p[0]; var tr = p[1]; var bl = p[2]; var br = p[3]
        if (tl.x > tr.x) { val t = tl; tl = tr; tr = t }
        if (bl.x > br.x) { val t = bl; bl = br; br = t }
        return listOf(tl, tr, br, bl)   // TL, TR, BR, BL
    }

    /** Span check — identical to web C++ CornersLookValid (corners = TL,TR,BR,BL). */
    private fun cornersLookValid(c: List<Point>, w: Int, h: Int): Boolean {
        val minX = w * 0.15; val minY = h * 0.15
        if (c[1].x - c[0].x < minX) return false  // TR.x − TL.x
        if (c[2].x - c[3].x < minX) return false  // BR.x − BL.x
        if (c[2].y - c[1].y < minY) return false  // BR.y − TR.y
        if (c[3].y - c[0].y < minY) return false  // BL.y − TL.y
        return true
    }

    private data class LB(val mat: Mat, val scale: Float, val padX: Float, val padY: Float)

    private fun letterbox(src: Mat, size: Int): LB {
        val sw = src.cols().toFloat(); val sh = src.rows().toFloat()
        val scale = min(size / sw, size / sh)
        val nw = (sw * scale).roundToInt().coerceIn(1, size)
        val nh = (sh * scale).roundToInt().coerceIn(1, size)
        val px = ((size - nw) / 2).coerceAtLeast(0)
        val py = ((size - nh) / 2).coerceAtLeast(0)
        val resized = Mat()
        Imgproc.resize(src, resized, Size(nw.toDouble(), nh.toDouble()))
        val canvas = Mat(size, size, src.type(), Scalar(110.0, 110.0, 110.0))
        val copyW = min(nw, size - px); val copyH = min(nh, size - py)
        resized.submat(0, copyH, 0, copyW).copyTo(canvas.submat(py, py + copyH, px, px + copyW))
        resized.release()
        return LB(canvas, scale, px.toFloat(), py.toFloat())
    }

    private fun matToCHWFloat(mat: Mat): FloatBuffer {
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_BGR2RGB)
        val n = mat.rows() * mat.cols()
        val raw = ByteArray(n * 3)
        rgb.get(0, 0, raw); rgb.release()
        val buf = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) {
            buf.put(0 * n + i, (raw[i * 3 + 0].toInt() and 0xFF) / 255f)
            buf.put(1 * n + i, (raw[i * 3 + 1].toInt() and 0xFF) / 255f)
            buf.put(2 * n + i, (raw[i * 3 + 2].toInt() and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    private fun nms(boxes: MutableList<FloatArray>, iouThr: Float): List<FloatArray> {
        boxes.sortByDescending { it[4] }
        val suppressed = BooleanArray(boxes.size)
        return buildList {
            for (i in boxes.indices) {
                if (suppressed[i]) continue
                add(boxes[i])
                for (j in i + 1 until boxes.size) {
                    if (suppressed[j]) continue
                    val iW = max(0f, min(boxes[i][2], boxes[j][2]) - max(boxes[i][0], boxes[j][0]))
                    val iH = max(0f, min(boxes[i][3], boxes[j][3]) - max(boxes[i][1], boxes[j][1]))
                    val inter = iW * iH
                    val aI = (boxes[i][2] - boxes[i][0]) * (boxes[i][3] - boxes[i][1])
                    val aJ = (boxes[j][2] - boxes[j][0]) * (boxes[j][3] - boxes[j][1])
                    val union = aI + aJ - inter
                    if (union > 0f && inter / union > iouThr) suppressed[j] = true
                }
            }
        }
    }

    fun close() {
        runCatching { cpuSession.close() }
        runCatching { cpu4Session.close() }
        runCatching { nnapiSession?.close() }
    }
}
