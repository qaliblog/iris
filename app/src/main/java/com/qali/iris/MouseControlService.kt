package com.qali.iris

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Accessibility service for mouse control.
 * Handles dispatching gesture injections for cursor movement and clicks even when the app is in background.
 */
class MouseControlService : AccessibilityService() {

    companion object {
        private const val TAG = "MouseControlService"

        @Volatile
        private var instance: MouseControlService? = null

        private val connectionCallbacks = CopyOnWriteArraySet<(MouseControlService) -> Unit>()
        private val lastRequestedPosition = AtomicReference<PointF?>(null)
        private val missingInstanceLogged = AtomicBoolean(false)

        fun getInstance(): MouseControlService? = instance

        fun registerOnServiceConnected(callback: (MouseControlService) -> Unit) {
            connectionCallbacks.add(callback)
            instance?.let { callback(it) }
        }

        fun unregisterOnServiceConnected(callback: (MouseControlService) -> Unit) {
            connectionCallbacks.remove(callback)
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabledServicesSetting =
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?: return false.also {
                        Log.w(TAG, "Enabled accessibility services list is empty in secure settings")
                    }

            val expectedComponent = ComponentName(context, MouseControlService::class.java).flattenToString()
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServicesSetting) }
            splitter.forEach { entry ->
                if (entry.equals(expectedComponent, ignoreCase = true)) {
                    return true
                }
            }

            Log.w(TAG, "MouseControlService not found in secure settings list. Expected entry: $expectedComponent")
            return false
        }

        fun moveCursor(x: Float, y: Float) {
            lastRequestedPosition.set(PointF(x, y))

            val service = instance
            if (service == null) {
                if (missingInstanceLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "Skipping cursor move: service instance is null")
                }
                return
            }

            missingInstanceLogged.set(false)
            service.performMouseMove(x, y)
        }

        fun performClick() {
            val service = instance
            if (service == null) {
                if (missingInstanceLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "Skipping click: service instance is null")
                }
                return
            }

            missingInstanceLogged.set(false)
            service.performMouseClick()
        }

        internal fun getPendingCursorPosition(): PointF? = lastRequestedPosition.get()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val missingServiceLogged = AtomicBoolean(false)

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastUpdateTime: Long = 0L
    private var settingsManager: SettingsManager? = null

    fun setSettingsManager(settingsManager: SettingsManager) {
        this.settingsManager = settingsManager
    }

    private fun getSmoothingFactor(): Float =
        settingsManager?.cursorSmoothingFactor ?: 0.7f

    private fun getMovementDuration(): Long =
        settingsManager?.cursorMovementDuration ?: 100L

    private fun getUpdateInterval(): Long =
        settingsManager?.cursorUpdateInterval ?: 16L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "MouseControlService connected")

        // Reset position on reconnect
        lastX = 0f
        lastY = 0f
        lastUpdateTime = 0L
        missingServiceLogged.set(false)

        connectionCallbacks.forEach { callback ->
            try {
                callback(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Error delivering onServiceConnected callback: ${t.message}", t)
            }
        }

        // Resume pending cursor update if we have one stored
        getPendingCursorPosition()?.let { position ->
            mainHandler.post {
                performMouseMoveInternal(position.x, position.y, forceImmediate = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MouseControlService destroyed")
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for mouse control
    }

    override fun onInterrupt() {
        Log.w(TAG, "MouseControlService interrupted")
    }

    private fun ensureServiceEnabled(): Boolean {
        val enabled = isAccessibilityServiceEnabled(this)
        if (!enabled) {
            if (missingServiceLogged.compareAndSet(false, true)) {
                Log.w(TAG, "Skipping cursor update: accessibility service not enabled via secure settings")
            }
        } else if (missingServiceLogged.getAndSet(false)) {
            Log.d(TAG, "Accessibility service re-enabled; resuming cursor updates")
        }
        return enabled
    }

    /**
     * Move cursor using GestureDescription (Android 7.0+)
     * Can be called from any thread - internally ensures main thread execution
     */
    fun performMouseMove(x: Float, y: Float) {
        if (x < 0 || y < 0) {
            Log.v(TAG, "Ignoring cursor update with invalid coordinates ($x, $y)")
            return
        }

        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                performMouseMoveInternal(x, y)
            } else {
                mainHandler.post { performMouseMoveInternal(x, y) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving cursor: ${e.message}", e)
        }
    }

    private fun performMouseMoveInternal(x: Float, y: Float, forceImmediate: Boolean = false) {
        if (!ensureServiceEnabled()) {
            return
        }

        try {
            val currentTime = System.currentTimeMillis()
            if (!forceImmediate && lastUpdateTime > 0 && (currentTime - lastUpdateTime) < getUpdateInterval()) {
                Log.v(TAG, "Skipping cursor update (throttled). Δt=${currentTime - lastUpdateTime}ms")
                return
            }
            lastUpdateTime = currentTime

            val smoothingFactor = getSmoothingFactor()

            val hasPreviousPosition = lastX != 0f || lastY != 0f
            val previousX = if (hasPreviousPosition) lastX else x
            val previousY = if (hasPreviousPosition) lastY else y

            val smoothedX = if (forceImmediate || !hasPreviousPosition) {
                x
            } else {
                lastX + (x - lastX) * (1 - smoothingFactor)
            }
            val smoothedY = if (forceImmediate || !hasPreviousPosition) {
                y
            } else {
                lastY + (y - lastY) * (1 - smoothingFactor)
            }

            lastX = smoothedX
            lastY = smoothedY

            val path = Path().apply {
                moveTo(previousX, previousY)
                lineTo(smoothedX, smoothedY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        getMovementDuration()
                    )
                )
                .build()

            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in performMouseMoveInternal: ${e.message}", e)
        }
    }

    /**
     * Perform a click at the current cursor position.
     * Can be called from any thread - internally ensures main thread execution.
     */
    fun performMouseClick() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                performMouseClickInternal()
            } else {
                mainHandler.post { performMouseClickInternal() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click: ${e.message}", e)
        }
    }

    private fun performMouseClickInternal() {
        if (!ensureServiceEnabled()) {
            return
        }

        try {
            if (lastX <= 0 || lastY <= 0) {
                Log.w(TAG, "Cannot perform click: invalid position (lastX=$lastX, lastY=$lastY)")
                return
            }

            val clickPath = Path().apply {
                moveTo(lastX, lastY)
                lineTo(lastX, lastY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        clickPath,
                        0,
                        100
                    )
                )
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Click completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Click cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in performMouseClickInternal: ${e.message}", e)
        }
    }
}
