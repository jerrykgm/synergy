package com.example.synergyclient.service

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

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
        // Return an empty view to hide the soft keyboard completely
        val view = View(this)
        view.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }

    fun typeText(text: String) {
        val ic = currentInputConnection
        ic?.commitText(text, 1)
    }

    fun sendBackspace() {
        val ic = currentInputConnection
        ic?.deleteSurroundingText(1, 0)
    }

    fun sendEnter() {
        val ic = currentInputConnection
        ic?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        ic?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
    }
}
