package com.example.synergyclient.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class SynergyAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private var cursorX = 500
    private var cursorY = 1000

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Create a programmatical visual pointer view (red dot)
        cursorView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(2, Color.WHITE)
            }
        }

        // Layout parameters to display overlay on top of all apps
        layoutParams = WindowManager.LayoutParams(
            32, // width in pixels
            32, // height in pixels
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX
            y = cursorY
        }

        Handler(Looper.getMainLooper()).post {
            try {
                windowManager.addView(cursorView, layoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateCursor(newX: Int, newY: Int) {
        cursorX = newX
        cursorY = newY
        Handler(Looper.getMainLooper()).post {
            if (::cursorView.isInitialized && ::layoutParams.isInitialized) {
                layoutParams.x = newX
                layoutParams.y = newY
                try {
                    windowManager.updateViewLayout(cursorView, layoutParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun clickCursor() {
        val gestureBuilder = GestureDescription.Builder()
        val path = Path().apply {
            moveTo(cursorX.toFloat(), cursorY.toFloat())
        }
        // Dispatch a quick tap gesture
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            Handler(Looper.getMainLooper()).post {
                try {
                    windowManager.removeView(cursorView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        instance = null
    }

    companion object {
        var instance: SynergyAccessibilityService? = null
            private set
    }
}
