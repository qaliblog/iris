package com.qali.iris

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages settings for eye tracking mouse control
 */
class SettingsManager(context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // X and Y movement multipliers
    var xMovementMultiplier: Float
        get() = prefs.getFloat(KEY_X_MOVEMENT_MULTIPLIER, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_X_MOVEMENT_MULTIPLIER, value).apply()
    
    var yMovementMultiplier: Float
        get() = prefs.getFloat(KEY_Y_MOVEMENT_MULTIPLIER, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_Y_MOVEMENT_MULTIPLIER, value).apply()
    
    // Eye position X effect - range amplifier (0 = no effect, higher = more range)
    var eyePositionXEffect: Float
        get() = prefs.getFloat(KEY_EYE_POSITION_X_EFFECT, 0f)
        set(value) = prefs.edit().putFloat(KEY_EYE_POSITION_X_EFFECT, value).apply()
    
    var eyePositionXMultiplier: Float
        get() = prefs.getFloat(KEY_EYE_POSITION_X_MULTIPLIER, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_EYE_POSITION_X_MULTIPLIER, value).apply()
    
    // Eye position Y effect - range amplifier (0 = no effect, higher = more range)
    var eyePositionYEffect: Float
        get() = prefs.getFloat(KEY_EYE_POSITION_Y_EFFECT, 0f)
        set(value) = prefs.edit().putFloat(KEY_EYE_POSITION_Y_EFFECT, value).apply()
    
    var eyePositionYMultiplier: Float
        get() = prefs.getFloat(KEY_EYE_POSITION_Y_MULTIPLIER, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_EYE_POSITION_Y_MULTIPLIER, value).apply()
    
    // Distance-based range multipliers (based on eye area)
    // 0 = no effect, positive = increases range when far, negative = reverse effect
    var distanceXMultiplier: Float
        get() = prefs.getFloat(KEY_DISTANCE_X_MULTIPLIER, 0f)
        set(value) = prefs.edit().putFloat(KEY_DISTANCE_X_MULTIPLIER, value).apply()
    
    var distanceYMultiplier: Float
        get() = prefs.getFloat(KEY_DISTANCE_Y_MULTIPLIER, 0f)
        set(value) = prefs.edit().putFloat(KEY_DISTANCE_Y_MULTIPLIER, value).apply()
    
    // Blink detection threshold (0.0-1.0, default 0.3 = 30% decrease)
    var blinkThreshold: Float
        get() = prefs.getFloat(KEY_BLINK_THRESHOLD, 0.3f)
        set(value) = prefs.edit().putFloat(KEY_BLINK_THRESHOLD, value.coerceIn(0.05f, 0.8f)).apply()
    
    // Use one eye for detection (true) or both eyes (false)
    var useOneEyeDetection: Boolean
        get() = prefs.getBoolean(KEY_USE_ONE_EYE, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_ONE_EYE, value).apply()
    
    // Cursor smoothing factor (0.0-1.0, default 0.7)
    // Higher values = more smoothing (slower response), lower = more responsive
    var cursorSmoothingFactor: Float
        get() = prefs.getFloat(KEY_CURSOR_SMOOTHING, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_CURSOR_SMOOTHING, value.coerceIn(0f, 1f)).apply()
    
    // Cursor update interval in milliseconds (default 16ms = ~60fps)
    // Lower = more frequent updates (smoother), higher = less frequent (better performance)
    var cursorUpdateInterval: Long
        get() = prefs.getLong(KEY_CURSOR_UPDATE_INTERVAL, 16L)
        set(value) = prefs.edit().putLong(KEY_CURSOR_UPDATE_INTERVAL, value.coerceIn(8L, 100L)).apply()
    
    // Cursor movement duration for gesture (default 100ms)
    // Lower = faster movement, higher = slower/smoother gesture
    var cursorMovementDuration: Long
        get() = prefs.getLong(KEY_CURSOR_MOVEMENT_DURATION, 100L)
        set(value) = prefs.edit().putLong(KEY_CURSOR_MOVEMENT_DURATION, value.coerceIn(50L, 300L)).apply()
    
    // Half-blink acceleration threshold (default 0.15)
    // Lower = more sensitive to partial blinks, higher = requires more acceleration
    var halfBlinkAccelThreshold: Float
        get() = prefs.getFloat(KEY_HALF_BLINK_ACCEL_THRESHOLD, 0.15f)
        set(value) = prefs.edit().putFloat(KEY_HALF_BLINK_ACCEL_THRESHOLD, value.coerceIn(0.05f, 0.5f)).apply()
    
    // Click delay threshold in milliseconds (default 200ms)
    // Time to account for previous click position when detecting new clicks
    var clickDelayThreshold: Long
        get() = prefs.getLong(KEY_CLICK_DELAY_THRESHOLD, 200L)
        set(value) = prefs.edit().putLong(KEY_CLICK_DELAY_THRESHOLD, value.coerceIn(0L, 1000L)).apply()
    
    // Cursor color (default blue)
    var cursorColor: Int
        get() = prefs.getInt(KEY_CURSOR_COLOR, android.graphics.Color.BLUE)
        set(value) = prefs.edit().putInt(KEY_CURSOR_COLOR, value).apply()
    
    // Click color (default green)
    var clickColor: Int
        get() = prefs.getInt(KEY_CLICK_COLOR, android.graphics.Color.GREEN)
        set(value) = prefs.edit().putInt(KEY_CLICK_COLOR, value).apply()
    
    // Drag color (default purple)
    var dragColor: Int
        get() = prefs.getInt(KEY_DRAG_COLOR, android.graphics.Color.parseColor("#9C27B0"))
        set(value) = prefs.edit().putInt(KEY_DRAG_COLOR, value).apply()

    var showLivePreview: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LIVE_PREVIEW, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LIVE_PREVIEW, value).apply()
    
    // Screen off tracking - enables PARTIAL_WAKE_LOCK to keep CPU awake when screen is off
    var screenOffTracking: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_OFF_TRACKING, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_OFF_TRACKING, value).apply()
    
    // Head direction X threshold - minimum movement to apply head direction effect on X axis
    var headDirectionXThreshold: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_X_THRESHOLD, 0.01f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_X_THRESHOLD, value.coerceIn(0f, 1f)).apply()
    
    // Head direction Y threshold - minimum movement to apply head direction effect on Y axis
    var headDirectionYThreshold: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_Y_THRESHOLD, 0.01f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_Y_THRESHOLD, value.coerceIn(0f, 1f)).apply()
    
    // Head direction X effect multiplier - how much head direction affects X movement
    var headDirectionXMultiplier: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_X_MULTIPLIER, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_X_MULTIPLIER, value).apply()
    
    // Head direction Y effect multiplier - how much head direction affects Y movement
    var headDirectionYMultiplier: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_Y_MULTIPLIER, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_Y_MULTIPLIER, value).apply()
    
    // Head direction X positive threshold - minimum positive X direction to apply effect (default 0.01)
    var headDirectionXPositiveThreshold: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_X_POSITIVE_THRESHOLD, 0.01f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_X_POSITIVE_THRESHOLD, value.coerceIn(0f, 1f)).apply()
    
    // Head direction X negative threshold - minimum negative X direction to apply effect (default -0.01)
    var headDirectionXNegativeThreshold: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_X_NEGATIVE_THRESHOLD, -0.01f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_X_NEGATIVE_THRESHOLD, value.coerceIn(-1f, 0f)).apply()
    
    // Head direction Y positive threshold - minimum positive Y direction to apply effect (default 0.01)
    var headDirectionYPositiveThreshold: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_Y_POSITIVE_THRESHOLD, 0.01f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_Y_POSITIVE_THRESHOLD, value.coerceIn(0f, 1f)).apply()
    
    // Head direction Y negative threshold - minimum negative Y direction to apply effect (default -0.01)
    var headDirectionYNegativeThreshold: Float
        get() = prefs.getFloat(KEY_HEAD_DIRECTION_Y_NEGATIVE_THRESHOLD, -0.01f)
        set(value) = prefs.edit().putFloat(KEY_HEAD_DIRECTION_Y_NEGATIVE_THRESHOLD, value.coerceIn(-1f, 0f)).apply()
    
    // Enable click detection
    var clickEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLICK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CLICK_ENABLED, value).apply()
    
    // Enable drag detection
    var dragEnabled: Boolean
        get() = prefs.getBoolean(KEY_DRAG_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DRAG_ENABLED, value).apply()
    
    // Large positive threshold for click detection (default 0.5)
    var clickPositiveThreshold: Float
        get() = prefs.getFloat(KEY_CLICK_POSITIVE_THRESHOLD, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CLICK_POSITIVE_THRESHOLD, value.coerceIn(0.1f, 2.0f)).apply()
    
    // Large negative threshold for click detection (default -0.5)
    var clickNegativeThreshold: Float
        get() = prefs.getFloat(KEY_CLICK_NEGATIVE_THRESHOLD, -0.5f)
        set(value) = prefs.edit().putFloat(KEY_CLICK_NEGATIVE_THRESHOLD, value.coerceIn(-2.0f, -0.1f)).apply()
    
    // Large positive threshold for drag detection (default 0.3)
    var dragPositiveThreshold: Float
        get() = prefs.getFloat(KEY_DRAG_POSITIVE_THRESHOLD, 0.3f)
        set(value) = prefs.edit().putFloat(KEY_DRAG_POSITIVE_THRESHOLD, value.coerceIn(0.1f, 2.0f)).apply()
    
    // Large negative threshold for drag detection (default -0.3)
    var dragNegativeThreshold: Float
        get() = prefs.getFloat(KEY_DRAG_NEGATIVE_THRESHOLD, -0.3f)
        set(value) = prefs.edit().putFloat(KEY_DRAG_NEGATIVE_THRESHOLD, value.coerceIn(-2.0f, -0.1f)).apply()
    
    companion object {
        private const val PREFS_NAME = "iris_settings"
        
        private const val KEY_X_MOVEMENT_MULTIPLIER = "x_movement_multiplier"
        private const val KEY_Y_MOVEMENT_MULTIPLIER = "y_movement_multiplier"
        private const val KEY_EYE_POSITION_X_EFFECT = "eye_position_x_effect"
        private const val KEY_EYE_POSITION_X_MULTIPLIER = "eye_position_x_multiplier"
        private const val KEY_EYE_POSITION_Y_EFFECT = "eye_position_y_effect"
        private const val KEY_EYE_POSITION_Y_MULTIPLIER = "eye_position_y_multiplier"
        private const val KEY_DISTANCE_X_MULTIPLIER = "distance_x_multiplier"
        private const val KEY_DISTANCE_Y_MULTIPLIER = "distance_y_multiplier"
        private const val KEY_BLINK_THRESHOLD = "blink_threshold"
        private const val KEY_USE_ONE_EYE = "use_one_eye"
        private const val KEY_CURSOR_SMOOTHING = "cursor_smoothing"
        private const val KEY_CURSOR_UPDATE_INTERVAL = "cursor_update_interval"
        private const val KEY_CURSOR_MOVEMENT_DURATION = "cursor_movement_duration"
        private const val KEY_HALF_BLINK_ACCEL_THRESHOLD = "half_blink_accel_threshold"
        private const val KEY_CLICK_DELAY_THRESHOLD = "click_delay_threshold"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        private const val KEY_CLICK_COLOR = "click_color"
        private const val KEY_DRAG_COLOR = "drag_color"
        private const val KEY_SHOW_LIVE_PREVIEW = "show_live_preview"
        private const val KEY_SCREEN_OFF_TRACKING = "screen_off_tracking"
        private const val KEY_HEAD_DIRECTION_X_THRESHOLD = "head_direction_x_threshold"
        private const val KEY_HEAD_DIRECTION_Y_THRESHOLD = "head_direction_y_threshold"
        private const val KEY_HEAD_DIRECTION_X_MULTIPLIER = "head_direction_x_multiplier"
        private const val KEY_HEAD_DIRECTION_Y_MULTIPLIER = "head_direction_y_multiplier"
        private const val KEY_HEAD_DIRECTION_X_POSITIVE_THRESHOLD = "head_direction_x_positive_threshold"
        private const val KEY_HEAD_DIRECTION_X_NEGATIVE_THRESHOLD = "head_direction_x_negative_threshold"
        private const val KEY_HEAD_DIRECTION_Y_POSITIVE_THRESHOLD = "head_direction_y_positive_threshold"
        private const val KEY_HEAD_DIRECTION_Y_NEGATIVE_THRESHOLD = "head_direction_y_negative_threshold"
        private const val KEY_CLICK_ENABLED = "click_enabled"
        private const val KEY_DRAG_ENABLED = "drag_enabled"
        private const val KEY_CLICK_POSITIVE_THRESHOLD = "click_positive_threshold"
        private const val KEY_CLICK_NEGATIVE_THRESHOLD = "click_negative_threshold"
        private const val KEY_DRAG_POSITIVE_THRESHOLD = "drag_positive_threshold"
        private const val KEY_DRAG_NEGATIVE_THRESHOLD = "drag_negative_threshold"
    }
}
