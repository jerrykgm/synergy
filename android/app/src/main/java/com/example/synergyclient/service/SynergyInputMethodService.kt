package com.example.synergyclient.service

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.LinearLayout

class SynergyInputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        // Return an empty, invisible view that takes up zero space.
        val layout = LinearLayout(this)
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        )
        return layout
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Ensure the input view is hidden immediately.
        requestHideSelf(0)
    }
}
