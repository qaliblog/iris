package com.qali.iris

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Custom view that draws a pointer/cursor
 */
class PointerView(context: Context) : View(context) {
    
    private var isClicking = false
    private var clickEndTime = 0L
    private val CLICK_COLOR_DURATION_MS = 200L // Show click color for 200ms
    private var isDragging = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Default colors: blue for cursor, green for click, purple for drag
    private var cursorColor: Int = Color.BLUE
    private var clickColor: Int = Color.GREEN
    private var dragColor: Int = Color.parseColor("#9C27B0") // Purple
    
    private val pointerPaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val centerPaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 5f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val outerPaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        alpha = 128
        isAntiAlias = true
    }
    
    /**
     * Set cursor color (default color when not clicking)
     */
    fun setCursorColor(color: Int) {
        cursorColor = color
        if (!isClicking) {
            updatePaintColors()
            invalidate()
        }
    }
    
    /**
     * Set click color (color shown when clicking)
     */
    fun setClickColor(color: Int) {
        clickColor = color
        if (isClicking) {
            updatePaintColors()
            invalidate()
        }
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
    
    fun indicateDragStart() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startDragAnimation()
        } else {
            mainHandler.post { startDragAnimation() }
        }
    }

    fun indicateDragEnd() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            endDragAnimation()
        } else {
            mainHandler.post { endDragAnimation() }
        }
    }

    private fun startDragAnimation() {
        isDragging = true
        updatePaintColors()
        animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
        invalidate()
    }

    private fun endDragAnimation() {
        isDragging = false
        updatePaintColors()
        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        invalidate()
    }

    private fun updatePaintColors() {
        val color = when {
            isDragging -> dragColor
            isClicking -> clickColor
            else -> cursorColor
        }
        pointerPaint.color = color
        centerPaint.color = color
        outerPaint.color = color
        outerPaint.alpha = if (isDragging) 255 else 128
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
