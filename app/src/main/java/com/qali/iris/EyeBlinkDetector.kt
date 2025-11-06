package com.qali.iris

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects eye blinks using acceleration-based detection of eyelid landmarks
 * Detects both full blinks and half-blinks based on acceleration changes
 */
class EyeBlinkDetector(
    initialBlinkThreshold: Float = 0.3f,
    initialHalfBlinkAccelThreshold: Float = 0.15f,
    initialClickDelayThreshold: Long = 200L
) {
    
    companion object {
        private const val TAG = "EyeBlinkDetector"
        private const val BLINK_DETECTION_WINDOW_MS = 500L // Max time for a blink (close-open)
        private const val MIN_TIME_BETWEEN_BLINKS_MS = 300L // Minimum time between clicks
        private const val ACCELERATION_HISTORY_SIZE = 5 // Number of recent acceleration values to track
    }
    
    private var blinkThreshold: Float = initialBlinkThreshold
    private var halfBlinkAccelThreshold: Float = initialHalfBlinkAccelThreshold
    private var clickDelayThreshold: Long = initialClickDelayThreshold
    
    // Store eyelid landmark positions and their history
    private data class EyelidState(
        val timestamp: Long,
        val upperLidY: Float, // Y position of upper eyelid (lower Y = more closed)
        val lowerLidY: Float, // Y position of lower eyelid (higher Y = more closed)
        val eyeOpenness: Float, // Calculated eye openness (0 = closed, 1 = fully open)
        val velocity: Float, // Rate of change of eye openness
        val acceleration: Float // Rate of change of velocity (acceleration)
    )
    
    private var lastEyelidStates = mutableListOf<EyelidState>()
    private var lastClickTime = 0L
    private var lastClickPosition: PointF? = null // Store position where last click happened
    private var baselineOpenness = 1f
    private var baselineSet = false
    
    /**
     * Update the blink threshold dynamically
     */
    fun setBlinkThreshold(threshold: Float) {
        blinkThreshold = threshold
    }
    
    /**
     * Set half-blink acceleration threshold
     */
    fun setHalfBlinkAccelThreshold(threshold: Float) {
        halfBlinkAccelThreshold = threshold
    }
    
    /**
     * Set click delay threshold (time to account for previous click position)
     */
    fun setClickDelayThreshold(delayMs: Long) {
        clickDelayThreshold = delayMs
    }
    
    /**
     * Process eyelid landmarks and detect blink using acceleration
     * @param upperLidY Y position of upper eyelid landmark (normalized 0-1, lower = more closed)
     * @param lowerLidY Y position of lower eyelid landmark (normalized 0-1, higher = more closed)
     * @param clickPosition Position where click should happen (for relative calculations)
     * @return true if a blink (click) was detected
     */
    fun processEyelidLandmarks(
        upperLidY: Float,
        lowerLidY: Float,
        clickPosition: PointF? = null
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Calculate eye openness from eyelid positions
        // Eye openness = distance between upper and lower lids (normalized)
        // Make it relative to click position if provided
        val eyeOpenness = if (clickPosition != null && lastClickPosition != null) {
            // Calculate relative to previous click position
            val relativeUpperY = upperLidY - (lastClickPosition!!.y * 0.5f) // Adjust for relative position
            val relativeLowerY = lowerLidY + (lastClickPosition!!.y * 0.5f)
            (relativeLowerY - relativeUpperY).coerceIn(0f, 1f)
        } else {
            // Absolute calculation
            (lowerLidY - upperLidY).coerceIn(0f, 1f)
        }
        
        // Set baseline on first call
        if (!baselineSet) {
            baselineOpenness = eyeOpenness
            baselineSet = true
            lastEyelidStates.clear()
            return false
        }
        
        // Calculate velocity (rate of change of openness)
        val velocity = if (lastEyelidStates.isNotEmpty()) {
            val lastState = lastEyelidStates.last()
            val timeDelta = (currentTime - lastState.timestamp).coerceAtLeast(1L).toFloat()
            (eyeOpenness - lastState.eyeOpenness) / (timeDelta / 1000f) // Change per second
        } else {
            0f
        }
        
        // Calculate acceleration (rate of change of velocity)
        val acceleration = if (lastEyelidStates.size >= 2) {
            val lastState = lastEyelidStates.last()
            val prevState = lastEyelidStates[lastEyelidStates.size - 2]
            val timeDelta = (currentTime - lastState.timestamp).coerceAtLeast(1L).toFloat()
            val velocityDelta = velocity - lastState.velocity
            velocityDelta / (timeDelta / 1000f) // Acceleration per second squared
        } else {
            0f
        }
        
        // Add current state
        lastEyelidStates.add(
            EyelidState(
                timestamp = currentTime,
                upperLidY = upperLidY,
                lowerLidY = lowerLidY,
                eyeOpenness = eyeOpenness,
                velocity = velocity,
                acceleration = acceleration
            )
        )
        
        // Keep only recent states (within detection window)
        lastEyelidStates.removeAll { currentTime - it.timestamp > BLINK_DETECTION_WINDOW_MS }
        
        // Check for blink using acceleration-based detection
        if (lastEyelidStates.size >= 3) {
            // Check minimum time between clicks (accounting for delay threshold)
            val timeSinceLastClick = currentTime - lastClickTime
            val effectiveMinTime = MIN_TIME_BETWEEN_BLINKS_MS + clickDelayThreshold
            
            if (timeSinceLastClick >= effectiveMinTime) {
                // Look for acceleration pattern indicating a blink
                // Negative acceleration = closing (eyelids moving together)
                // Positive acceleration = opening (eyelids moving apart)
                
                // Full blink: significant negative acceleration followed by positive acceleration
                val recentStates = lastEyelidStates.takeLast(3)
                if (recentStates.size >= 3) {
                    val state1 = recentStates[0]
                    val state2 = recentStates[1]
                    val state3 = recentStates[2]
                    
                    // Check for closing acceleration (negative) followed by opening acceleration (positive)
                    val closingAccel = state2.acceleration // Should be negative (closing)
                    val openingAccel = state3.acceleration // Should be positive (opening)
                    
                    // Full blink detection: significant closing then opening
                    if (closingAccel < -blinkThreshold && openingAccel > blinkThreshold * 0.5f) {
                        // Verify the eye actually closed and opened
                        val closedRatio = state2.eyeOpenness
                        val openedRatio = state3.eyeOpenness
                        
                        if (closedRatio < (1f - blinkThreshold) && openedRatio > closedRatio + 0.2f) {
                            lastClickTime = currentTime
                            lastClickPosition = clickPosition
                            lastEyelidStates.clear() // Reset after detection
                            return true
                        }
                    }
                    
                    // Half-blink detection: significant acceleration change without full close
                    // This detects partial blinks based on acceleration threshold
                    val accelChange = abs(openingAccel - closingAccel)
                    if (accelChange >= halfBlinkAccelThreshold) {
                        // Check if there was a meaningful change in openness
                        val opennessChange = abs(state3.eyeOpenness - state1.eyeOpenness)
                        if (opennessChange >= halfBlinkAccelThreshold * 0.5f) {
                            // Half-blink detected
                            lastClickTime = currentTime
                            lastClickPosition = clickPosition
                            lastEyelidStates.clear() // Reset after detection
                            return true
                        }
                    }
                }
            }
        }
        
        return false
    }
    
    /**
     * Process eye area (backward compatibility)
     * Converts eye area to approximate eyelid positions
     */
    fun processEyeArea(eyeArea: Float): Boolean {
        // Convert eye area to approximate eyelid positions
        // Larger area = more open = larger distance between lids
        val normalizedArea = eyeArea.coerceIn(0f, 1f)
        val upperLidY = 0.4f - (normalizedArea * 0.2f) // Upper lid moves down as area increases
        val lowerLidY = 0.6f + (normalizedArea * 0.2f) // Lower lid moves up as area increases
        
        return processEyelidLandmarks(upperLidY, lowerLidY, null)
    }
    
    /**
     * Reset detector (useful when user looks away)
     */
    fun reset() {
        baselineSet = false
        baselineOpenness = 1f
        lastEyelidStates.clear()
        lastClickPosition = null
    }
}
