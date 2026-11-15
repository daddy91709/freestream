package com.freestream.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView

class RemoteKeyHandler(
    private val webViewProvider: () -> WebView,
    private val keyboardBridge: SoftKeyboardBridge,
    private val cursorController: VirtualCursorController,
    private val isOverlayBlockingNavigation: () -> Boolean,
    private val tryRecoverFromErrorBack: () -> Boolean,
    private val isCustomViewActive: () -> Boolean,
    private val hideCustomView: () -> Unit,
    private val evalJs: (String) -> Unit,
    private val evalJsWithResult: (String, (String) -> Unit) -> Unit,
    private val onExitConfirmed: () -> Unit,
    private val showBackExitToast: () -> Unit
) {
    private var lastBackPressTime = 0L
    private var lastNavRepeatTime = 0L

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var okLongPressTriggered = false
    private var okDownSeen = false

    private val longPressRunnable = Runnable {
        okLongPressTriggered = true
        cursorController.toggleMouseMode()
    }

    /** Restituisce true/false se l'evento è gestito, oppure null per delegare a super.dispatchKeyEvent. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean? {
        val keyCode = event.keyCode
        val action = event.action

        // Tasto Indietro (BACK): sempre gestito uniformemente tramite handleBackPress()
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleBackPress()
            return true
        }

        // Schermata di errore o caricamento: lascia la navigazione nativa sul pulsante Riprova
        if (isOverlayBlockingNavigation()) {
            return null
        }

        // Campo di testo attivo. Se la tastiera è aperta riceve i tasti prima dell'Activity:
        // qui arrivano solo quando è chiusa, quindi le frecce servono per uscire dal campo.
        if (keyboardBridge.isInputFocused && !cursorController.isMouseMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        keyboardBridge.leaveInputField(evalJs)
                        evalJs("window.tvNavigate && window.tvNavigate('${directionName(keyCode)}')")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (action == KeyEvent.ACTION_UP) {
                        evalJs("window.tvTapActiveInput && window.tvTapActiveInput()")
                        keyboardBridge.scheduleShowKeyboard()
                    }
                    return true
                }
            }
            return null
        }

        // 1. Tasti Multimediali
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    playerCommand("'toggle'")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    playerCommand("'play'")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    playerCommand("'pause'")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    playerCommand("'seek', 10")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    playerCommand("'seek', -10")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playerCommand("'next'")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playerCommand("'seek', -30")
                    return true
                }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> {
                    if (event.repeatCount == 0) cursorController.toggleMouseMode()
                    return true
                }
            }
        }

        // 2. Tasto OK / Invio: Pressione breve = Click, Pressione prolungata = Switch Cursore
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okDownSeen = true
                    okLongPressTriggered = false
                    longPressHandler.removeCallbacks(longPressRunnable)
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD)
                }
            } else if (action == KeyEvent.ACTION_UP) {
                longPressHandler.removeCallbacks(longPressRunnable)
                // Ignora un UP orfano (es. OK premuto per chiudere la tastiera)
                if (!okDownSeen) return true
                okDownSeen = false
                if (okLongPressTriggered) {
                    okLongPressTriggered = false
                    return true
                }
                if (cursorController.isMouseMode) {
                    cursorController.clickVirtualCursor()
                } else {
                    evalJs("window.tvNavigate && window.tvNavigate('Enter')")
                }
            }
            return true
        }

        // 4. Frecce Direzionali D-Pad
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            if (cursorController.isMouseMode) {
                return cursorController.handleDpadKey(keyCode, action, event.repeatCount)
            }

            if (action == KeyEvent.ACTION_DOWN) {
                val repeat = event.repeatCount > 0
                if (repeat) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNavRepeatTime < NAV_REPEAT_INTERVAL_MS) return true
                    lastNavRepeatTime = now
                }
                evalJs("window.tvNavigate && window.tvNavigate('${directionName(keyCode)}', $repeat)")
            }
            return true
        }

        return null
    }

    fun destroy() {
        longPressHandler.removeCallbacksAndMessages(null)
    }

    fun handleBackPress() {
        if (tryRecoverFromErrorBack()) return
        if (isOverlayBlockingNavigation()) {
            confirmExitOrToast()
            return
        }
        if (keyboardBridge.isInputFocused) {
            keyboardBridge.leaveInputField(evalJs)
            return
        }
        if (cursorController.isMouseMode) {
            cursorController.toggleMouseMode()
            return
        }
        if (isCustomViewActive()) {
            hideCustomView()
            return
        }
        // Prima chiudi menu/sidebar/dropdown della pagina, poi la cronologia, infine l'uscita
        evalJsWithResult("(window.tvBack && window.tvBack()) === true") { result ->
            if (result == "true") return@evalJsWithResult
            val webView = webViewProvider()
            if (webView.canGoBack()) {
                webView.goBack()
                return@evalJsWithResult
            }
            confirmExitOrToast()
        }
    }

    private fun confirmExitOrToast() {
        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastBackPressTime < BACK_EXIT_WINDOW_MS) {
            onExitConfirmed()
        } else {
            lastBackPressTime = currentTime
            showBackExitToast()
        }
    }

    private fun playerCommand(args: String) {
        evalJs("window.fsPlayer && window.fsPlayer($args)")
    }

    private fun directionName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        else -> "ArrowRight"
    }

    companion object {
        private const val LONG_PRESS_THRESHOLD = 600L
        private const val NAV_REPEAT_INTERVAL_MS = 110L
        private const val BACK_EXIT_WINDOW_MS = 2500L
    }
}
