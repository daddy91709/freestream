package com.freestream.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class SoftKeyboardBridge(
    private val window: Window,
    private val webViewProvider: () -> WebView
) {
    var isInputFocused: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val showKeyboardRunnable = Runnable { showSoftKeyboard() }

    fun onInputFocusChanged(focused: Boolean) {
        isInputFocused = focused
        handler.removeCallbacks(showKeyboardRunnable)
        if (focused) {
            // Ritardo: lascia a Chromium il tempo di riavviare l'InputConnection sul nuovo campo,
            // altrimenti la tastiera si apre sulla vecchia connessione e si richiude subito
            handler.postDelayed(showKeyboardRunnable, KEYBOARD_SHOW_DELAY_MS)
        } else {
            hideSoftKeyboard()
        }
    }

    fun scheduleShowKeyboard() {
        handler.removeCallbacks(showKeyboardRunnable)
        handler.postDelayed(showKeyboardRunnable, KEYBOARD_SHOW_DELAY_MS)
    }

    fun showSoftKeyboard() {
        if (!isInputFocused) return
        val webView = webViewProvider()
        if (!webView.hasFocus()) webView.requestFocus()
        WindowCompat.getInsetsController(window, webView).show(WindowInsetsCompat.Type.ime())
        val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSoftKeyboard() {
        handler.removeCallbacks(showKeyboardRunnable)
        val webView = webViewProvider()
        WindowCompat.getInsetsController(window, webView).hide(WindowInsetsCompat.Type.ime())
        val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    fun leaveInputField(evalJs: (String) -> Unit) {
        isInputFocused = false
        hideSoftKeyboard()
        evalJs("window.tvBlurInput ? window.tvBlurInput() : (document.activeElement && document.activeElement.blur())")
    }

    fun resetIfFocused() {
        if (isInputFocused) {
            isInputFocused = false
            hideSoftKeyboard()
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val KEYBOARD_SHOW_DELAY_MS = 220L
    }
}
