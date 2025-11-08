package com.qali.iris

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService for true background eye-tracking on Android 15+
 * 
 * This service bypasses Android 15 restrictions by:
 * - Starting CameraForegroundService when enabled
 * - Allowing overlay updates even when app is in background
 * - Enabling camera access when screen is off
 * 
 * Works on Android 7+ (API 24) to Android 15 (API 35)
 */
class EyeTrackingAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "EyeTrackingAccessibilityService"
        private var instance: EyeTrackingAccessibilityService? = null
        
        /**
         * Get the singleton instance of the service
         * Returns null if service is not running
         */
        fun getInstance(): EyeTrackingAccessibilityService? = instance
        
        /**
         * Check if the service is enabled and running
         */
        fun isEnabled(): Boolean {
            return instance != null && instance?.isServiceEnabled == true
        }
    }
    
    private var isServiceEnabled = false
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceEnabled = true
        
        Log.d(TAG, "EyeTrackingAccessibilityService connected")
        LogcatManager.addLog("Accessibility service connected - Background tracking enabled", "Service")
        
        // Start CameraForegroundService when accessibility service is enabled
        // This ensures camera continues working even when screen is off
        try {
            CameraForegroundService.start(this)
            Log.d(TAG, "CameraForegroundService started from accessibility service")
            LogcatManager.addLog("Camera service started from accessibility service", "Service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraForegroundService: ${e.message}", e)
            LogcatManager.addLog("Failed to start camera service: ${e.message}", "Service")
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events
        // This service is only used to bypass Android 15 restrictions
    }
    
    override fun onInterrupt() {
        // Service was interrupted - log but don't stop camera service
        // User may have temporarily disabled accessibility
        Log.w(TAG, "Accessibility service interrupted")
        LogcatManager.addLog("Accessibility service interrupted", "Service")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        Log.d(TAG, "EyeTrackingAccessibilityService destroyed")
        LogcatManager.addLog("Accessibility service destroyed", "Service")
        
        // Note: We don't stop CameraForegroundService here
        // It should continue running even if accessibility is disabled
        // The user can manually stop it via notification
        
        isServiceEnabled = false
        instance = null
    }
}
