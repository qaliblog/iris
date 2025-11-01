package com.qali.iris

/**
 * Calculates final screen coordinates with all adjustments applied
 * Effects amplify movement range, not offset position
 */
class TrackingCalculator(private val settings: SettingsManager, private val displayMetrics: android.util.DisplayMetrics) {
    
    fun calculateAdjustedPosition(result: EyeTracker.TrackingResult): Pair<Float, Float> {
        // Base position from eye tracking (normalized 0-1, centered at 0.5)
        val baseX = result.eyePositionX // Normalized X position (0-1)
        val baseY = result.eyePositionY // Normalized Y position (0-1)
        
        // Center position (screen center)
        val screenCenterX = displayMetrics.widthPixels / 2f
        val screenCenterY = displayMetrics.heightPixels / 2f
        
        // Calculate movement from center (normalized -0.5 to 0.5)
        val movementX = (baseX - 0.5f) // Range: -0.5 to 0.5
        val movementY = (baseY - 0.5f) // Range: -0.5 to 0.5
        
        // Apply eye position X effect as range amplifier (0 = no effect, higher = more range)
        // This multiplies the X movement range, not offsets the position
        val xRangeMultiplier = if (settings.eyePositionXEffect == 0f) 1f else (1f + settings.eyePositionXEffect * settings.eyePositionXMultiplier)
        val adjustedMovementX = movementX * xRangeMultiplier
        
        // Apply eye position Y effect as range amplifier (0 = no effect, higher = more range)
        val yRangeMultiplier = if (settings.eyePositionYEffect == 0f) 1f else (1f + settings.eyePositionYEffect * settings.eyePositionYMultiplier)
        val adjustedMovementY = movementY * yRangeMultiplier
        
        // Apply distance-based range multipliers (amplifies movement based on eye distance)
        // Distance: 0 = closest, increases as farther away
        // When distance > 0, apply multiplier to increase range (or decrease if negative)
        val distanceXRange = if (settings.distanceXMultiplier == 0f) 1f else (1f + result.eyeArea * settings.distanceXMultiplier)
        val distanceYRange = if (settings.distanceYMultiplier == 0f) 1f else (1f + result.eyeArea * settings.distanceYMultiplier)
        
        val finalMovementX = adjustedMovementX * distanceXRange
        val finalMovementY = adjustedMovementY * distanceYRange
        
        // Apply movement multipliers (overall X/Y range)
        val finalX = screenCenterX + (finalMovementX * settings.xMovementMultiplier * displayMetrics.widthPixels)
        val finalY = screenCenterY + (finalMovementY * settings.yMovementMultiplier * displayMetrics.heightPixels)
        
        // Clamp to screen bounds
        val adjustedX = finalX.coerceIn(0f, displayMetrics.widthPixels.toFloat())
        val adjustedY = finalY.coerceIn(0f, displayMetrics.heightPixels.toFloat())
        
        return Pair(adjustedX, adjustedY)
    }
}
