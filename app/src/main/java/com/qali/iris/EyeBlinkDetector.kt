package com.qali.iris

import android.graphics.PointF
import kotlin.math.abs

class EyeBlinkDetector(
    initialBlinkThreshold: Float = 0.3f,
    initialHalfBlinkAccelThreshold: Float = 0.15f,
    initialClickDelayThreshold: Long = 200L
) {
    companion object { private const val TAG = "EyeBlinkDetector" }

    private var blinkThreshold = initialBlinkThreshold
    private var halfBlinkAccelThreshold = initialHalfBlinkAccelThreshold
    private var clickDelayThreshold = initialClickDelayThreshold

    private data class State(val time: Long, val openness: Float, val velocity: Float = 0f, val acceleration: Float = 0f)
    private val history = ArrayDeque<State>(5)
    private var lastActionTime = 0L
    private var isHalfBlinking = false
    private var baselineOpenness = 1f
    private var baselineSet = false

    var onTap: ((PointF) -> Unit)? = null
    var onDragStart: ((PointF) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    fun setBlinkThreshold(t: Float) { blinkThreshold = t }
    fun setHalfBlinkAccelThreshold(t: Float) { halfBlinkAccelThreshold = t }
    fun setClickDelayThreshold(t: Long) { clickDelayThreshold = t }

    fun processEyelidLandmarks(upperLidY: Float, lowerLidY: Float, clickPosition: PointF) {
        val now = System.currentTimeMillis()
        val openness = (lowerLidY - upperLidY).coerceIn(0f, 1f)
        if (!baselineSet) { baselineOpenness = openness; baselineSet = true; history.clear(); return }
        val normalized = (openness / baselineOpenness).coerceIn(0f, 1f)

        val velocity = if (history.isNotEmpty()) {
            val dt = (now - history.last().time).coerceAtLeast(1L)
            (normalized - history.last().openness) / (dt / 1000f)
        } else 0f

        val acceleration = if (history.size >= 2) {
            val dt = (now - history[history.size - 2].time).coerceAtLeast(1L)
            val dv = velocity - history.last().velocity
            dv / (dt / 1000f)
        } else 0f

        history.add(State(now, normalized, velocity, acceleration))
        if (history.size > 5) history.removeFirst()
        while (history.isNotEmpty() && now - history.first().time > 500) history.removeFirst()

        if (history.size >= 3 && now - lastActionTime >= 250) {
            val s1 = history[history.size - 3]
            val s2 = history[history.size - 2]
            val s3 = history[history.size - 1]
            val closingAccel = s2.acceleration
            val openingAccel = s3.acceleration
            val closeThreshold = 1f - blinkThreshold
            val reopenThreshold = (closeThreshold + 0.15f).coerceAtMost(1f)

            val closed = s2.openness < closeThreshold
            val reopened = s3.openness > reopenThreshold

            if (closingAccel < -blinkThreshold && openingAccel > blinkThreshold * 0.6f && closed && reopened) {
                lastActionTime = now
                onTap?.invoke(clickPosition)
                return
            }
        }

        if (history.size >= 2) {
            val s1 = history[history.size - 2]; val s2 = history[history.size - 1]
            val accelChange = abs(s2.acceleration - s1.acceleration)

            if (!isHalfBlinking && accelChange >= halfBlinkAccelThreshold && s2.acceleration < -halfBlinkAccelThreshold * 0.8f) {
                isHalfBlinking = true; lastActionTime = now; onDragStart?.invoke(clickPosition); return
            }

            if (isHalfBlinking && (s2.acceleration > 0.05f || s2.openness > 0.7f)) {
                isHalfBlinking = false; onDragEnd?.invoke()
            }
        }
    }

    fun processEyeArea(eyeArea: Float): Boolean {
        val normalized = eyeArea.coerceIn(0f, 1f)
        val upper = 0.4f - normalized * 0.2f; val lower = 0.6f + normalized * 0.2f
        processEyelidLandmarks(upper, lower, PointF(0f, 0f))
        return false
    }

    fun reset() { history.clear(); isHalfBlinking = false; baselineSet = false; baselineOpenness = 1f }
}