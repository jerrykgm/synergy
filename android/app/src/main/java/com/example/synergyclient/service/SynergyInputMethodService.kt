package com.example.synergyclient.service

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.os.SystemClock

class SynergyInputMethodService : InputMethodService() {
    companion object {
        @Volatile
        var instance: SynergyInputMethodService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onCreateInputView(): View {
        // Return zero-size view — Synergy keyboard is invisible by design
        val view = View(this)
        view.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
        return view
    }

    override fun onEvaluateInputViewShown(): Boolean {
        // Return false to prevent Android from showing the keyboard container layout
        return false
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Prevent full screen mode in landscape orientations
        return false
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }

    // ── Printable text ────────────────────────────────────────────────────

    fun typeText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    // ── Control keys via InputConnection KeyEvent injection ───────────────
    // Using sendKeyEvent ensures keys work in ALL apps (browsers, games,
    // non-editable views) on both Mac and Windows server modes.

    fun sendBackspace() {
        sendKey(KeyEvent.KEYCODE_DEL)
    }

    fun sendForwardDelete() {
        sendKey(KeyEvent.KEYCODE_FORWARD_DEL)
    }

    fun sendEnter() {
        val ic = currentInputConnection ?: return
        val actionId = currentInputEditorInfo?.actionId ?: 0
        // Honour the editor's IME action (e.g. Search/Done/Next) if set,
        // otherwise send a real Enter keydown/keyup pair
        if (actionId != 0 && actionId != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(actionId)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun sendTab(isShift: Boolean) {
        val meta = if (isShift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        sendKey(KeyEvent.KEYCODE_TAB, meta)
    }

    fun sendEscape() {
        sendKey(KeyEvent.KEYCODE_ESCAPE)
    }

    // ── Arrow / navigation keys ───────────────────────────────────────────

    fun sendArrowLeft(shift: Boolean = false, ctrl: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, buildMeta(shift, ctrl))
    }

    fun sendArrowRight(shift: Boolean = false, ctrl: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, buildMeta(shift, ctrl))
    }

    fun sendArrowUp(shift: Boolean = false, ctrl: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_DPAD_UP, buildMeta(shift, ctrl))
    }

    fun sendArrowDown(shift: Boolean = false, ctrl: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, buildMeta(shift, ctrl))
    }

    fun sendHome(shift: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_MOVE_HOME, buildMeta(shift, false))
    }

    fun sendEnd(shift: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_MOVE_END, buildMeta(shift, false))
    }

    fun sendPageUp(shift: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_PAGE_UP, buildMeta(shift, false))
    }

    fun sendPageDown(shift: Boolean = false) {
        sendKey(KeyEvent.KEYCODE_PAGE_DOWN, buildMeta(shift, false))
    }

    // ── Function keys F1–F12 ──────────────────────────────────────────────

    fun sendFunctionKey(n: Int) {
        val code = when (n) {
            1  -> KeyEvent.KEYCODE_F1;  2  -> KeyEvent.KEYCODE_F2
            3  -> KeyEvent.KEYCODE_F3;  4  -> KeyEvent.KEYCODE_F4
            5  -> KeyEvent.KEYCODE_F5;  6  -> KeyEvent.KEYCODE_F6
            7  -> KeyEvent.KEYCODE_F7;  8  -> KeyEvent.KEYCODE_F8
            9  -> KeyEvent.KEYCODE_F9;  10 -> KeyEvent.KEYCODE_F10
            11 -> KeyEvent.KEYCODE_F11; 12 -> KeyEvent.KEYCODE_F12
            else -> return
        }
        sendKey(code)
    }

    // ── Copy / Paste / Cut / Select-All via InputConnection ───────────────

    fun sendCopy()      { currentInputConnection?.performContextMenuAction(android.R.id.copy) }
    fun sendPaste()     { currentInputConnection?.performContextMenuAction(android.R.id.paste) }
    fun sendCut()       { currentInputConnection?.performContextMenuAction(android.R.id.cut) }
    fun sendSelectAll() { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }

    fun switchToPreviousKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                switchToPreviousInputMethod()
            } catch (e: Exception) {
                fallbackSwitch()
            }
        } else {
            fallbackSwitch()
        }
    }

    private fun fallbackSwitch() {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val token = window?.window?.attributes?.token
            if (token != null) {
                @Suppress("DEPRECATION")
                imm?.switchToLastInputMethod(token)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Generic key sender ────────────────────────────────────────────────

    /** Sends a key down + up pair through the current InputConnection. */
    fun sendKey(keyCode: Int, metaState: Int = 0) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP,   keyCode, 0, metaState))
    }

    // ── Meta state builder ────────────────────────────────────────────────

    private fun buildMeta(shift: Boolean, ctrl: Boolean): Int {
        var m = 0
        if (shift) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl)  m = m or KeyEvent.META_CTRL_ON  or KeyEvent.META_CTRL_LEFT_ON
        return m
    }
}

