package com.qali.iris

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {
    
    init {
        // Don't intercept touch events - let them pass through to views behind
        isClickable = false
        isFocusable = false
    }
    
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Let touches pass through to views behind (like the settings button)
        return false
    }

    private var results: FaceLandmarkerResult? = null
    private var leftEyeLinePaint = Paint()
    private var rightEyeLinePaint = Paint()
    private var leftEyePupilPaint = Paint()
    private var rightEyePupilPaint = Paint()
    private var eyeTracker: EyeTracker? = null
    private var pointerX: Float = -1f
    private var pointerY: Float = -1f

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    init {
        initPaints()
    }
    
    fun setEyeTracker(tracker: EyeTracker) {
        eyeTracker = tracker
    }

    fun clear() {
        results = null
        pointerX = -1f
        pointerY = -1f
        // Post invalidate to main thread to ensure thread safety
        post { invalidate() }
    }
    
    fun setPointerPosition(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        // Post invalidate to main thread to ensure thread safety
        post { invalidate() }
    }

    private fun initPaints() {
        // Left eye (green) - from user's perspective (right eye on face)
        leftEyeLinePaint.color = Color.GREEN
        leftEyeLinePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        leftEyeLinePaint.style = Paint.Style.STROKE
        leftEyeLinePaint.strokeCap = Paint.Cap.ROUND
        
        // Right eye (blue) - from user's perspective (left eye on face)
        rightEyeLinePaint.color = Color.BLUE
        rightEyeLinePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        rightEyeLinePaint.style = Paint.Style.STROKE
        rightEyeLinePaint.strokeCap = Paint.Cap.ROUND
        
        // Pupils
        leftEyePupilPaint.color = Color.GREEN
        leftEyePupilPaint.strokeWidth = PUPIL_STROKE_WIDTH
        leftEyePupilPaint.style = Paint.Style.FILL
        
        rightEyePupilPaint.color = Color.BLUE
        rightEyePupilPaint.strokeWidth = PUPIL_STROKE_WIDTH
        rightEyePupilPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw pointer if available
        if (pointerX >= 0 && pointerY >= 0) {
            drawPointer(canvas, pointerX, pointerY)
        }

        // Clear previous drawings if results exist but have no face landmarks
        if (results?.faceLandmarks().isNullOrEmpty()) {
            return
        }

        results?.let { faceLandmarkerResult ->
            // Calculate scaled image dimensions
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor

            // Calculate offsets to center the image on the canvas
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            // Iterate through each detected face
            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                drawEyeLandmarks(canvas, faceLandmarks, offsetX, offsetY)
            }
        }
    }
    
    private fun drawPointer(canvas: Canvas, x: Float, y: Float) {
        val pointerPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 10f
            style = Paint.Style.FILL
        }
        val outerPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 3f
            style = Paint.Style.STROKE
            alpha = 128
        }
        
        // Draw crosshair pointer
        val size = 30f
        canvas.drawCircle(x, y, size / 2, outerPaint)
        canvas.drawLine(x - size, y, x + size, y, pointerPaint)
        canvas.drawLine(x, y - size, x, y + size, pointerPaint)
        canvas.drawCircle(x, y, 5f, pointerPaint)
    }

    /**
     * Draws only eye landmarks (eye lines and pupils) with different colors
     */
    private fun drawEyeLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        val tracker = eyeTracker ?: return
        
        // Get eye indices
        val leftEyeIndices = tracker.getLeftEyeIndices()
        val rightEyeIndices = tracker.getRightEyeIndices()
        
        // Draw left eye (green) - eye line
        drawEyeLine(canvas, faceLandmarks, leftEyeIndices, offsetX, offsetY, leftEyeLinePaint)
        
        // Draw left eye pupil (calculated center)
        val leftEyePoints = leftEyeIndices.mapNotNull { faceLandmarks.getOrNull(it) }
        if (leftEyePoints.isNotEmpty()) {
            val centerX = leftEyePoints.map { it.x() }.average().toFloat() * imageWidth * scaleFactor + offsetX
            val centerY = leftEyePoints.map { it.y() }.average().toFloat() * imageHeight * scaleFactor + offsetY
            canvas.drawCircle(centerX, centerY, PUPIL_RADIUS, leftEyePupilPaint)
        }
        
        // Draw right eye (blue) - eye line
        drawEyeLine(canvas, faceLandmarks, rightEyeIndices, offsetX, offsetY, rightEyeLinePaint)
        
        // Draw right eye pupil (calculated center)
        val rightEyePoints = rightEyeIndices.mapNotNull { faceLandmarks.getOrNull(it) }
        if (rightEyePoints.isNotEmpty()) {
            val centerX = rightEyePoints.map { it.x() }.average().toFloat() * imageWidth * scaleFactor + offsetX
            val centerY = rightEyePoints.map { it.y() }.average().toFloat() * imageHeight * scaleFactor + offsetY
            canvas.drawCircle(centerX, centerY, PUPIL_RADIUS, rightEyePupilPaint)
        }
    }
    
    private fun drawEyeLine(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        eyeIndices: List<Int>,
        offsetX: Float,
        offsetY: Float,
        paint: Paint
    ) {
        if (eyeIndices.size < 2) return
        
        // Draw connected line through eye landmarks
        var prevPoint: Pair<Float, Float>? = null
        eyeIndices.forEach { index ->
            val landmark = faceLandmarks.getOrNull(index)
            landmark?.let {
                val x = it.x() * imageWidth * scaleFactor + offsetX
                val y = it.y() * imageHeight * scaleFactor + offsetY
                
                prevPoint?.let { prev ->
                    canvas.drawLine(prev.first, prev.second, x, y, paint)
                }
                prevPoint = Pair(x, y)
            }
        }
        
        // Connect last to first for closed loop
        if (eyeIndices.isNotEmpty() && prevPoint != null) {
            val firstLandmark = faceLandmarks.getOrNull(eyeIndices.first())
            firstLandmark?.let {
                val x = it.x() * imageWidth * scaleFactor + offsetX
                val y = it.y() * imageHeight * scaleFactor + offsetY
                canvas.drawLine(prevPoint!!.first, prevPoint!!.second, x, y, paint)
            }
        }
    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        // Post invalidate to main thread to ensure thread safety
        post { invalidate() }
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 6F
        private const val PUPIL_STROKE_WIDTH = 8F
        private const val PUPIL_RADIUS = 8F
        private const val TAG = "Eye Overlay"
    }
}
