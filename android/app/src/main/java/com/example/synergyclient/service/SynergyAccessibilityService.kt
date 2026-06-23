package com.example.synergyclient.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicBoolean

class SynergyAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: CursorOverlayView
    private val mainHandler = Handler(Looper.getMainLooper())

    // -1 = not yet positioned by server (cursor hidden until first CINN/DMMV)
    @Volatile var cursorX: Int = -1
        private set
    @Volatile var cursorY: Int = -1
        private set
    @Volatile var isSynergyActive: Boolean = false
        private set

    private val invalidatePending = AtomicBoolean(false)
    private val invalidateRunnable = Runnable {
        invalidatePending.set(false)
        if (::overlayView.isInitialized) overlayView.invalidate()
    }

    // ── Local text buffer: mirrors what's in the focused field ────────────
    // Avoids reading stale node.text on every keystroke (causes "enenenen")
    private val textBuffer = StringBuilder()
    private var textBufferInitialized = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = CursorOverlayView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        mainHandler.post {
            try {
                windowManager.addView(overlayView, params)
                android.util.Log.i("SynergyApp", "Cursor overlay added successfully")
            } catch (e: Exception) {
                android.util.Log.e("SynergyApp", "Failed to add cursor overlay: ${e.message}", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            // When a text field gains focus: sync our buffer from its current text
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val src = event.source ?: return
                if (src.isEditable) {
                    val existing = src.text?.toString() ?: ""
                    synchronized(textBuffer) {
                        textBuffer.clear()
                        textBuffer.append(existing)
                        textBufferInitialized = true
                    }
                    if (isSynergyActive) mainHandler.post { suppressKeyboard() }
                }
                src.recycle()
            }
            // When text changes externally (user tapped, paste from system, etc.): re-sync buffer
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source ?: return
                if (src.isEditable && !isSynergyActive) {
                    val existing = src.text?.toString() ?: ""
                    synchronized(textBuffer) {
                        textBuffer.clear()
                        textBuffer.append(existing)
                    }
                }
                src.recycle()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            mainHandler.post { try { windowManager.removeView(overlayView) } catch (_: Exception) {} }
        }
        instance = null
    }

    // ── Cursor ────────────────────────────────────────────────────────────

    fun updateCursor(newX: Int, newY: Int) {
        cursorX = clampX(newX); cursorY = clampY(newY)
        if (invalidatePending.compareAndSet(false, true)) mainHandler.post(invalidateRunnable)
    }

    fun moveCursorRelative(dx: Int, dy: Int) = updateCursor(cursorX + dx, cursorY + dy)

    fun clickCursor() {
        // Use a tiny stroke (tap) at the current cursor position
        val cx = cursorX.toFloat()
        val cy = cursorY.toFloat()
        val path = Path().apply { moveTo(cx, cy) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build(), null, null
        )
    }

    fun scroll(dx: Int, dy: Int) {
        val x = cursorX.toFloat(); val y = cursorY.toFloat()
        val endX = (x - dx * 3f).coerceIn(0f, screenW().toFloat())
        val endY = (y - dy * 3f).coerceIn(0f, screenH().toFloat())
        val path = Path().apply { moveTo(x, y); lineTo(endX, endY) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build(), null, null
        )
    }

    // ── IME suppression ───────────────────────────────────────────────────

    fun hideKeyboard() {
        isSynergyActive = true
        mainHandler.post { suppressKeyboard() }
    }

    fun showKeyboard() {
        isSynergyActive = false
    }

    private fun suppressKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = if (::overlayView.isInitialized) overlayView.windowToken else null
            if (token != null) imm.hideSoftInputFromWindow(token, 0)
        } catch (_: Exception) {}
    }

    // ── Keyboard text injection ───────────────────────────────────────────

    fun handleKeyDown(keyId: Int, modifiers: Int) {
        mainHandler.post {
            val ctrl  = (modifiers and 0x0004) != 0
            val shift = (modifiers and 0x0001) != 0

            if (ctrl) {
                val lower = if (keyId in 0x41..0x5A) keyId + 32 else keyId
                when (lower) {
                    0x63 -> nodeAction(AccessibilityNodeInfo.ACTION_COPY)
                    0x76 -> nodeAction(AccessibilityNodeInfo.ACTION_PASTE)
                    0x78 -> nodeAction(AccessibilityNodeInfo.ACTION_CUT)
                    0x61 -> nodeAction(AccessibilityNodeInfo.ACTION_SELECT)
                }
                return@post
            }

            when (keyId) {
                0xFF08, 0xEF08 -> deleteLastChar()
                0xFF0D, 0xFF8D -> insertChar('\n')
                0xFF09 -> insertChar('\t')
                0xFF1B -> performGlobalAction(GLOBAL_ACTION_BACK)
                0xFFFF, 0xFF9F, 0xEF9F -> deleteForwardChar()
                in 0xFF50..0xFFFF -> { /* navigation/function keys — ignore */ }
                in 0x20..0x7E -> {
                    val ch = if (shift && keyId in 0x61..0x7A)
                        (keyId - 32).toChar() else keyId.toChar()
                    insertChar(ch)
                }
                in 0x00A0..0x10FFFF -> {
                    val s = if (keyId <= 0xFFFF) keyId.toChar().toString()
                            else String(Character.toChars(keyId))
                    insertString(s)
                }
            }
        }
    }

    /** Called for DKDL — lang field is keyboard language code (e.g. "en"), NOT the character.
     *  Always use keyId for the actual character to inject. */
    fun handleKeyDownLang(keyId: Int, modifiers: Int, langText: String) {
        // langText is e.g. "en", "hi", "ar" — keyboard language, not the typed char
        // The character is always encoded in keyId
        handleKeyDown(keyId, modifiers)
    }

    // Insert a single character using our local buffer (no node.text read)
    private fun insertChar(ch: Char) = insertString(ch.toString())

    private fun insertString(s: String) {
        val node = focusedEditable() ?: return
        val newText = synchronized(textBuffer) {
            if (!textBufferInitialized) {
                // First keystroke in this field — bootstrap from node
                textBuffer.clear()
                textBuffer.append(node.text?.toString() ?: "")
                textBufferInitialized = true
            }
            textBuffer.append(s)
            textBuffer.toString()
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            })
        node.recycle()
        suppressKeyboard()
    }

    private fun deleteLastChar() {
        val node = focusedEditable() ?: return
        val newText = synchronized(textBuffer) {
            if (!textBufferInitialized) {
                textBuffer.clear()
                textBuffer.append(node.text?.toString() ?: "")
                textBufferInitialized = true
            }
            if (textBuffer.isNotEmpty()) textBuffer.deleteCharAt(textBuffer.length - 1)
            textBuffer.toString()
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            })
        node.recycle()
    }

    private fun deleteForwardChar() {
        val node = focusedEditable() ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        node.recycle()
    }

    private fun nodeAction(action: Int) {
        val node = focusedEditable() ?: return
        node.performAction(action)
        node.recycle()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else { node.recycle(); null }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────

    fun setClipboard(text: String) {
        mainHandler.post {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Synergy", text))
            } catch (_: Exception) {}
        }
    }

    fun getClipboardText(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Full physical screen size including navigation bar.
     * Used for BOTH cursor clamping AND DINF so coordinates are consistent.
     * displayMetrics.heightPixels excludes the nav bar — do NOT use it here.
     */
    fun getFullScreenSize(): Pair<Int, Int> {
        return try {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } catch (_: Exception) {
            // Fallback: displayMetrics (may exclude nav bar, but better than nothing)
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun screenW(): Int = getFullScreenSize().first
    private fun screenH(): Int = getFullScreenSize().second
    private fun clampX(v: Int) = v.coerceIn(0, (screenW() - 1).coerceAtLeast(0))
    private fun clampY(v: Int) = v.coerceIn(0, (screenH() - 1).coerceAtLeast(0))

    companion object {
        var instance: SynergyAccessibilityService? = null
            private set
    }
}
