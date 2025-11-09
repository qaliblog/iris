package com.qali.iris

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.qali.iris.PointerView

/**
 * Service that displays a floating pointer overlay on top of all apps
 * This allows the pointer to be visible even when the app is in background
 */
class PointerOverlayService : Service() {
    
    companion object {
        private const val TAG = "PointerOverlayService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "pointer_overlay_channel"
        private var instance: PointerOverlayService? = null
        
        fun getInstance(): PointerOverlayService? = instance
        
        /**
         * Check if the app is currently in the foreground
         * Required for Android 15 overlay update restrictions
         */
        fun isAppInForeground(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            
            val appProcesses = activityManager.runningAppProcesses ?: return false
            val packageName = context.packageName
            
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName == packageName) {
                    return true
                }
            }
            return false
        }
        
        fun updatePointerPosition(x: Float, y: Float) {
            instance?.let { service ->
                Log.d(TAG, "updatePointerPosition called: x=$x, y=$y")
                if (x < 0 || y < 0) {
                    Log.d(TAG, "updatePointerPosition: Hiding pointer (invalid coordinates)")
                    service.hidePointer()
                } else {
                    service.pointerView?.visibility = View.VISIBLE
                    service.updatePointer(x, y)
                }
            } ?: run {
                Log.w(TAG, "updatePointerPosition: Service instance is null - cannot update pointer")
            }
        }
        
        fun indicateClick() {
            instance?.pointerView?.indicateClick()
        }

        fun indicateDragStart() {
            instance?.pointerView?.indicateDragStart()
        }

        fun indicateDragEnd() {
            instance?.pointerView?.indicateDragEnd()
        }
    }
    
    var pointerView: PointerView? = null
        private set
    
    private var windowManager: WindowManager? = null
    private var pointerLayout: FrameLayout? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        createPointerView()
        
        // Try to start as foreground service if possible (for Android O+)
        // Note: On Android 14+ with targetSdk 34, this may fail if no type is specified
        // But TYPE_APPLICATION_OVERLAY windows work without foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification())
                Log.d(TAG, "Started as foreground service")
            } catch (e: Exception) {
                // If foreground service fails, continue as regular service
                // The overlay window will still work
                Log.w(TAG, "Could not start as foreground service (may require type on Android 14+): ${e.message}")
            }
        }
        
        Log.d(TAG, "PointerOverlayService created")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pointer Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays pointer overlay on screen"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iris Pointer")
            .setContentText("Pointer overlay is active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure foreground service is maintained (only if Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground in onStartCommand: ${e.message}", e)
            }
        }
        return START_STICKY // Restart if killed
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createPointerView() {
        pointerLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            // Start hidden until we get valid coordinates
            visibility = View.GONE
        }
        
        // Create custom pointer view
        pointerView = PointerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(60, 60)
            // Apply colors from settings
            val settingsManager = SettingsManager(this@PointerOverlayService)
            setCursorColor(settingsManager.cursorColor)
            setClickColor(settingsManager.clickColor)
        }
        
        pointerLayout?.addView(pointerView)
        
        // Determine window type based on Android version
        // Android 8.0+ (API 26+): TYPE_ACCESSIBILITY_OVERLAY is available for accessibility services
        // This allows overlay updates even when app is in background on Android 15+
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Check if we're running as an accessibility service (which we are via EyeTrackingAccessibilityService)
            // TYPE_ACCESSIBILITY_OVERLAY (2038) is available from API 26+
            // For Android 15+, this is preferred for accessibility services
            if (Build.VERSION.SDK_INT >= 35 && EyeTrackingAccessibilityService.isEnabled()) {
                // Android 15+ with accessibility service: Use TYPE_ACCESSIBILITY_OVERLAY
                // This bypasses background overlay update restrictions
                try {
                    // TYPE_ACCESSIBILITY_OVERLAY = 2038 (available from API 26+)
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } catch (e: Exception) {
                    Log.w(TAG, "TYPE_ACCESSIBILITY_OVERLAY not available, using TYPE_APPLICATION_OVERLAY: ${e.message}")
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        Log.d(TAG, "Using window type: $windowType (SDK=${Build.VERSION.SDK_INT}, accessibility=${EyeTrackingAccessibilityService.isEnabled()})")
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -1000 // Start off-screen until we get valid coordinates
            y = -1000
        }
        
        try {
            windowManager?.addView(pointerLayout, params)
            Log.d(TAG, "Pointer overlay added (initially hidden)")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding pointer overlay: ${e.message}", e)
        }
    }
    
    fun hidePointer() {
        // Completely hide the pointer overlay
        pointerLayout?.let { view ->
            try {
                view.visibility = View.GONE
                // Also move it off-screen
                val params = view.layoutParams as? WindowManager.LayoutParams
                params?.let {
                    it.x = -1000
                    it.y = -1000
                    try {
                        windowManager?.updateViewLayout(view, it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating pointer layout: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding pointer: ${e.message}", e)
            }
        }
    }
    
    fun updatePointer(x: Float, y: Float) {
        Log.d(TAG, "updatePointer() called: x=$x, y=$y")
        
        // Only update if valid coordinates (not -1)
        if (x < 0 || y < 0) {
            // Hide pointer if invalid coordinates
            Log.d(TAG, "updatePointer: Invalid coordinates, hiding pointer")
            hidePointer()
            return
        }
        
        pointerLayout?.let { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams
            params?.let {
                val screenX = x.toInt() - 30 // Center the pointer (60/2)
                val screenY = y.toInt() - 30
                
                // Android 15+ restriction: Only update overlay if:
                // 1. EyeTrackingAccessibilityService is enabled (bypasses restriction), OR
                // 2. App is in foreground
                val isAccessibilityEnabled = EyeTrackingAccessibilityService.isEnabled()
                val isForeground = isAppInForeground(this)
                val canUpdateOverlay = isAccessibilityEnabled || isForeground
                
                // Check overlay permission
                val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this)
                } else {
                    true
                }
                
                Log.d(TAG, "updatePointer: screenX=$screenX, screenY=$screenY, accessibility=$isAccessibilityEnabled, foreground=$isForeground, canDrawOverlays=$canDrawOverlays")
                
                if (!canDrawOverlays) {
                    Log.w(TAG, "updatePointer: Overlay permission not granted - cannot update pointer")
                    return
                }
                
                if (!canUpdateOverlay) {
                    // Android 15 restriction: Cannot update overlay in background without accessibility
                    Log.w(TAG, "updatePointer: Overlay update blocked - Android 15 restriction (accessibility=$isAccessibilityEnabled, foreground=$isForeground)")
                    return
                }
                
                // Update position
                it.x = screenX
                it.y = screenY
                
                try {
                    // Ensure view is visible BEFORE updating layout
                    // This is critical for background updates
                    if (view.visibility != View.VISIBLE) {
                        view.visibility = View.VISIBLE
                        Log.d(TAG, "updatePointer: Pointer view made visible for background update")
                    }
                    
                    // Always update on main thread immediately
                    // Use Handler.post to ensure it runs even from background threads
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            windowManager?.updateViewLayout(view, it)
                            Log.d(TAG, "Overlay UPDATED: Pointer updated to ($screenX, $screenY) - accessibility=$isAccessibilityEnabled, foreground=$isForeground")
                            LogcatManager.addLog("Overlay UPDATED: Pointer at ($screenX, $screenY)", "Service")
                        } catch (e: Exception) {
                            Log.e(TAG, "updatePointer: Error updating pointer position on main thread: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "updatePointer: Error updating pointer position: ${e.message}", e)
                }
            } ?: run {
                Log.w(TAG, "updatePointer: Layout params are null, cannot update pointer")
            }
        } ?: run {
            Log.w(TAG, "updatePointer: Pointer layout is null, cannot update pointer")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        
        pointerLayout?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing pointer overlay: ${e.message}", e)
            }
        }
        
        Log.d(TAG, "PointerOverlayService destroyed")
    }
}
