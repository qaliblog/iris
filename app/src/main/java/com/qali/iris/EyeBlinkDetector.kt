package com.qali.iris

/**
 * Detects eye blinks (rapid close-open pattern) for click functionality
 */
class EyeBlinkDetector(initialBlinkThreshold: Float = 0.3f) {
    
    companion object {
        private const val TAG = "EyeBlinkDetector"
        private const val BLINK_DETECTION_WINDOW_MS = 500L // Max time for a blink (close-open)
        private const val BLINK_THRESHOLD_INCREASE = 0.2f // Eye area must increase by 20% to be considered "open"
        private const val MIN_TIME_BETWEEN_BLINKS_MS = 300L // Minimum time between clicks
    }
    
    private var blinkThreshold: Float = initialBlinkThreshold
    
    private data class EyeState(
        val timestamp: Long,
        val eyeArea: Float,
        val isClosed: Boolean
    )
    
    private var lastEyeStates = mutableListOf<EyeState>()
    private var lastClickTime = 0L
    private var baselineEyeArea = 0f
    private var baselineSet = false
    
    /**
     * Update the blink threshold dynamically
     */
    fun setBlinkThreshold(threshold: Float) {
        blinkThreshold = threshold
    }
    
    /**
     * Process eye area and detect blink pattern
     * Returns true if a blink (click) was detected
     */
    fun processEyeArea(eyeArea: Float): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Set baseline on first call or if eyes seem stable
        if (!baselineSet || baselineEyeArea == 0f) {
            baselineEyeArea = eyeArea
            baselineSet = true
            lastEyeStates.clear()
            return false
        }
        
        // Calculate relative change from baseline
        val relativeArea = if (baselineEyeArea > 0) eyeArea / baselineEyeArea else 1f
        val isCurrentlyClosed = relativeArea < (1f - blinkThreshold)
        
        // Update baseline slowly (adaptive)
        baselineEyeArea = baselineEyeArea * 0.95f + eyeArea * 0.05f
        
        // Add current state
        lastEyeStates.add(
            EyeState(
                timestamp = currentTime,
                eyeArea = relativeArea,
                isClosed = isCurrentlyClosed
            )
        )
        
        // Keep only recent states (within detection window)
        lastEyeStates.removeAll { currentTime - it.timestamp > BLINK_DETECTION_WINDOW_MS }
        
        // Check for blink pattern: closed then opened within window
        if (lastEyeStates.size >= 3) {
            // Look for pattern: open -> closed -> open
            for (i in 0 until lastEyeStates.size - 2) {
                val state1 = lastEyeStates[i]
                val state2 = lastEyeStates[i + 1]
                val state3 = lastEyeStates.last()
                
                val timeDiff = state3.timestamp - state1.timestamp
                
                // Check if pattern matches: was open, then closed, then open again
                if (!state1.isClosed && state2.isClosed && !state3.isClosed &&
                    timeDiff <= BLINK_DETECTION_WINDOW_MS &&
                    (currentTime - lastClickTime) >= MIN_TIME_BETWEEN_BLINKS_MS) {
                    
                    // Verify significant change
                    val closeRatio = state2.eyeArea
                    val openRatio = state3.eyeArea
                    val decreaseAmount = 1f - closeRatio
                    val increaseAmount = openRatio - closeRatio
                    
                    if (decreaseAmount >= blinkThreshold && 
                        increaseAmount >= BLINK_THRESHOLD_INCREASE) {
                        
                        lastClickTime = currentTime
                        lastEyeStates.clear() // Reset after detection
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    /**
     * Reset detector (useful when user looks away)
     */
    fun reset() {
        baselineSet = false
        baselineEyeArea = 0f
        lastEyeStates.clear()
    }
}
