package com.qali.iris

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Custom view that draws a pointer/cursor
 */
class PointerView(context: Context) : View(context) {
    
    private var isClicking = false
    private var clickEndTime = 0L
    private val CLICK_COLOR_DURATION_MS = 200L // Show green for 200ms
    
    private val pointerPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val centerPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val outerPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 2f
        style = Paint.Style.STROKE
        alpha = 128
        isAntiAlias = true
    }
    
    /**
     * Indicate that a click was detected
     */
    fun indicateClick() {
        isClicking = true
        clickEndTime = System.currentTimeMillis() + CLICK_COLOR_DURATION_MS
        updatePaintColors()
        invalidate()
        
        // Reset color after duration
        postDelayed({
            isClicking = false
            updatePaintColors()
            invalidate()
        }, CLICK_COLOR_DURATION_MS)
    }
    
    private fun updatePaintColors() {
        val color = if (isClicking) Color.GREEN else Color.RED
        pointerPaint.color = color
        centerPaint.color = color
        outerPaint.color = color
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val size = 20f
        
        // Draw outer circle
        canvas.drawCircle(centerX, centerY, size, outerPaint)
        
        // Draw crosshair
        canvas.drawLine(centerX - size, centerY, centerX + size, centerY, pointerPaint)
        canvas.drawLine(centerX, centerY - size, centerX, centerY + size, pointerPaint)
        
        // Draw center dot
        canvas.drawCircle(centerX, centerY, 5f, centerPaint)
    }
}
