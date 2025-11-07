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
                        Log.w(TAG, "Enabled accessibility services list is empty")
                    }

            val expectedComponent = ComponentName(context, MouseControlService::class.java).flattenToString()
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServicesSetting) }
            return splitter.any { it.equals(expectedComponent, ignoreCase = true) }
        }

        // Called by EyeBlinkDetector
        fun moveCursor(x: Float, y: Float) {
            lastRequestedPosition.set(PointF(x, y))
            instance?.performMouseMove(x, y)
        }

        fun performClick() {
            instance?.performMouseClick()
        }

        fun startDrag() {
            instance?.startDragInternal()
        }

        fun endDrag() {
            instance?.endDragInternal()
        }

        internal fun getPendingCursorPosition(): PointF? = lastRequestedPosition.get()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsManager: SettingsManager? = null

    // Cursor state
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastUpdateTime: Long = 0L

    // Drag state
    private var isDragging = false
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f

    fun setSettingsManager(settingsManager: SettingsManager) {
        this.settingsManager = settingsManager
    }

    private fun getSmoothingFactor(): Float = settingsManager?.cursorSmoothingFactor ?: 0.7f
    private fun getUpdateInterval(): Long = settingsManager?.cursorUpdateInterval ?: 16L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "MouseControlService connected")

        lastX = 0f
        lastY = 0f
        lastUpdateTime = 0L
        isDragging = false

        connectionCallbacks.forEach { it(this) }

        getPendingCursorPosition()?.let { pos ->
            mainHandler.post { performMouseMoveInternal(pos.x, pos.y, forceImmediate = true) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "MouseControlService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onGesture(gestureDescription: GestureDescription?): Boolean = false

    private fun ensureServiceEnabled(): Boolean {
        return if (isAccessibilityServiceEnabled(this)) true
        else {
            if (missingInstanceLogged.compareAndSet(false, true)) {
                Log.w(TAG, "Accessibility service not enabled")
            }
            false
        }
    }

    // Public: Always update pointer position
    fun performMouseMove(x: Float, y: Float) {
        if (x < 0 || y < 0) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performMouseMoveInternal(x, y)
        } else {
            mainHandler.post { performMouseMoveInternal(x, y) }
        }
    }

    private fun performMouseMoveInternal(x: Float, y: Float, forceImmediate: Boolean = false) {
        if (!ensureServiceEnabled()) return

        val currentTime = System.currentTimeMillis()
        if (!forceImmediate && lastUpdateTime > 0 && (currentTime - lastUpdateTime) < getUpdateInterval()) return
        lastUpdateTime = currentTime

        val smoothing = getSmoothingFactor()
        val hasPrev = lastX > 0f && lastY > 0f

        val targetX = if (forceImmediate || !hasPrev) x else lastX + (x - lastX) * (1 - smoothing)
        val targetY = if (forceImmediate || !hasPrev) y else lastY + (y - lastY) * (1 - smoothing)

        val dx = kotlin.math.abs(targetX - lastX)
        val dy = kotlin.math.abs(targetY - lastY)
        if (!forceImmediate && dx < 1f && dy < 1f) return

        lastX = targetX
        lastY = targetY

        val path = Path().apply {
            if (isDragging) {
                moveTo(dragStartX, dragStartY)
                lineTo(targetX, targetY)
            } else {
                moveTo(targetX, targetY)
            }
        }

        val duration = if (isDragging) 16L else 1L

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, null, null)
    }

    // Public: Tap (blink with full open/close)
    fun performMouseClick() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performMouseClickInternal()
        } else {
            mainHandler.post { performMouseClickInternal() }
        }
    }

    private fun performMouseClickInternal() {
        if (!ensureServiceEnabled() || lastX <= 0 || lastY <= 0) return

        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(lastX, lastY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { Log.d(TAG, "Tap") }
        }, null)
    }

    // Public: Start drag (half-blink with negative reverse acceleration)
    fun startDragInternal() {
        if (!ensureServiceEnabled() || lastX <= 0 || lastY <= 0) return
        isDragging = true
        dragStartX = lastX
        dragStartY = lastY
        Log.d(TAG, "Drag started")
    }

    fun endDragInternal() { isDragging = false; Log.d(TAG, "Drag ended") }
}