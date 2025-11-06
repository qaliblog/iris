package com.qali.iris

import android.graphics.PointF
import android.util.DisplayMetrics
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Eye tracking utility that calculates eye position based on eye landmarks
 * and maps it to screen coordinates for mouse control
 */
class EyeTracker(
    private val displayMetrics: DisplayMetrics,
    private var useOneEye: Boolean = false
) {
    
    /**
     * Set whether to use one eye (true) or both eyes (false) for detection
     */
    fun setUseOneEye(useOneEye: Boolean) {
        this.useOneEye = useOneEye
    }
    
    companion object {
        private const val TAG = "EyeTracker"
        
        // MediaPipe face landmark indices for eyes (468 landmarks)
        // Left eye (from user's perspective, right eye on face) - indices 33-42
        // Right eye (from user's perspective, left eye on face) - indices 362-373
        
        // Eye line landmarks - these form the eye contour
        // Left eye contour (right eye from user's view)
        private val LEFT_EYE_LINE_INDICES = listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
        
        // Right eye contour (left eye from user's view)  
        private val RIGHT_EYE_LINE_INDICES = listOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)
        
        // Pupil centers - MediaPipe has 468 landmarks (0-467)
        // Use approximate center indices or calculate from eye region
        private const val LEFT_EYE_PUPIL_CENTER = 468 // Will be calculated from eye region if not available
        private const val RIGHT_EYE_PUPIL_CENTER = 473 // Will be calculated from eye region if not available
    }
    
    data class EyeRegion(
        val center: PointF,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val width: Float,
        val height: Float
    )
    
    data class EyelidLandmarks(
        val upperLidY: Float, // Y position of upper eyelid (normalized 0-1, lower Y = more closed)
        val lowerLidY: Float  // Y position of lower eyelid (normalized 0-1, higher Y = more closed)
    )
    
    data class TrackingResult(
        val leftEyeRegion: EyeRegion?,
        val rightEyeRegion: EyeRegion?,
        val combinedCenter: PointF?,
        val screenX: Float,
        val screenY: Float,
        val eyeArea: Float = 0f, // Combined eye area (0 = biggest, increases as eyes get farther)
        val eyePositionX: Float = 0f, // Normalized X position of eyes (0-1)
        val eyePositionY: Float = 0f,  // Normalized Y position of eyes (0-1)
        val leftEyelidLandmarks: EyelidLandmarks? = null, // Eyelid landmarks for left eye
        val rightEyelidLandmarks: EyelidLandmarks? = null  // Eyelid landmarks for right eye
    )
    
    /**
     * Calculate eye region based on eye line landmarks
     * Returns a rectangle area in the middle of the eye
     */
    private fun calculateEyeRegion(
        landmarks: List<NormalizedLandmark>,
        eyeIndices: List<Int>
    ): EyeRegion? {
        if (eyeIndices.isEmpty()) return null
        
        val eyePoints = eyeIndices.mapNotNull { index ->
            landmarks.getOrNull(index)
        }
        
        if (eyePoints.isEmpty()) return null
        
        // Calculate bounding box
        val left = eyePoints.minOf { it.x() }
        val right = eyePoints.maxOf { it.x() }
        val top = eyePoints.minOf { it.y() }
        val bottom = eyePoints.maxOf { it.y() }
        
        val width = right - left
        val height = bottom - top
        
        // Calculate center of eye area
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        
        // Create a rectangle in the middle (80% of eye area)
        val rectWidth = width * 0.8f
        val rectHeight = height * 0.8f
        val rectLeft = centerX - rectWidth / 2f
        val rectRight = centerX + rectWidth / 2f
        val rectTop = centerY - rectHeight / 2f
        val rectBottom = centerY + rectHeight / 2f
        
        return EyeRegion(
            center = PointF(centerX, centerY),
            left = rectLeft,
            top = rectTop,
            right = rectRight,
            bottom = rectBottom,
            width = rectWidth,
            height = rectHeight
        )
    }
    
    /**
     * Get pupil position from landmarks
     * If specific pupil index doesn't exist, calculate center from eye landmarks
     */
    private fun getPupilPosition(
        landmarks: List<NormalizedLandmark>,
        pupilIndex: Int,
        eyeIndices: List<Int>
    ): PointF? {
        // Try to get specific pupil landmark
        val pupil = landmarks.getOrNull(pupilIndex)
        if (pupil != null) {
            return PointF(pupil.x(), pupil.y())
        }
        
        // Fallback: calculate center from eye region
        val eyePoints = eyeIndices.mapNotNull { landmarks.getOrNull(it) }
        if (eyePoints.isEmpty()) return null
        
        val centerX = eyePoints.map { it.x() }.average().toFloat()
        val centerY = eyePoints.map { it.y() }.average().toFloat()
        return PointF(centerX, centerY)
    }
    
    /**
     * Extract upper and lower eyelid positions from eye landmarks
     */
    private fun extractEyelidLandmarks(
        landmarks: List<NormalizedLandmark>,
        eyeIndices: List<Int>
    ): EyelidLandmarks? {
        if (eyeIndices.isEmpty()) return null
        
        val eyePoints = eyeIndices.mapNotNull { index ->
            landmarks.getOrNull(index)?.let { landmark ->
                Pair(landmark.x(), landmark.y())
            }
        }
        
        if (eyePoints.isEmpty()) return null
        
        // Find upper and lower eyelids based on Y position
        // Upper lid has lower Y values, lower lid has higher Y values
        val upperLidY = eyePoints.minOf { it.second } // Minimum Y = topmost point (upper lid)
        val lowerLidY = eyePoints.maxOf { it.second } // Maximum Y = bottommost point (lower lid)
        
        return EyelidLandmarks(
            upperLidY = upperLidY,
            lowerLidY = lowerLidY
        )
    }
    
    /**
     * Track eyes and calculate screen coordinates
     */
    fun trackEyes(landmarks: List<NormalizedLandmark>): TrackingResult {
        val leftEyeRegion = calculateEyeRegion(landmarks, LEFT_EYE_LINE_INDICES)
        val rightEyeRegion = calculateEyeRegion(landmarks, RIGHT_EYE_LINE_INDICES)
        
        // Extract eyelid landmarks
        val leftEyelidLandmarks = extractEyelidLandmarks(landmarks, LEFT_EYE_LINE_INDICES)
        val rightEyelidLandmarks = extractEyelidLandmarks(landmarks, RIGHT_EYE_LINE_INDICES)
        
        // Calculate pupil positions from eye regions (MediaPipe doesn't have specific pupil landmarks)
        val leftPupil = getPupilPosition(landmarks, LEFT_EYE_PUPIL_CENTER, LEFT_EYE_LINE_INDICES)
        val rightPupil = getPupilPosition(landmarks, RIGHT_EYE_PUPIL_CENTER, RIGHT_EYE_LINE_INDICES)
        
        // Calculate combined center - use one eye or both based on settings
        val combinedCenter = if (useOneEye) {
            // Use one eye only - prefer right eye (left from user's perspective)
            rightEyeRegion?.center ?: leftEyeRegion?.center
        } else {
            // Use both eyes - average of both
            when {
                leftEyeRegion != null && rightEyeRegion != null -> {
                    // Average of both eye centers
                    PointF(
                        (leftEyeRegion.center.x + rightEyeRegion.center.x) / 2f,
                        (leftEyeRegion.center.y + rightEyeRegion.center.y) / 2f
                    )
                }
                leftEyeRegion != null -> leftEyeRegion.center
                rightEyeRegion != null -> rightEyeRegion.center
                else -> null
            }
        }
        
        // If we have pupils, use weighted average (pupils are more accurate)
        val finalPoint = if (useOneEye) {
            // Use one eye only - prefer right pupil (left from user's perspective)
            rightPupil ?: leftPupil ?: combinedCenter
        } else {
            // Use both eyes
            when {
                leftPupil != null && rightPupil != null -> {
                    // Weighted average: 60% pupils, 40% eye regions
                    val pupilCenter = PointF(
                        (leftPupil.x + rightPupil.x) / 2f,
                        (leftPupil.y + rightPupil.y) / 2f
                    )
                    if (combinedCenter != null) {
                        PointF(
                            pupilCenter.x * 0.6f + combinedCenter.x * 0.4f,
                            pupilCenter.y * 0.6f + combinedCenter.y * 0.4f
                        )
                    } else {
                        pupilCenter
                    }
                }
                leftPupil != null -> leftPupil
                rightPupil != null -> rightPupil
                else -> combinedCenter
            }
        }
        
        // Calculate eye area (larger area = closer to screen, smaller = farther)
        // Use one eye or both based on settings
        val eyeArea: Float = if (useOneEye) {
            // Use one eye only - prefer right eye
            (rightEyeRegion?.let { it.width * it.height } ?: leftEyeRegion?.let { it.width * it.height }) ?: 0f
        } else {
            // Use both eyes - average area
            when {
                leftEyeRegion != null && rightEyeRegion != null -> {
                    val avgArea = (leftEyeRegion.width * leftEyeRegion.height + rightEyeRegion.width * rightEyeRegion.height) / 2f
                    avgArea
                }
                leftEyeRegion != null -> leftEyeRegion.width * leftEyeRegion.height
                rightEyeRegion != null -> rightEyeRegion.width * rightEyeRegion.height
                else -> 0f
            }
        }
        
        // Calculate distance metric: 0 for biggest area (closest), increases as area decreases
        // We normalize based on typical eye area ranges (this will be calibrated)
        val maxExpectedArea = 0.01f // Maximum expected eye area in normalized coordinates
        val distance = if (eyeArea > 0) {
            (maxExpectedArea - eyeArea.coerceAtMost(maxExpectedArea)) / maxExpectedArea
        } else {
            0f
        }
        
        // Get eye position for effect calculations
        val eyePosX = finalPoint?.x ?: 0.5f
        val eyePosY = finalPoint?.y ?: 0.5f
        
        // Map normalized coordinates (0-1) to screen coordinates (base position)
        val baseScreenX = if (finalPoint != null) {
            finalPoint.x * displayMetrics.widthPixels
        } else {
            displayMetrics.widthPixels / 2f
        }
        
        val baseScreenY = if (finalPoint != null) {
            finalPoint.y * displayMetrics.heightPixels
        } else {
            displayMetrics.heightPixels / 2f
        }
        
        // Calculate combined eyelid landmarks (average of both eyes or use one eye)
        val combinedEyelidLandmarks = if (useOneEye) {
            // Use one eye only - prefer right eye (left from user's perspective)
            rightEyelidLandmarks ?: leftEyelidLandmarks
        } else {
            // Use both eyes - average the positions
            when {
                leftEyelidLandmarks != null && rightEyelidLandmarks != null -> {
                    EyelidLandmarks(
                        upperLidY = (leftEyelidLandmarks.upperLidY + rightEyelidLandmarks.upperLidY) / 2f,
                        lowerLidY = (leftEyelidLandmarks.lowerLidY + rightEyelidLandmarks.lowerLidY) / 2f
                    )
                }
                leftEyelidLandmarks != null -> leftEyelidLandmarks
                rightEyelidLandmarks != null -> rightEyelidLandmarks
                else -> null
            }
        }
        
        return TrackingResult(
            leftEyeRegion = leftEyeRegion,
            rightEyeRegion = rightEyeRegion,
            combinedCenter = finalPoint,
            screenX = baseScreenX,
            screenY = baseScreenY,
            eyeArea = distance, // Distance: 0 = closest, increases as farther
            eyePositionX = eyePosX,
            eyePositionY = eyePosY,
            leftEyelidLandmarks = leftEyelidLandmarks,
            rightEyelidLandmarks = rightEyelidLandmarks
        )
    }
    
    /**
     * Get eye landmark indices for drawing
     */
    fun getLeftEyeIndices(): List<Int> {
        // Return line indices only (pupil is calculated, not a specific landmark)
        return LEFT_EYE_LINE_INDICES
    }
    
    fun getRightEyeIndices(): List<Int> {
        // Return line indices only (pupil is calculated, not a specific landmark)
        return RIGHT_EYE_LINE_INDICES
    }
}
