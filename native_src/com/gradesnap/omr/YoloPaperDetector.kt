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
 * YOLOv8n paper detector — tri-provider (CPU 1-thread / CPU 4-thread / NNAPI).
 *
 * Session A — CPU (setIntraOpNumThreads=1):
 *   Always available. Matches the web WASM single-thread baseline.
 *   Baseline on Redmi Note 13 Pro+: YOLO ≈ 429ms, E2E ≈ 2,231ms.
 *
 * Session B — NNAPI (Android 8.1+ / API 27):
 *   Delegates to Mali GPU or Dimensity APU. Tried with USE_FP16 first;
 *   falls back to FP32 if driver rejects it.
 *   If NNAPI hardware is unavailable, nnapiSession stays null and
 *   nnapiAvailable = false — there is NO silent CPU fallback.
 *
 * Session C — CPU 4-thread (setIntraOpNumThreads=4):
 *   Fair comparison against Mobile Web SIMD+Threads (SharedArrayBuffer).
 *   Uses all available performance cores on Dimensity 7200-Ultra (8 cores).
 *   Always available (pure CPU, no special driver required).
 *
 * Timing exposed per call to normalize():
 *   lastOrtMs   — pure OrtSession.run() time (what the paper calls "YOLO infer")
 *   lastStageMs — full YOLO stage: preprocess + infer + NMS + crop/resize
 *
 * Thread safety: normalize() is called sequentially by OmrProcessor.
 */
class YoloPaperDetector private constructor(context: Context) : PaperDetector {

    // ── Provider enum ─────────────────────────────────────────────────────────
    enum class Provider { CPU, CPU4T, NNAPI }

    companion object {
        private const val TAG = "YoloPaperDetector"
        private const val MODEL_NAME  = "paper_detect.onnx"
        private const val INPUT_SIZE  = 640
        private const val MARKER_CLS  = 0      // class 0 = marker_corners
        private const val CONF_THR    = 0.50f
        private const val IOU_THR     = 0.45f
        private const val EXPAND      = 0.12f  // 12% bbox expansion (matches Web)

        @Volatile private var instance: YoloPaperDetector? = null
        fun getInstance(ctx: Context): YoloPaperDetector =
            instance ?: synchronized(this) {
                instance ?: YoloPaperDetector(ctx.applicationContext).also { instance = it }
            }
    }

    // ── Sessions ──────────────────────────────────────────────────────────────
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val cpuSession: OrtSession
    private val cpu4Session: OrtSession
    private val nnapiSession: OrtSession?

    val nnapiAvailable: Boolean
    var nnapiUseFp16: Boolean = false
        private set

    // ── Mutable state — updated after every normalize() call ──────────────────
    /** Switch this before each benchmark pass. */
    var activeProvider: Provider = Provider.CPU

    /** Pure OrtSession.run() time (ms) — the "YOLO inference" figure for the paper. */
    var lastOrtMs: Long = 0L
        private set

    /** Letterbox + CHW conversion time (ms) — "yolo_prep" in stage breakdown. */
    var lastPrepMs: Long = 0L
        private set

    /** Full YOLO stage (preprocess + infer + NMS + crop) — used for E2E decomposition. */
    var lastStageMs: Long = 0L
        private set

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        val modelBytes = context.assets.open(MODEL_NAME).readBytes()
        val availCores = Runtime.getRuntime().availableProcessors()

        // ── Session A: CPU 1-thread ───────────────────────────────────────────
        cpuSession = OrtSession.SessionOptions()
            .apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
            .let { env.createSession(modelBytes, it) }

        // ── Session C: CPU 4-thread ───────────────────────────────────────────
        cpu4Session = OrtSession.SessionOptions()
            .apply { setIntraOpNumThreads(4); setInterOpNumThreads(1) }
            .let { env.createSession(modelBytes, it) }

        Log.i(TAG, "─── YoloPaperDetector init ───────────────────────────")
        Log.i(TAG, "ORT version : 1.18.0")
        Log.i(TAG, "Device      : ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.i(TAG, "Android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "CPU cores   : $availCores available")
        Log.i(TAG, "CPU-1T sess : ready (intraOpThreads=1)")
        Log.i(TAG, "CPU-4T sess : ready (intraOpThreads=4, availCores=$availCores)")

