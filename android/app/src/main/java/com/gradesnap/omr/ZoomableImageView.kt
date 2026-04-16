package com.gradesnap.omr

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView hỗ trợ pinch-zoom và pan.
 * Zoom range: 1x – 8x.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()

    private var oldDist = 1f
    private var minScale = 1f
    private var maxScale = 8f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val current = currentScale()
                val newScale = current * scaleFactor
                val bounded = newScale.coerceIn(minScale, maxScale)
                matrix.postScale(bounded / current, bounded / current, detector.focusX, detector.focusY)
                clampMatrix()
                imageMatrix = matrix
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                val target = if (current < 2.5f) 2.5f else minScale
                val scale = target / current
                matrix.postScale(scale, scale, e.x, e.y)
                clampMatrix()
                imageMatrix = matrix
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) fitImageToView()
    }

    private fun fitImageToView() {
        val d = drawable ?: return
        val vW = width.toFloat()
        val vH = height.toFloat()
        val iW = d.intrinsicWidth.toFloat()
        val iH = d.intrinsicHeight.toFloat()
        if (iW == 0f || iH == 0f) return

        val scale = minOf(vW / iW, vH / iH)
        minScale = scale
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((vW - iW * scale) / 2f, (vH - iH * scale) / 2f)
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> mode = ZOOM
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && !scaleDetector.isInProgress) {
                    matrix.set(savedMatrix)
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                    clampMatrix()
                    imageMatrix = matrix
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> mode = NONE
        }
        return true
    }

    private fun currentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun clampMatrix() {
        val d = drawable ?: return
        matrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val iW = d.intrinsicWidth * scale
        val iH = d.intrinsicHeight * scale
        val vW = width.toFloat()
        val vH = height.toFloat()
        var dx = matrixValues[Matrix.MTRANS_X]
        var dy = matrixValues[Matrix.MTRANS_Y]

        dx = if (iW <= vW) (vW - iW) / 2 else dx.coerceIn(vW - iW, 0f)
        dy = if (iH <= vH) (vH - iH) / 2 else dy.coerceIn(vH - iH, 0f)

        matrixValues[Matrix.MTRANS_X] = dx
        matrixValues[Matrix.MTRANS_Y] = dy
        matrix.setValues(matrixValues)
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
