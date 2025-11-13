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
        
        var finalMovementX = adjustedMovementX * distanceXRange
        var finalMovementY = adjustedMovementY * distanceYRange
        
        // Apply head direction effect with thresholds
        result.headDirection?.let { headDir ->
            // Get head direction components (-1 to 1)
            val headDirX = headDir.directionX
            val headDirY = headDir.directionY
            
            // Apply thresholds - only apply head direction if it exceeds threshold
            val headDirXEffect = if (kotlin.math.abs(headDirX) >= settings.headDirectionXThreshold) {
                headDirX * settings.headDirectionXMultiplier
            } else {
                0f // Below threshold, no effect
            }
            
            val headDirYEffect = if (kotlin.math.abs(headDirY) >= settings.headDirectionYThreshold) {
                headDirY * settings.headDirectionYMultiplier
            } else {
                0f // Below threshold, no effect
            }
            
            // Apply head direction effect to movement
            // Head direction is normalized (-1 to 1), so we scale it appropriately
            finalMovementX += headDirXEffect * 0.5f // Scale to match movement range
            finalMovementY += headDirYEffect * 0.5f
        }
        
        // Apply movement multipliers (overall X/Y range)
        val finalX = screenCenterX + (finalMovementX * settings.xMovementMultiplier * displayMetrics.widthPixels)
        val finalY = screenCenterY + (finalMovementY * settings.yMovementMultiplier * displayMetrics.heightPixels)
        
        // Clamp to screen bounds
        val adjustedX = finalX.coerceIn(0f, displayMetrics.widthPixels.toFloat())
        val adjustedY = finalY.coerceIn(0f, displayMetrics.heightPixels.toFloat())
        
        return Pair(adjustedX, adjustedY)
    }
}
