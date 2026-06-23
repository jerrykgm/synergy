package com.example.synergyclient.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
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

    // ── Cursor state (volatile for cross-thread reads from network thread) ─
    // -1 = not yet positioned by server (cursor hidden until first CINN/DMMV)
    @Volatile var cursorX: Int = -1
        private set
    @Volatile var cursorY: Int = -1
        private set
    @Volatile var isSynergyActive: Boolean = false
        private set

    // ── Invalidate coalescing — prevents flooding the main thread ──────────
    private val invalidatePending = AtomicBoolean(false)
    private val invalidateRunnable = Runnable {
        invalidatePending.set(false)
        if (::overlayView.isInitialized) overlayView.invalidate()
    }

    // ── Pre-allocated gesture path objects (never reallocated) ────────────
    private val clickPath  = Path()
    private val scrollPath = Path()

    // ── Mac Server Mode — cached to avoid SharedPreferences I/O on hot path
    @Volatile private var cachedMacServerMode: Boolean = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var sharedPrefs: SharedPreferences? = null

    // ── Text buffer: mirrors focused field content to avoid stale node reads
    private val textBuffer = StringBuilder()
    private var lastActiveNode: AccessibilityNodeInfo? = null

    private var lastClickTime = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Cache mac server mode from prefs once, then listen for changes
        sharedPrefs = getSharedPreferences("synergy_prefs", Context.MODE_PRIVATE)
        cachedMacServerMode = sharedPrefs!!.getBoolean("mac_server_mode", false)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "mac_server_mode") {
                cachedMacServerMode = prefs.getBoolean("mac_server_mode", false)
            }
        }
        sharedPrefs!!.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = CursorOverlayView(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                softKeyboardController.addOnShowModeChangedListener { controller, mode ->
                    if (isSynergyActive && mode != SHOW_MODE_HIDDEN) {
                        mainHandler.post {
                            try { controller.showMode = SHOW_MODE_HIDDEN } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

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
                android.util.Log.i("SynergyApp", "Cursor overlay added")
            } catch (e: Exception) {
                android.util.Log.e("SynergyApp", "Failed to add overlay: ${e.message}", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                synchronized(textBuffer) {
                    textBuffer.clear()
                    lastActiveNode?.recycle()
                    lastActiveNode = null
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = event.source ?: return
                if (src.isEditable) {
                    val existing = src.text?.toString() ?: ""
                    synchronized(textBuffer) {
                        textBuffer.clear()
                        textBuffer.append(existing)
                        lastActiveNode?.recycle()
                        lastActiveNode = AccessibilityNodeInfo.obtain(src)
                    }
                    if (isSynergyActive) mainHandler.post { suppressKeyboard() }
                }
                src.recycle()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source ?: return
                if (src.isEditable && !isSynergyActive) {
                    val existing = src.text?.toString() ?: ""
                    synchronized(textBuffer) {
                        textBuffer.clear()
                        textBuffer.append(existing)
                        lastActiveNode?.recycle()
                        lastActiveNode = AccessibilityNodeInfo.obtain(src)
                    }
                }
                src.recycle()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        prefsListener?.let { sharedPrefs?.unregisterOnSharedPreferenceChangeListener(it) }
        lastActiveNode?.recycle()
        lastActiveNode = null
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            mainHandler.post { try { windowManager.removeView(overlayView) } catch (_: Exception) {} }
        }
        instance = null
    }

    // ── Cursor (called from network IO thread — must be lock-free) ────────

    fun updateCursor(newX: Int, newY: Int) {
        cursorX = clampX(newX)
        cursorY = clampY(newY)
        // Coalesce invalidates: only post one runnable at a time
        if (invalidatePending.compareAndSet(false, true)) {
            mainHandler.post(invalidateRunnable)
        }
    }

    fun moveCursorRelative(dx: Int, dy: Int) = updateCursor(cursorX + dx, cursorY + dy)

    fun clickCursor() {
        val now = System.currentTimeMillis()
        val isDoubleClick = (now - lastClickTime) < 400
        lastClickTime = now

        val cx = cursorX.toFloat()
        val cy = cursorY.toFloat()

        // Reuse pre-allocated path
        clickPath.reset()
        clickPath.moveTo(cx, cy)

        if (isDoubleClick) {
            // Two taps with 40ms gap — minimum duration (1ms) for lowest latency
            val stroke1 = GestureDescription.StrokeDescription(clickPath, 0,  1)
            val stroke2 = GestureDescription.StrokeDescription(clickPath, 40, 1)
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build(), null, null
            )
            lastClickTime = 0L  // prevent triple-tap
        } else {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 1))
                    .build(), null, null
            )
        }
    }

    fun scroll(dx: Int, dy: Int) {
        val x   = cursorX.toFloat()
        val y   = cursorY.toFloat()
        val w   = screenW().toFloat()
        val h   = screenH().toFloat()
        val endX = (x - dx * 3f).coerceIn(0f, w)
        val endY = (y - dy * 3f).coerceIn(0f, h)

        // Reuse pre-allocated path
        scrollPath.reset()
        scrollPath.moveTo(x, y)
        scrollPath.lineTo(endX, endY)

        // 50ms duration — fast enough for smooth scroll, short enough for responsiveness
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 50))
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
        mainHandler.post {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    softKeyboardController.showMode = SHOW_MODE_AUTO
                }
            } catch (_: Exception) {}
        }
    }

    private fun suppressKeyboard() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                softKeyboardController.showMode = SHOW_MODE_HIDDEN
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = if (::overlayView.isInitialized) overlayView.windowToken else null
            if (token != null) imm.hideSoftInputFromWindow(token, 0)
        } catch (_: Exception) {}
    }

    // ── Keyboard text injection ───────────────────────────────────────────

    fun handleKeyDown(keyId: Int, modifiers: Int) {
        mainHandler.post {
            val isShift = (modifiers and 0x0001) != 0
            val isCtrl  = (modifiers and 0x0002) != 0
            val isAlt   = (modifiers and 0x0004) != 0
            val isCmd   = (modifiers and 0x0008) != 0

            // Use cached value — no SharedPreferences I/O on hot path
            val ctrl = if (cachedMacServerMode) {
                isCmd || isCtrl
            } else {
                isCtrl || isAlt
            }

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
                0xFF08, 0xEF08              -> deleteLastChar()
                0xFF0D, 0xFF8D              -> insertChar('\n')
                0xFF09                      -> insertChar('\t')
                0xFF1B                      -> performGlobalAction(GLOBAL_ACTION_BACK)
                0xFFFF, 0xFF9F, 0xEF9F      -> deleteForwardChar()
                in 0xFF50..0xFFFF           -> { /* navigation/function keys */ }
                in 0x20..0x7E -> {
                    val ch = if (isShift && keyId in 0x61..0x7A)
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

    /**
     * Called for DKDL (key with language tag).
     * The lang field is the keyboard language (e.g. "en"), NOT the typed character.
     * Character is always encoded in keyId.
     */
    fun handleKeyDownLang(keyId: Int, modifiers: Int, langText: String) {
        handleKeyDown(keyId, modifiers)
    }

    private fun insertChar(ch: Char) = insertString(ch.toString())

    private fun updateBufferIfNodeChanged(currentNode: AccessibilityNodeInfo) {
        val last = lastActiveNode
        val isSame = last != null &&
                     last.windowId == currentNode.windowId &&
                     last.className == currentNode.className &&
                     last.packageName == currentNode.packageName &&
                     last.viewIdResourceName == currentNode.viewIdResourceName &&
                     last == currentNode

        if (!isSame) {
            textBuffer.clear()
            textBuffer.append(currentNode.text?.toString() ?: "")
            lastActiveNode?.recycle()
            lastActiveNode = AccessibilityNodeInfo.obtain(currentNode)
        } else {
            val screenText = currentNode.text?.toString() ?: ""
            if (screenText.isEmpty() && textBuffer.isNotEmpty()) {
                textBuffer.clear()
            }
        }
    }

    private fun insertString(s: String) {
        val node = focusedEditable() ?: return
        val newText = synchronized(textBuffer) {
            updateBufferIfNodeChanged(node)
            textBuffer.append(s)
            textBuffer.toString()
        }
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
        )
        node.recycle()
        suppressKeyboard()
    }

    private fun deleteLastChar() {
        val node = focusedEditable() ?: return
        val newText = synchronized(textBuffer) {
            updateBufferIfNodeChanged(node)
            if (textBuffer.isNotEmpty()) textBuffer.deleteCharAt(textBuffer.length - 1)
            textBuffer.toString()
        }
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
        )
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
        val root = rootInActiveWindow
        if (root != null) {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null && node.isEditable) return node
            val manual = findFocusedEditableNode(root)
            if (manual != null) {
                root.recycle()
                return manual
            }
            root.recycle()
        }
        // Fallback: traverse all windows (required on Samsung/OneUI)
        try {
            for (win in windows) {
                val wRoot = win.root ?: continue
                val node = wRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (node != null && node.isEditable) {
                    wRoot.recycle()
                    return node
                }
                val manual = findFocusedEditableNode(wRoot)
                if (manual != null) {
                    wRoot.recycle()
                    return manual
                }
                wRoot.recycle()
            }
        } catch (_: Exception) {}
        return null
    }

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableNode(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
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

    fun getClipboardText(): String = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    } catch (_: Exception) { "" }

    // ── Screen size helpers ───────────────────────────────────────────────

    /**
     * Returns full physical screen size including navigation bar.
     * Used for BOTH cursor clamping AND DINF so coordinates stay consistent.
     */
    fun getFullScreenSize(): Pair<Int, Int> = try {
        val b = windowManager.currentWindowMetrics.bounds
        Pair(b.width(), b.height())
    } catch (_: Exception) {
        val dm = resources.displayMetrics
        Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun screenW(): Int = getFullScreenSize().first
    private fun screenH(): Int = getFullScreenSize().second
    private fun clampX(v: Int) = v.coerceIn(0, (screenW() - 1).coerceAtLeast(0))
    private fun clampY(v: Int) = v.coerceIn(0, (screenH() - 1).coerceAtLeast(0))

    companion object {
        @Volatile
        var instance: SynergyAccessibilityService? = null
            private set
    }
}
