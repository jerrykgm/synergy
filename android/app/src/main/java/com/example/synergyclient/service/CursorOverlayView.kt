package com.example.synergyclient.service

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Full-screen transparent overlay that draws the mouse cursor arrow.
 * Uses LAYER_TYPE_SOFTWARE for maximum compatibility with MIUI/custom ROMs.
 * Shadow uses plain offset path (no BlurMaskFilter) so software rendering is fast.
 */
class CursorOverlayView(context: Context) : View(context) {

    companion object {
        private const val SIZE = 32f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()
    private val shadowPath = Path()

    init {
        setWillNotDraw(false)
        buildPaths()
    }

    private fun buildPaths() {
        val s = SIZE
        arrowPath.apply {
            moveTo(0f,        0f)
            lineTo(0f,        s * 1.0f)
            lineTo(s * 0.28f, s * 0.72f)
            lineTo(s * 0.52f, s * 1.28f)
            lineTo(s * 0.68f, s * 1.20f)
            lineTo(s * 0.44f, s * 0.64f)
            lineTo(s * 0.72f, s * 0.64f)
            close()
        }
        shadowPath.addPath(arrowPath, 2.5f, 2.5f)
    }

    override fun onDraw(canvas: Canvas) {
        val svc = SynergyAccessibilityService.instance ?: return
        val cx = svc.cursorX
        val cy = svc.cursorY
        
        // Hidden until server positions us (cursorX == -1 means not yet received CINN/DMMV)
        if (cx < 0 || cy < 0) return

        canvas.save()
        canvas.translate(cx.toFloat(), cy.toFloat())
        canvas.drawPath(shadowPath, shadowPaint)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
        canvas.restore()
    }
}
