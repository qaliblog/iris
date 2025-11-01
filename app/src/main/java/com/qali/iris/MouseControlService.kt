package com.qali.iris

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * Accessibility service for mouse control
 * This service allows the app to control the mouse/cursor anywhere on the device
 */
class MouseControlService : AccessibilityService() {
    
    companion object {
        private const val TAG = "MouseControlService"
        private var instance: MouseControlService? = null
        
        fun getInstance(): MouseControlService? = instance
        
        fun moveCursor(x: Float, y: Float) {
            instance?.performMouseMove(x, y)
        }
        
        fun performClick() {
            instance?.performMouseClick()
        }
    }
    
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastUpdateTime: Long = 0
    private var settingsManager: SettingsManager? = null
    
    fun setSettingsManager(settingsManager: SettingsManager) {
        this.settingsManager = settingsManager
    }
    
    private fun getSmoothingFactor(): Float {
        return settingsManager?.cursorSmoothingFactor ?: 0.7f
    }
    
    private fun getMovementDuration(): Long {
        return settingsManager?.cursorMovementDuration ?: 100L
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "MouseControlService connected")
        
        // Reset position on reconnect
        lastX = 0f
        lastY = 0f
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "MouseControlService destroyed")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for mouse control
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "MouseControlService interrupted")
    }
    
    /**
     * Move cursor using GestureDescription (Android 7.0+)
     */
    fun performMouseMove(x: Float, y: Float) {
        try {
            // Check if x and y are valid (not -1)
            if (x < 0 || y < 0) {
                return
            }
            
            val currentTime = System.currentTimeMillis()
            val smoothingFactor = getSmoothingFactor()
            val movementDuration = getMovementDuration()
            
            // Apply update interval throttling if configured
            val updateInterval = settingsManager?.cursorUpdateInterval ?: 16L
            if (lastUpdateTime > 0 && (currentTime - lastUpdateTime) < updateInterval) {
                return // Skip this update if too soon
            }
            lastUpdateTime = currentTime
            
            // Smooth the movement
            val smoothedX = if (lastX == 0f && lastY == 0f) x else lastX + (x - lastX) * (1 - smoothingFactor)
            val smoothedY = if (lastX == 0f && lastY == 0f) y else lastY + (y - lastY) * (1 - smoothingFactor)
            
            lastX = smoothedX
            lastY = smoothedY
            
            // Create path from current position to new position for smooth movement
            val path = android.graphics.Path().apply {
                moveTo(lastX, lastY)
                lineTo(smoothedX, smoothedY)
            }
            
            // Use dispatchGesture for Android 7.0+
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path,
                        0,
                        movementDuration // Duration for movement (configurable)
                    )
                )
                .build()
            
            dispatchGesture(gesture, null, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error moving cursor: ${e.message}", e)
        }
    }
    
    /**
     * Perform a click at the current cursor position
     */
    fun performMouseClick() {
        try {
            // Check if we have a valid position
            if (lastX <= 0 || lastY <= 0) {
                Log.w(TAG, "Cannot perform click: invalid position")
                return
            }
            
            val clickPath = android.graphics.Path().apply {
                moveTo(lastX, lastY)
                lineTo(lastX, lastY)
            }
            
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        clickPath,
                        0,
                        100
                    )
                )
                .build()
            
            dispatchGesture(gesture, object : 
                android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Click completed")
                }
                
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Click cancelled")
                }
            }, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click: ${e.message}", e)
        }
    }
    
}
