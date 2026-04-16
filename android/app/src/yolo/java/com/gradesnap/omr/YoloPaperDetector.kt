package com.gradesnap.omr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

private data class DetBox(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float, val conf: Float
)

private fun nms(boxes: List<DetBox>, iouThresh: Float): List<DetBox> {
    val sorted = boxes.sortedByDescending { it.conf }
    val suppressed = BooleanArray(sorted.size)
    val kept = mutableListOf<DetBox>()
    for (i in sorted.indices) {
        if (suppressed[i]) continue
        kept.add(sorted[i])
        for (j in i + 1 until sorted.size) {
            if (suppressed[j]) continue
            val a = sorted[i]; val b = sorted[j]
            val interX = maxOf(0f, minOf(a.x2, b.x2) - maxOf(a.x1, b.x1))
            val interY = maxOf(0f, minOf(a.y2, b.y2) - maxOf(a.y1, b.y1))
            val inter  = interX * interY
            val union  = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
            if (union > 0 && inter / union > iouThresh) suppressed[j] = true
        }
    }
    return kept
}

/**
 * YOLOv8n-based paper detector.
 * Mirrors web worker.js detectMarkerRegion():
 *   1. Letterbox-resize to 640×640
 *   2. ONNX inference — model output [1, 4+NC, 8400], class 0 = marker_corners
 *   3. NMS → best bbox → expand 12%
 *   4. Mask background black, pass to NormalizePaper (OpenCV marker-corner detection)
 */
class YoloPaperDetector(context: Context) : PaperDetector {

    companion object {
        private const val TAG = "YoloPaperDetector"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.50f   // matches web (was 0.25)
        private const val IOU_THRESHOLD = 0.45f
        private const val MARKER_CLASS_ID = 0
        private const val EXPAND_PCT = 0.20f      // increased from 0.12 to avoid clipping corners
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val bytes = context.assets.open("paper_detect.onnx").readBytes()
        session = env.createSession(bytes, opts)
        Log.i(TAG, "ONNX session ready")
    }

    override fun normalize(img: Mat): Mat {
        val t0 = System.currentTimeMillis()
        val bbox = detect(img)
        val yoloMs = System.currentTimeMillis() - t0
        Log.i(TAG, "[YOLO] inference=${yoloMs}ms detected=${bbox != null}")

        return if (bbox != null) {
            val masked = maskOutside(img, bbox)
            val warped = NormalizePaper.normalize(masked)
            masked.release()
            warped
        } else {
            Log.w(TAG, "[YOLO] no detection — fallback to full image")
            NormalizePaper.normalize(img)
        }
    }

    private fun detect(img: Mat): Rect? {
        val W = img.cols(); val H = img.rows()

        // ── Letterbox ──────────────────────────────────────────────────────────
        val scale = minOf(INPUT_SIZE.toFloat() / W, INPUT_SIZE.toFloat() / H)
        val newW = (W * scale).toInt(); val newH = (H * scale).toInt()
        val padX = (INPUT_SIZE - newW) / 2; val padY = (INPUT_SIZE - newH) / 2

        val resized = Mat()
        Imgproc.resize(img, resized, Size(newW.toDouble(), newH.toDouble()))
        val lb = Mat.zeros(INPUT_SIZE, INPUT_SIZE, img.type())
        resized.copyTo(lb.submat(Rect(padX, padY, newW, newH)))
        resized.release()

        // ── BGR → RGB, HWC → CHW float32 normalised [0,1] ────────────────────
        val rgb = Mat(); Imgproc.cvtColor(lb, rgb, Imgproc.COLOR_BGR2RGB); lb.release()
        val buf = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
        val channels = ArrayList<Mat>(); Core.split(rgb, channels); rgb.release()
        for (ch in channels) {
            ch.convertTo(ch, CvType.CV_32F, 1.0 / 255.0)
            val arr = FloatArray(INPUT_SIZE * INPUT_SIZE); ch.get(0, 0, arr)
            buf.put(arr); ch.release()
        }
        buf.rewind()

        // ── Inference ─────────────────────────────────────────────────────────
        val tensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val outputs = session.run(mapOf(session.inputNames.first() to tensor))
        tensor.close()

        // Output shape: [1, 4+NC, numAnchors]  — same layout as worker.js
        @Suppress("UNCHECKED_CAST")
        val pred = (outputs[0].value as Array<Array<FloatArray>>)[0]
        val numAnchors = pred[0].size
        outputs.close()

        // ── Parse candidates ─────────────────────────────────────────────────
        val candidates = mutableListOf<DetBox>()
        for (i in 0 until numAnchors) {
            val conf = pred[4 + MARKER_CLASS_ID][i]
            if (conf < CONF_THRESHOLD) continue
            val cx = pred[0][i]; val cy = pred[1][i]
            val bw = pred[2][i]; val bh = pred[3][i]
            candidates.add(DetBox(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, conf))
        }
        Log.d(TAG, "[YOLO] candidates before NMS: ${candidates.size}")
        if (candidates.isEmpty()) return null

        val kept = nms(candidates, IOU_THRESHOLD)
        Log.d(TAG, "[YOLO] after NMS: ${kept.size}, best conf=${"%.3f".format(kept[0].conf)}")

        val best = kept[0]
        Log.d(TAG, "[YOLO] letterbox=(${best.x1.toInt()},${best.y1.toInt()})-(${best.x2.toInt()},${best.y2.toInt()})")

        // ── Letterbox → original coords + 12% expand ─────────────────────────
        val ox1 = (best.x1 - padX) / scale; val oy1 = (best.y1 - padY) / scale
        val ox2 = (best.x2 - padX) / scale; val oy2 = (best.y2 - padY) / scale
        val ew = (ox2 - ox1) * EXPAND_PCT; val eh = (oy2 - oy1) * EXPAND_PCT

        val x1 = (ox1 - ew).coerceIn(0f, (W - 1).toFloat()).toInt()
        val y1 = (oy1 - eh).coerceIn(0f, (H - 1).toFloat()).toInt()
        val x2 = (ox2 + ew).coerceIn(0f, W.toFloat()).toInt()
        val y2 = (oy2 + eh).coerceIn(0f, H.toFloat()).toInt()

        Log.i(TAG, "[YOLO] final bbox=($x1,$y1)-($x2,$y2) image=${W}x${H}")
        return Rect(x1, y1, x2 - x1, y2 - y1)
    }

    private fun maskOutside(img: Mat, bbox: Rect): Mat {
        val masked = Mat.zeros(img.size(), img.type())
        val roi = img.submat(bbox)
        roi.copyTo(masked.submat(bbox))
        roi.release()
        val ratio = bbox.width.toLong() * bbox.height * 100.0 / (img.cols().toLong() * img.rows())
        Log.i(TAG, "[Mask] size=${bbox.width}x${bbox.height} ratio=${"%.1f".format(ratio)}%")
        return masked
    }

    fun close() { session.close(); env.close() }
}
