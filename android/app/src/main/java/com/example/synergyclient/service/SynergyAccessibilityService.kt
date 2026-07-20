package com.example.synergyclient.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import com.example.synergyclient.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class SynergyAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: CursorOverlayView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentDisplayId = Display.DEFAULT_DISPLAY
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var isLocalClipboardChange = false

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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplayAndOverlay()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateDisplayAndOverlay()

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipListener = ClipboardManager.OnPrimaryClipChangedListener {
                try {
                    if (isLocalClipboardChange) {
                        isLocalClipboardChange = false
                        return@OnPrimaryClipChangedListener
                    }
                    val clip = cm.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString()
                        if (!text.isNullOrEmpty()) {
                            com.example.synergyclient.data.NotesManager.addClipItem(this, text)
                            val isClipSync = sharedPrefs?.getBoolean("clipboard_sync", true) ?: true
                            if (isClipSync) {
                                com.example.synergyclient.network.SynergyForegroundService.instance?.networkService?.sendClipboardText(text)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            cm.addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {}

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val info = serviceInfo
                info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
                serviceInfo = info
            } catch (_: Exception) {}
        }
    }

    // Last time we sent a force-focus packet — used to debounce touch events
    @Volatile private var lastForceFocusMs = 0L

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
                // Auto-Focus on Touch: only fire on actual user touches, debounced to 500ms
                // to avoid flooding the server with CFFF packets on every accessibility event
                val now = System.currentTimeMillis()
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    now - lastForceFocusMs > 500L) {
                    try {
                        val prefs = getSharedPreferences("synergy_prefs", MODE_PRIVATE)
                        if (prefs.getBoolean("force_focus_client", true)) {
                            com.example.synergyclient.network.SynergyForegroundService
                                .instance?.networkService?.sendForceFocus()
                            lastForceFocusMs = now
                        }
                    } catch (_: Exception) {}
                }

                val src = event.source ?: return
                if (src.isEditable) {
                    val existing = getNodeText(src)
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
                    val existing = getNodeText(src)
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
        if (clipListener != null) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.removePrimaryClipChangedListener(clipListener)
            } catch (_: Exception) {}
        }
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

    // ── Drag and click gesture state ──────────────────────────────────────
    private var isMouseDown = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartTime = 0L
    private val dragPath = Path()

    fun handleMouseDown() {
        val cx = cursorX.toFloat()
        val cy = cursorY.toFloat()
        dragStartX = cx
        dragStartY = cy
        dragStartTime = System.currentTimeMillis()
        isMouseDown = true
    }

    /**
     * Right-click handler: performs a long-press at the cursor position.
     * On Android, a long-press is the universal way to open context menus
     * (Copy / Paste / Cut / Select All / Share / etc.) in ANY app — text fields,
     * browsers, images, links. This mirrors desktop right-click behaviour seamlessly.
     */
    fun handleRightClick() {
        val cx = cursorX.toFloat()
        val cy = cursorY.toFloat()

        // 1) Gesture-based long-press at cursor location (works in all views/browsers)
        val path = Path().apply { moveTo(cx, cy) }
        dispatchGestureOnActiveDisplay(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 600L))
        )

        // 2) Accessibility ACTION_LONG_CLICK fallback for text fields where
        //    the gesture may be intercepted by the IME — fires 700ms after gesture
        mainHandler.postDelayed({
            try {
                val root = rootInActiveWindow ?: return@postDelayed
                val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                node?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    it.recycle()
                }
                root.recycle()
            } catch (_: Exception) {}
        }, 700L)
    }

    fun handleMouseUp() {
        if (!isMouseDown) return
        isMouseDown = false
        val cx = cursorX.toFloat()
        val cy = cursorY.toFloat()
        val duration = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(10L)
        
        if (Math.abs(cx - dragStartX) < 10 && Math.abs(cy - dragStartY) < 10 && duration < 300) {
            // Treat as a standard click
            val now = System.currentTimeMillis()
            val isDoubleClick = (now - lastClickTime) < 400
            lastClickTime = now

            clickPath.reset()
            clickPath.moveTo(cx, cy)

            if (isDoubleClick) {
                val stroke1 = GestureDescription.StrokeDescription(clickPath, 0,  1)
                val stroke2 = GestureDescription.StrokeDescription(clickPath, 40, 1)
                dispatchGestureOnActiveDisplay(
                    GestureDescription.Builder()
                        .addStroke(stroke1)
                        .addStroke(stroke2)
                )
                lastClickTime = 0L
            } else {
                dispatchGestureOnActiveDisplay(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 1))
                )
            }
        } else {
            // Drag gesture: dispatch a swipe gesture matching the path
            dragPath.reset()
            dragPath.moveTo(dragStartX, dragStartY)
            dragPath.lineTo(cx, cy)
            dispatchGestureOnActiveDisplay(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(dragPath, 0, duration))
            )
        }
    }

    fun clickCursor() {
        // Fallback or deprecated if server uses legacy clicks
        val cx = cursorX.toFloat()
        val cy = cursorY.toFloat()
        clickPath.reset()
        clickPath.moveTo(cx, cy)
        dispatchGestureOnActiveDisplay(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 1))
        )
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
        dispatchGestureOnActiveDisplay(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 50))
        )
    }

    // ── IME suppression ───────────────────────────────────────────────────

    fun hideKeyboard() {
        isSynergyActive = true
        mainHandler.post {
            suppressKeyboard()
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val current = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                val target = "com.example.synergyclient/.service.SynergyInputMethodService"
                if (current != target) {
                    val token = if (::overlayView.isInitialized) overlayView.windowToken else null
                    var ok = false
                    if (token != null) {
                        try {
                            imm.setInputMethod(token, target)
                            ok = true
                        } catch (_: Exception) {}
                    }
                    if (!ok) {
                        imm.showInputMethodPicker()
                    }
                }
            } catch (_: Exception) {}
        }
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
            // Bring Synergy app to foreground if typing occurs and the preference is enabled
            try {
                val focusOnType = sharedPrefs?.getBoolean("focus_app_on_type", false) ?: false
                if (focusOnType) {
                    val context: Context = this
                    val act = com.example.synergyclient.MainActivity.activeInstance
                    if (act != null) {
                        // Activity is running; bring its task stack to the front
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.moveTaskToFront(act.taskId, 0)
                    } else {
                        // Start a fresh activity instance
                        val intent = Intent(context, com.example.synergyclient.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                        context.startActivity(intent)
                    }
                }
            } catch (_: Exception) {}

            val isShift = (modifiers and 0x0001) != 0
            val isCtrl  = (modifiers and 0x0002) != 0
            val isAlt   = (modifiers and 0x0004) != 0
            val isCmd   = (modifiers and 0x0008) != 0

            // ── Mac Cmd / PC Ctrl shortcut dispatch ───────────────────────
            val hasModifier = if (cachedMacServerMode) (isCmd || isCtrl) else isCtrl

            if (hasModifier) {
                val lower = if (keyId in 0x41..0x5A) keyId + 32 else keyId
                when (lower) {
                    // ── Standard editing ──────────────────────────────────
                    0x63 -> { nodeAction(AccessibilityNodeInfo.ACTION_COPY);  return@post }
                    0x76 -> { nodeAction(AccessibilityNodeInfo.ACTION_PASTE); return@post }
                    0x78 -> { nodeAction(AccessibilityNodeInfo.ACTION_CUT);   return@post }
                    0x61 -> { nodeAction(AccessibilityNodeInfo.ACTION_SELECT); return@post }

                    // ── Undo / Redo ───────────────────────────────────────
                    0x7A -> {
                        if (isShift) {
                            // Cmd+Shift+Z → Redo (action ID 0x00100000 on API 26+)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                focusedEditable()?.let { node ->
                                    node.performAction(0x00100000) // ACTION_REDO
                                    node.recycle()
                                }
                            }
                        } else {
                            // Cmd+Z → Undo (action ID 0x00080000 on API 26+)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                focusedEditable()?.let { node ->
                                    node.performAction(0x00080000) // ACTION_UNDO
                                    node.recycle()
                                }
                            }
                        }
                        return@post
                    }

                    // ── Navigation / Window shortcuts ─────────────────────
                    0x77 -> { performGlobalAction(GLOBAL_ACTION_RECENTS); return@post }        // Cmd+W → recents
                    0x68 -> { performGlobalAction(GLOBAL_ACTION_HOME); return@post }            // Cmd+H → home
                    0x20 -> { performGlobalAction(GLOBAL_ACTION_HOME); return@post }            // Cmd+Space → home/launcher

                    // ── Arrow key navigation with Cmd ─────────────────────
                    0xFF51, 0xFF53, 0xFF52, 0xFF54 -> {
                        // Cmd+Left/Right/Up/Down → beginning/end of line/document
                        val args = Bundle()
                        val moveGranularity: Int
                        val direction: Int
                        when (lower) {
                            0xFF51 -> { moveGranularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE;      direction = 0 } // Left  → line start
                            0xFF53 -> { moveGranularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE;      direction = 1 } // Right → line end
                            0xFF52 -> { moveGranularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH; direction = 0 } // Up    → paragraph start
                            else   -> { moveGranularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH; direction = 1 } // Down  → paragraph end
                        }
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, moveGranularity)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            val action = if (direction == 0)
                                AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                            else
                                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                            node.performAction(action, args)
                            node.recycle()
                        }
                        return@post
                    }
                }
                // For any other Ctrl/Cmd combo not explicitly handled — swallow silently
                return@post
            }

            // ── Alt / Option modifier (word navigation on Mac) ────────────
            if (isAlt) {
                val args = Bundle()
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD)
                args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                when (keyId) {
                    0xFF51 -> { // Alt+Left → previous word
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                        return@post
                    }
                    0xFF53 -> { // Alt+Right → next word
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                        return@post
                    }
                    0xFF08, 0xEF08 -> { // Alt+Backspace → delete word
                        val charArgs = Bundle()
                        charArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD)
                        charArgs.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, charArgs)
                            node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                            node.recycle()
                        }
                        return@post
                    }
                }
                // Fall through for regular Alt combos that produce printable chars
            }

            // Get the IME instance once — prefer IME KeyEvent injection because it works
            // in ALL apps (browsers, games, non-editable views) unlike Accessibility Actions
            val ime = SynergyInputMethodService.instance

            when (keyId) {
                // ── Standard control chars ────────────────────────────────
                0xFF08, 0xEF08, 0x0008               -> deleteLastChar()
                0xFF0D, 0xFF8D, 0xEF0D, 0xEF8D,
                0x000D, 0x000A                        -> triggerEnterKey()
                0xFF09, 0x0009                        -> triggerTab(isShift)
                0xFF1B, 0x001B                        -> {
                    // Escape: try IME first (works in all apps), fall back to system Back
                    if (ime != null) ime.sendEscape()
                    else performGlobalAction(GLOBAL_ACTION_BACK)
                }
                0xFFFF, 0xFF9F, 0xEF9F               -> deleteForwardChar()

                // ── Arrow keys ────────────────────────────────────────────
                // IME KeyEvent injection works universally (text fields, browsers, games)
                // Accessibility granularity movement kept as fallback when IME disconnected
                0xFF51 -> { // Left
                    if (ime != null) {
                        ime.sendArrowLeft(isShift)
                    } else {
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                    }
                }
                0xFF53 -> { // Right
                    if (ime != null) {
                        ime.sendArrowRight(isShift)
                    } else {
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                    }
                }
                0xFF52 -> { // Up
                    if (ime != null) {
                        ime.sendArrowUp(isShift)
                    } else {
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                    }
                }
                0xFF54 -> { // Down
                    if (ime != null) {
                        ime.sendArrowDown(isShift)
                    } else {
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                    }
                }

                // ── Home / End keys ───────────────────────────────────────
                0xFF50, 0xEF50 -> { // Home
                    if (ime != null) ime.sendHome(isShift)
                    else {
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                    }
                }
                0xFF57, 0xEF57 -> { // End
                    if (ime != null) ime.sendEnd(isShift)
                    else {
                        val args = Bundle()
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, isShift)
                        focusedEditable()?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
                            node.recycle()
                        }
                    }
                }

                // ── Page Up / Page Down ───────────────────────────────────
                // IME KeyEvent first (works in scrollable views); gesture scroll as fallback
                0xFF55, 0xEF55 -> { // Page Up
                    if (ime != null) {
                        ime.sendPageUp(isShift)
                    } else {
                        val sw = screenW().toFloat(); val sh = screenH().toFloat()
                        val p = Path().apply { moveTo(sw / 2, sh * 0.25f); lineTo(sw / 2, sh * 0.75f) }
                        dispatchGestureOnActiveDisplay(GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(p, 0, 100)))
                    }
                }
                0xFF56, 0xEF56 -> { // Page Down
                    if (ime != null) {
                        ime.sendPageDown(isShift)
                    } else {
                        val sw = screenW().toFloat(); val sh = screenH().toFloat()
                        val p = Path().apply { moveTo(sw / 2, sh * 0.75f); lineTo(sw / 2, sh * 0.25f) }
                        dispatchGestureOnActiveDisplay(GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(p, 0, 100)))
                    }
                }

                // ── Function keys F1–F12 ──────────────────────────────────
                // IME sends real F-key events; fallback to Android system actions
                0xFFBE -> if (ime != null) ime.sendFunctionKey(1)  else performGlobalAction(GLOBAL_ACTION_BACK)         // F1
                0xFFBF -> if (ime != null) ime.sendFunctionKey(2)  else performGlobalAction(GLOBAL_ACTION_HOME)         // F2
                0xFFC0 -> if (ime != null) ime.sendFunctionKey(3)  else performGlobalAction(GLOBAL_ACTION_RECENTS)      // F3
                0xFFC1 -> if (ime != null) ime.sendFunctionKey(4)  else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) // F4
                0xFFC2 -> if (ime != null) ime.sendFunctionKey(5)  else performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)// F5
                0xFFC3 -> ime?.sendFunctionKey(6)   // F6
                0xFFC4 -> ime?.sendFunctionKey(7)   // F7
                0xFFC5 -> ime?.sendFunctionKey(8)   // F8
                0xFFC6 -> ime?.sendFunctionKey(9)   // F9
                0xFFC7 -> ime?.sendFunctionKey(10)  // F10
                0xFFC8 -> ime?.sendFunctionKey(11)  // F11
                0xFFC9 -> ime?.sendFunctionKey(12)  // F12

                // ── Keypad numbers and operators ─────────────────────────
                in 0xFFB0..0xFFB9                     -> insertChar((keyId - 0xFFB0 + '0'.code).toChar())
                0xFFAC, 0xEFAC                        -> insertChar(',')
                0xFFAD, 0xEFAD                        -> insertChar('-')
                0xFFAE, 0xEFAE                        -> insertChar('.')
                0xFFAF, 0xEFAF                        -> insertChar('/')
                0xFFAA, 0xEFAA                        -> insertChar('*')
                0xFFAB, 0xEFAB                        -> insertChar('+')

                // ── Printable ASCII ───────────────────────────────────────
                in 0x20..0x7E -> {
                    val ch = if (isShift && keyId in 0x61..0x7A)
                        (keyId - 32).toChar() else keyId.toChar()
                    insertChar(ch)
                }

                // ── Unicode beyond ASCII ──────────────────────────────────
                in 0x00A0..0x10FFFF -> {
                    if (keyId !in 0xFE00..0xFFFF) {
                        val s = if (keyId <= 0xFFFF) keyId.toChar().toString()
                        else String(Character.toChars(keyId))
                        insertString(s)
                    }
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

    private fun getNodeText(node: AccessibilityNodeInfo): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()
            val text = node.text?.toString()
            if (node.isShowingHintText || (hint != null && text == hint)) {
                return ""
            }
        }
        return node.text?.toString() ?: ""
    }

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
            textBuffer.append(getNodeText(currentNode))
            lastActiveNode?.recycle()
            lastActiveNode = AccessibilityNodeInfo.obtain(currentNode)
        } else {
            val screenText = getNodeText(currentNode)
            if (screenText.isEmpty() && textBuffer.isNotEmpty()) {
                textBuffer.clear()
            }
        }
    }

    private fun insertString(s: String) {
        val ime = SynergyInputMethodService.instance
        if (ime != null && ime.currentInputConnection != null) {
            ime.typeText(s)
        } else {
            val node = focusedEditable() ?: return
            val currentText = getNodeText(node)
            val selectionStart = node.textSelectionStart
            val selectionEnd = node.textSelectionEnd
            val newText = if (selectionStart >= 0 && selectionEnd >= 0) {
                currentText.substring(0, selectionStart) + s + currentText.substring(selectionEnd)
            } else {
                currentText + s
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val newCursor = if (selectionStart >= 0) selectionStart + s.length else newText.length
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            node.recycle()
        }
    }

    private fun deleteLastChar() {
        val ime = SynergyInputMethodService.instance
        if (ime != null && ime.currentInputConnection != null) {
            ime.sendBackspace()
        } else {
            val node = focusedEditable() ?: return
            val text = getNodeText(node)
            if (text.isNotEmpty()) {
                val start = node.textSelectionStart
                val end = node.textSelectionEnd
                val newText = if (start > 0 && start == end) {
                    text.substring(0, start - 1) + text.substring(start)
                } else if (start >= 0 && start < end) {
                    text.substring(0, start) + text.substring(end)
                } else {
                    text.substring(0, text.length - 1)
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                val newCursor = if (start > 0 && start == end) start - 1 else if (start >= 0) start else newText.length
                val cursorArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            }
            node.recycle()
        }
    }

    /**
     * Like [focusedEditable] but accepts ANY focused node — including
     * WebView HTML inputs which may not be flagged as isEditable.
     */
    private fun focusedAnyNode(): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                root.recycle()
                if (n != null) return n
            }
            for (win in windows) {
                val wRoot = win.root ?: continue
                val n = wRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: wRoot.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                wRoot.recycle()
                if (n != null) return n
            }
        } catch (_: Exception) {}
        return null
    }

    private fun triggerEnterKey() {
        val ime = SynergyInputMethodService.instance
        ime?.sendEnter()
    }

    private fun triggerTab(isShift: Boolean) {
        val ime = SynergyInputMethodService.instance
        if (ime != null) {
            ime.sendTab(isShift)
        } else {
            insertChar('\t')
        }
    }

    private fun deleteForwardChar() {
        val node = focusedAnyNode() ?: return
        // Select forward one char then cut
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                   AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
        node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        node.recycle()
    }

    private fun nodeAction(action: Int) {
        val ime = SynergyInputMethodService.instance
        if (ime != null) {
            val ic = ime.currentInputConnection
            if (ic != null) {
                val menuAction = when (action) {
                    AccessibilityNodeInfo.ACTION_COPY -> android.R.id.copy
                    AccessibilityNodeInfo.ACTION_PASTE -> android.R.id.paste
                    AccessibilityNodeInfo.ACTION_CUT -> android.R.id.cut
                    AccessibilityNodeInfo.ACTION_SELECT -> android.R.id.selectAll
                    else -> 0
                }
                if (menuAction != 0) {
                    ic.performContextMenuAction(menuAction)
                    return
                }
            }
        }
        val node = focusedEditable() ?: return
        node.performAction(action)
        node.recycle()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null) {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null) return node
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
                if (node != null) {
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
        val isInputClass = node.className?.contains("EditText", ignoreCase = true) == true ||
                           node.className?.contains("WebView", ignoreCase = true) == true ||
                           node.isEditable
        if (node.isFocused && (isInputClass || node.text != null)) return AccessibilityNodeInfo.obtain(node)
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

    // ── Clipboard & File Drag Drop ─────────────────────────────────────────

    fun setClipboard(text: String) {
        com.example.synergyclient.data.NotesManager.addClipItem(this, text)
        mainHandler.post {
            try {
                val current = getClipboardText()
                if (current == text) {
                    return@post
                }
                isLocalClipboardChange = true
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Flowport", text))
            } catch (_: Exception) {}
        }
    }

    fun setClipboardImage(imageBytes: ByteArray) {
        mainHandler.post {
            try {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return@post
                val filename = "flowport_clip_${System.currentTimeMillis()}.png"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Flowport")
                }
                val resolver = contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newUri(resolver, "Flowport Image", uri)
                    cm.setPrimaryClip(clip)
                }
            } catch (e: Exception) {
                android.util.Log.e("Flowport", "Failed to copy image to clipboard", e)
            }
        }
    }

    fun saveSharedFile(filename: String, fileBytes: ByteArray) {
        mainHandler.post {
            try {
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Flowport")
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(fileBytes)
                    }
                    android.util.Log.i("Flowport", "Saved file: $filename to Download/Flowport")
                    
                    // Trigger a system scan to make file immediately visible
                    val file = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/Flowport/" + filename
                    )
                    android.media.MediaScannerConnection.scanFile(
                        this, arrayOf(file.absolutePath), null
                    ) { _, _ -> }
                }
            } catch (e: Exception) {
                android.util.Log.e("Flowport", "Failed to save drag-drop file", e)
            }
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val display = windowManager.defaultDisplay
            val realSize = android.graphics.Point()
            display.getRealSize(realSize)
            Pair(realSize.x, realSize.y)
        }
    } catch (_: Exception) {
        val dm = resources.displayMetrics
        Pair(dm.widthPixels, dm.heightPixels)
    }

    fun setMacMode(enabled: Boolean) {
        cachedMacServerMode = enabled
    }

    private fun screenW(): Int = getFullScreenSize().first
    private fun screenH(): Int = getFullScreenSize().second
    private fun clampX(v: Int) = v.coerceIn(0, (screenW() - 1).coerceAtLeast(0))
    private fun clampY(v: Int) = v.coerceIn(0, (screenH() - 1).coerceAtLeast(0))

    // ── DeX / Multi-display helpers ──────────────────────────────────────────

    private fun getActiveDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays ?: return null
        val config = context.resources.configuration
        val isDeX = config.toString().contains("dexMode")
        
        if (isDeX) {
            val dexDisplay = displays.find { it.displayId != Display.DEFAULT_DISPLAY }
            if (dexDisplay != null) {
                return dexDisplay
            }
        }
        return displays.find { it.displayId == Display.DEFAULT_DISPLAY }
    }

    private fun updateDisplayAndOverlay() {
        mainHandler.post {
            try {
                val targetDisplay = getActiveDisplay(this) ?: return@post
                val targetId = targetDisplay.displayId
                
                if (targetId == currentDisplayId && ::windowManager.isInitialized) {
                    return@post
                }
                
                if (::overlayView.isInitialized && ::windowManager.isInitialized) {
                    try {
                        windowManager.removeView(overlayView)
                    } catch (_: Exception) {}
                }
                
                currentDisplayId = targetId
                
                val displayContext = if (targetId == Display.DEFAULT_DISPLAY) {
                    this
                } else {
                    createDisplayContext(targetDisplay)
                }
                windowManager = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
                
                if (!::overlayView.isInitialized) {
                    overlayView = CursorOverlayView(this)
                }
                
                val screenHeight = getFullScreenSize().second
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    y = 4
                    height = (screenHeight - 8).coerceAtLeast(100)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                
                windowManager.addView(overlayView, params)
                android.util.Log.i("SynergyApp", "Overlay updated for display $currentDisplayId")
            } catch (e: Exception) {
                android.util.Log.e("SynergyApp", "Failed to update display/overlay: ${e.message}", e)
            }
        }
    }

    private fun dispatchGestureOnActiveDisplay(builder: GestureDescription.Builder, callback: GestureResultCallback? = null) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setDisplayId(currentDisplayId)
        }
        dispatchGesture(builder.build(), callback, mainHandler)
    }

    companion object {
        @Volatile
        var instance: SynergyAccessibilityService? = null
            private set
    }
}