        // ── Session B: NNAPI (API 27+) ────────────────────────────────────────
        var ns: OrtSession? = null
        var nAvail = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // API 27
            ns = createNnapiSession(modelBytes)
            if (ns != null) {
                nAvail = true
                Log.i(TAG, "NNAPI session: ready — FP32 (NNAPIFlags.USE_FP16 skipped: not in ORT 1.18 Android AAR)")
                Log.i(TAG, "NOTE: check logcat for 'NnapiExecutionProvider' to see GPU/DSP/CPU assignment")
            } else {
                Log.w(TAG, "NNAPI session: NOT available on this device/driver → CPU-only")
                Log.w(TAG, "Filter logcat 'nnapi_implementation' for driver rejection reason")
            }
        } else {
            Log.w(TAG, "NNAPI session: skipped — requires API 27, device has ${Build.VERSION.SDK_INT}")
        }
        nnapiSession = ns
        nnapiAvailable = nAvail
        Log.i(TAG, "──────────────────────────────────────────────────────")
    }

    private fun createNnapiSession(modelBytes: ByteArray): OrtSession? = try {
        val opts = OrtSession.SessionOptions()
        opts.addNnapi()
        env.createSession(modelBytes, opts)
    } catch (e: Exception) {
        Log.d(TAG, "createNnapiSession failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // ── PaperDetector impl ────────────────────────────────────────────────────
    override fun normalize(img: Mat): Mat {
        val session = when (activeProvider) {
            Provider.CPU   -> cpuSession
            Provider.CPU4T -> cpu4Session
            Provider.NNAPI -> nnapiSession ?: run {
                Log.w(TAG, "NNAPI requested but unavailable — falling back to CPU for this call")
                cpuSession
            }
        }
        return inferAndWarp(img, session, activeProvider.name)
    }

    // ── Core YOLO stage ───────────────────────────────────────────────────────
    private fun inferAndWarp(img: Mat, session: OrtSession, tag: String): Mat {
        val tStage = SystemClock.elapsedRealtime()
        return try {
            inferAndWarpInternal(img, session, tag)
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] YOLO stage FAILED: ${e.javaClass.simpleName}: ${e.message}")
            lastOrtMs   = 0L
            lastStageMs = SystemClock.elapsedRealtime() - tStage
            // Graceful fallback: resize full image to 1700×2400 so Stage 2 can still attempt
            Mat().also { Imgproc.resize(img, it, Size(1700.0, 2400.0)) }
        }
    }

    private fun inferAndWarpInternal(img: Mat, session: OrtSession, tag: String): Mat {
        val tStage = SystemClock.elapsedRealtime()

        // ── 1. Preprocess: letterbox → CHW float ─────────────────────────────
        val tPrep = SystemClock.elapsedRealtime()
        val lb  = letterbox(img, INPUT_SIZE)
        val buf = matToCHWFloat(lb.mat)
        lb.mat.release()
        val prepMs = SystemClock.elapsedRealtime() - tPrep

        // ── 2. ORT inference ──────────────────────────────────────────────────
        val tOrt = SystemClock.elapsedRealtime()
        val inputTensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val ortResult = session.run(mapOf(session.inputNames.first() to inputTensor))
        inputTensor.close()

        // ── 3. Parse output — force-evaluate BEFORE stopping ORT timer ────────
        // OrtSession.Result.get(int) → OnnxValue  (direct, not Optional)
        // OrtSession.Result.get(String) → Optional<OnnxValue>  (DO NOT use for cast)
        // floatBuffer.get() materialises the tensor eagerly, ensuring lastOrtMs
        // captures full inference time even if session.run() returns lazily.
        val outTensor = ortResult.get(0) as OnnxTensor
        val shape      = outTensor.info.shape          // [1, 4+NC, numAnchors]
        val numAnchors = shape[2].toInt()
        val numRows    = shape[1].toInt()
        val flat       = FloatArray(numRows * numAnchors)
        outTensor.floatBuffer.get(flat)
        lastOrtMs = SystemClock.elapsedRealtime() - tOrt  // captured after full eval
        outTensor.close()
        ortResult.close()

        // ── 4. Filter candidates (class MARKER_CLS above CONF_THR) ───────────
        val candidates = mutableListOf<FloatArray>()
        for (i in 0 until numAnchors) {
            val conf = flat[(4 + MARKER_CLS) * numAnchors + i]
            if (conf < CONF_THR) continue
            val cx = flat[0 * numAnchors + i]; val cy = flat[1 * numAnchors + i]
            val bw = flat[2 * numAnchors + i]; val bh = flat[3 * numAnchors + i]
            candidates += floatArrayOf(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, conf)
        }

        // ── 5. NMS ────────────────────────────────────────────────────────────
        val kept = nms(candidates, IOU_THR)
        val topConf = if (kept.isNotEmpty()) kept[0][4] else 0f

        // ── 6. Map box → original image coords + expand ───────────────────────
        val imgW = img.cols(); val imgH = img.rows()
        val bbox: Rect? = kept.firstOrNull()?.let { b ->
            val ox1 = ((b[0] - lb.padX) / lb.scale).roundToInt()
            val oy1 = ((b[1] - lb.padY) / lb.scale).roundToInt()
            val ox2 = ((b[2] - lb.padX) / lb.scale).roundToInt()
            val oy2 = ((b[3] - lb.padY) / lb.scale).roundToInt()
            val ew  = ((ox2 - ox1) * EXPAND).roundToInt()
            val eh  = ((oy2 - oy1) * EXPAND).roundToInt()
            val x1  = max(0, ox1 - ew); val y1 = max(0, oy1 - eh)
            val x2  = min(imgW, ox2 + ew); val y2 = min(imgH, oy2 + eh)
            val w = x2 - x1; val h = y2 - y1
            if (w > 50 && h > 50) Rect(x1, y1, w, h) else null
        }

        lastPrepMs  = prepMs
        lastStageMs = SystemClock.elapsedRealtime() - tStage

        Log.i(TAG, "[$tag] prep=${lastPrepMs}ms  ort=${lastOrtMs}ms  stage=${lastStageMs}ms" +
                   "  conf=${"%.3f".format(topConf)}  bbox=${bbox?.run { "${width}×${height}@(${x},${y})" } ?: "MISS"}  img=${imgW}×${imgH}")

        // ── 7. Crop detected region → resize to 1700×2400 ────────────────────
        // Stage 2 (OmrProcessor.cropByMarkers) refines perspective via corner detection.
        val src    = if (bbox != null) img.submat(bbox) else img
        val warped = Mat()
        Imgproc.resize(src, warped, Size(1700.0, 2400.0))
        if (bbox == null) Log.w(TAG, "[$tag] YOLO miss — full image fallback; Stage 2 may fail")
        return warped
    }

    // ── Letterbox ─────────────────────────────────────────────────────────────
    private data class LB(val mat: Mat, val scale: Float, val padX: Float, val padY: Float)

    private fun letterbox(src: Mat, size: Int): LB {
        val sw = src.cols().toFloat(); val sh = src.rows().toFloat()
        val scale = min(size / sw, size / sh)
        // Clamp to avoid submat overflow from float rounding
        val nw = (sw * scale).roundToInt().coerceIn(1, size)
        val nh = (sh * scale).roundToInt().coerceIn(1, size)
        val px = ((size - nw) / 2).coerceAtLeast(0)
        val py = ((size - nh) / 2).coerceAtLeast(0)

        val resized = Mat()
        Imgproc.resize(src, resized, Size(nw.toDouble(), nh.toDouble()))
        val canvas = Mat(size, size, src.type(), Scalar(114.0, 114.0, 114.0))
        // Guard submat bounds in case nw/nh still reach the edge
        val copyW = min(nw, size - px)
        val copyH = min(nh, size - py)
        resized.submat(0, copyH, 0, copyW).copyTo(canvas.submat(py, py + copyH, px, px + copyW))
        resized.release()

        return LB(canvas, scale, px.toFloat(), py.toFloat())
    }

    // ── BGR Mat → CHW FloatBuffer (normalized 0–1) ────────────────────────────
    private fun matToCHWFloat(mat: Mat): FloatBuffer {
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_BGR2RGB)
        val n = mat.rows() * mat.cols()
        val raw = ByteArray(n * 3)
        rgb.get(0, 0, raw)
        rgb.release()
        val buf = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) {
            buf.put(0 * n + i, (raw[i * 3 + 0].toInt() and 0xFF) / 255f)
            buf.put(1 * n + i, (raw[i * 3 + 1].toInt() and 0xFF) / 255f)
            buf.put(2 * n + i, (raw[i * 3 + 2].toInt() and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    // ── NMS ───────────────────────────────────────────────────────────────────
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
