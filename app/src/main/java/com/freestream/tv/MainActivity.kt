package com.freestream.tv

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var customViewContainer: FrameLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var tvLoadingStatus: TextView
    private lateinit var errorLayout: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var virtualCursor: ImageView
    private lateinit var osdHudContainer: FrameLayout
    private lateinit var ivOsdIcon: ImageView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var activeDomain: String? = null
    private var lastBackPressTime = 0L

    // Riconoscimento dispositivo (TV vs Smartphone/Tablet)
    private var isTv = false

    // Script iniettati nella pagina
    private var docStartScripts = false
    private var adblockJs = ""
    private var playerAgentJs = ""
    private var topFrameJs = ""

    // Cursore virtuale stile Silk Browser Fire TV (movimento a frame con accelerazione)
    private var isMouseMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var dirLeft = false
    private var dirRight = false
    private var dirUp = false
    private var dirDown = false
    private var moveStartNanos = 0L
    private var lastFrameNanos = 0L
    private var frameLoopRunning = false
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f

    // Stato tastiera virtuale / input field
    private var isInputFocused = false
    private val keyboardHandler = Handler(Looper.getMainLooper())
    private val showKeyboardRunnable = Runnable { showSoftKeyboard() }

    // Gestione pressione prolungata OK / CENTER
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var okLongPressTriggered = false
    private var okDownSeen = false

    private val longPressRunnable = Runnable {
        okLongPressTriggered = true
        toggleMouseMode()
    }

    // Throttling dei tasti direzionali tenuti premuti (modalità controller)
    private var lastNavRepeatTime = 0L

    // Gestione OSD centrale stile Mac Caps Lock
    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        osdHudContainer.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(300)
            .withEndAction { osdHudContainer.visibility = View.GONE }
            .start()
    }

    private val cursorFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onCursorFrame(frameTimeNanos)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isTv = isTvDevice()

        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        customViewContainer = findViewById(R.id.customViewContainer)
        loadingLayout = findViewById(R.id.loadingLayout)
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus)
        errorLayout = findViewById(R.id.errorLayout)
        btnRetry = findViewById(R.id.btnRetry)
        virtualCursor = findViewById(R.id.virtualCursor)
        osdHudContainer = findViewById(R.id.osdHudContainer)
        ivOsdIcon = findViewById(R.id.ivOsdIcon)

        btnRetry.setOnClickListener {
            startDomainDiscoveryAndLoad()
        }

        prepareScripts()
        setupWebView()
        startDomainDiscoveryAndLoad()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        // Evita che l'audio continui quando si torna alla Home della Fire TV
        webView.evaluateJavascript("window.fsPlayer && window.fsPlayer('pause')", null)
        webView.onPause()
        releaseCursorKeys()
        super.onPause()
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(cursorFrameCallback)
        keyboardHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        osdHandler.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        return false
    }

    private fun prepareScripts() {
        adblockJs = readAssetFile("adblock.js").orEmpty()
        playerAgentJs = readAssetFile("player_agent.js").orEmpty()
        val css = readAssetFile("tv_styles.css").orEmpty()
        val nav = readAssetFile("tv_navigation.js").orEmpty()
        topFrameJs = "if (window.top === window) {\nwindow.__fsCss = ${JSONObject.quote(css)};\n$nav\n}"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (isTv) {
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        } else {
            settings.userAgentString = null
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        // Iniezione a document-start in TUTTI i frame: adblock prima degli script pubblicitari,
        // agente player anche nell'iframe cross-origin di JWPlayer, navigazione TV senza attendere onPageFinished
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                val allOrigins = setOf("*")
                WebViewCompat.addDocumentStartJavaScript(webView, adblockJs, allOrigins)
                WebViewCompat.addDocumentStartJavaScript(webView, playerAgentJs, allOrigins)
                WebViewCompat.addDocumentStartJavaScript(webView, topFrameJs, allOrigins)
                docStartScripts = true
            } catch (e: Exception) {
                docStartScripts = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                customViewContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                customViewContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                customViewContainer.visibility = View.GONE
                customViewContainer.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isInputFocused) {
                    isInputFocused = false
                    hideSoftKeyboard()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Il sito può reindirizzare a un nuovo dominio: lo ricordiamo per il prossimo avvio
                DomainResolver.rememberIfSite(applicationContext, url)
                loadingLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                errorLayout.visibility = View.GONE
                if (!webView.hasFocus()) webView.requestFocus()

                // Fallback per WebView senza DOCUMENT_START_SCRIPT (gli script sono idempotenti)
                webView.evaluateJavascript(adblockJs, null)
                webView.evaluateJavascript(playerAgentJs, null)
                webView.evaluateJavascript(topFrameJs, null)
                if (isMouseMode) webView.evaluateJavascript("window.tvClearFocus && window.tvClearFocus()", null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.url == null) return false
                val host = request.url.host.orEmpty()
                val internal = DomainResolver.isOfficialHost(host)
                if (!internal && request.isForMainFrame && request.hasGesture()) {
                    Toast.makeText(this@MainActivity, getString(R.string.external_link_blocked), Toast.LENGTH_SHORT).show()
                }
                return !internal
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    loadingLayout.visibility = View.GONE
                    errorLayout.visibility = View.VISIBLE
                    btnRetry.requestFocus()
                }
            }
        }
    }

    // Interfaccia per comunicazione bidirezionale da WebView
    inner class WebAppInterface {
        @JavascriptInterface
        fun onInputFocus(focused: Boolean) {
            runOnUiThread {
                isInputFocused = focused
                keyboardHandler.removeCallbacks(showKeyboardRunnable)
                if (focused) {
                    // Ritardo: lascia a Chromium il tempo di riavviare l'InputConnection sul nuovo campo,
                    // altrimenti la tastiera si apre sulla vecchia connessione e si richiude subito
                    keyboardHandler.postDelayed(showKeyboardRunnable, 220)
                } else {
                    hideSoftKeyboard()
                }
            }
        }

        // Tap nativo (evento trusted) in coordinate CSS della pagina
        @JavascriptInterface
        fun tapAt(cssX: Float, cssY: Float, cssViewportWidth: Float) {
            runOnUiThread {
                val factor = if (cssViewportWidth > 0f && webView.width > 0) {
                    webView.width / cssViewportWidth
                } else {
                    resources.displayMetrics.density
                }
                dispatchTap(cssX * factor, cssY * factor)
            }
        }
    }

    fun showSoftKeyboard() {
        if (!isInputFocused) return
        if (!webView.hasFocus()) webView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        @Suppress("DEPRECATION")
        imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
    }

    fun hideSoftKeyboard() {
        keyboardHandler.removeCallbacks(showKeyboardRunnable)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    private fun startDomainDiscoveryAndLoad() {
        errorLayout.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        tvLoadingStatus.text = getString(R.string.loading_message)

        lifecycleScope.launch {
            try {
                val domain = DomainResolver.resolveActiveDomain(applicationContext)
                activeDomain = domain
                tvLoadingStatus.text = getString(R.string.connecting_message)
                webView.loadUrl(domain)
            } catch (e: Exception) {
                loadingLayout.visibility = View.GONE
                errorLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun readAssetFile(fileName: String): String? {
        return try {
            assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun js(code: String) {
        webView.evaluateJavascript(code, null)
    }

    // Intercettazione comandi telecomando
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // Schermata di errore: lascia la navigazione nativa sul pulsante Riprova
        if (errorLayout.visibility == View.VISIBLE || loadingLayout.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }

        // Campo di testo attivo. Se la tastiera è aperta riceve i tasti prima dell'Activity:
        // qui arrivano solo quando è chiusa, quindi le frecce servono per uscire dal campo.
        if (isInputFocused && !isMouseMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) leaveInputField()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        leaveInputField()
                        js("window.tvNavigate && window.tvNavigate('${directionName(keyCode)}')")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (action == KeyEvent.ACTION_UP) {
                        js("window.tvTapActiveInput && window.tvTapActiveInput()")
                        keyboardHandler.removeCallbacks(showKeyboardRunnable)
                        keyboardHandler.postDelayed(showKeyboardRunnable, 220)
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
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
                    if (event.repeatCount == 0) toggleMouseMode()
                    return true
                }
            }
        }

        // 2. Tasto Indietro (BACK)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleBack()
            return true
        }

        // 3. Tasto OK / Invio: Pressione breve = Click, Pressione prolungata = Switch Cursore
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
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
                if (isMouseMode) {
                    clickVirtualCursor()
                } else {
                    js("window.tvNavigate && window.tvNavigate('Enter')")
                }
            }
            return true
        }

        // 4. Frecce Direzionali D-Pad
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {

            if (isMouseMode) {
                val pressed = action == KeyEvent.ACTION_DOWN
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> dirUp = pressed
                    KeyEvent.KEYCODE_DPAD_DOWN -> dirDown = pressed
                    KeyEvent.KEYCODE_DPAD_LEFT -> dirLeft = pressed
                    KeyEvent.KEYCODE_DPAD_RIGHT -> dirRight = pressed
                }
                if (pressed) {
                    if (!frameLoopRunning) moveStartNanos = System.nanoTime()
                    // Spostamento immediato minimo: un tocco singolo sposta sempre di qualche pixel
                    nudgeCursor(keyCode)
                    startCursorLoop()
                }
                return true
            }

            if (action == KeyEvent.ACTION_DOWN) {
                val repeat = event.repeatCount > 0
                if (repeat) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNavRepeatTime < NAV_REPEAT_INTERVAL_MS) return true
                    lastNavRepeatTime = now
                }
                js("window.tvNavigate && window.tvNavigate('${directionName(keyCode)}', $repeat)")
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun directionName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        else -> "ArrowRight"
    }

    private fun leaveInputField() {
        isInputFocused = false
        hideSoftKeyboard()
        js("window.tvBlurInput ? window.tvBlurInput() : (document.activeElement && document.activeElement.blur())")
    }

    private fun handleBack() {
        if (isMouseMode) {
            toggleMouseMode()
            return
        }
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        // Prima chiudi menu/sidebar/dropdown della pagina, poi la cronologia, infine l'uscita
        webView.evaluateJavascript("(window.tvBack && window.tvBack()) === true") { result ->
            if (result == "true") return@evaluateJavascript
            if (webView.canGoBack()) {
                webView.goBack()
                return@evaluateJavascript
            }
            val currentTime = SystemClock.uptimeMillis()
            if (currentTime - lastBackPressTime < 2500) {
                finish()
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(this, getString(R.string.press_back_again), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playerCommand(args: String) {
        js("window.fsPlayer && window.fsPlayer($args)")
    }

    // Modalità Puntatore Virtuale stile Silk Browser Fire TV
    private fun toggleMouseMode() {
        isMouseMode = !isMouseMode
        releaseCursorKeys()

        if (isMouseMode) {
            val w = if (webView.width > 0) webView.width.toFloat() else 1920f
            val h = if (webView.height > 0) webView.height.toFloat() else 1080f
            cursorX = w / 2f
            cursorY = h / 2f
            updateCursorPosition()
            virtualCursor.visibility = View.VISIBLE
            js("window.tvClearFocus && window.tvClearFocus()")
            dispatchHover()
        } else {
            virtualCursor.visibility = View.GONE
            js("window.tvFocusNear && window.tvFocusNear($cursorX, $cursorY, ${webView.width})")
        }

        showModeOsd(isMouseMode)
    }

    // Mostra OSD centrale stile Mac Caps Lock
    private fun showModeOsd(isMouse: Boolean) {
        osdHandler.removeCallbacks(hideOsdRunnable)
        ivOsdIcon.setImageResource(if (isMouse) R.drawable.ic_osd_mouse else R.drawable.ic_osd_controller)

        osdHudContainer.animate().cancel()
        osdHudContainer.visibility = View.VISIBLE
        osdHudContainer.alpha = 0f
        osdHudContainer.scaleX = 0.8f
        osdHudContainer.scaleY = 0.8f

        osdHudContainer.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(160)
            .start()

        osdHandler.postDelayed(hideOsdRunnable, 1100)
    }

    private fun releaseCursorKeys() {
        dirLeft = false
        dirRight = false
        dirUp = false
        dirDown = false
    }

    private fun startCursorLoop() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
    }

    private fun nudgeCursor(keyCode: Int) {
        val step = max(webView.width, 1280) * 0.006f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveCursorBy(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursorBy(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursorBy(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursorBy(step, 0f)
        }
    }

    // Movimento fluido a 60fps: velocità iniziale bassa per la precisione, poi accelerazione progressiva
    private fun onCursorFrame(frameTimeNanos: Long) {
        val anyHeld = dirLeft || dirRight || dirUp || dirDown
        if (!isMouseMode || !anyHeld) {
            frameLoopRunning = false
            flushEdgeScroll()
            return
        }

        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos

        val ref = max(webView.width, 1280).toFloat()
        val heldSeconds = ((System.nanoTime() - moveStartNanos) / 1_000_000_000f).coerceAtLeast(0f)
        val t = min(1f, heldSeconds / CURSOR_ACCEL_SECONDS)
        val speed = ref * (CURSOR_MIN_SPEED + (CURSOR_MAX_SPEED - CURSOR_MIN_SPEED) * t * t)

        var vx = 0f
        var vy = 0f
        if (dirLeft) vx -= 1f
        if (dirRight) vx += 1f
        if (dirUp) vy -= 1f
        if (dirDown) vy += 1f
        if (vx != 0f && vy != 0f) {
            vx *= 0.7071f
            vy *= 0.7071f
        }

        moveCursorBy(vx * speed * dt, vy * speed * dt)
        Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        val w = if (webView.width > 0) webView.width.toFloat() else 1920f
        val h = if (webView.height > 0) webView.height.toFloat() else 1080f

        cursorX = (cursorX + dx).coerceIn(0f, w - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, h - 1f)

        // Scorrimento della pagina quando il cursore spinge contro il bordo (stile Silk)
        val edge = h * 0.06f
        if (dy > 0 && cursorY >= h - edge) pendingScrollY += dy
        if (dy < 0 && cursorY <= edge) pendingScrollY += dy
        if (dx > 0 && cursorX >= w - edge) pendingScrollX += dx
        if (dx < 0 && cursorX <= edge) pendingScrollX += dx

        updateCursorPosition()
        dispatchHover()
        flushEdgeScroll()
    }

    private fun flushEdgeScroll() {
        val sx = pendingScrollX.toInt()
        val sy = pendingScrollY.toInt()
        if (sx == 0 && sy == 0) return
        pendingScrollX -= sx
        pendingScrollY -= sy
        js("window.fsScrollAt ? window.fsScrollAt($cursorX, $cursorY, $sx, $sy, ${webView.width}) : window.scrollBy($sx / (window.devicePixelRatio||1), $sy / (window.devicePixelRatio||1))")
    }

    private fun updateCursorPosition() {
        // La punta del cursore coincide con il centro dell'icona circolare
        val halfW = (virtualCursor.width / 2f).takeIf { it > 0 } ?: 16f
        val halfH = (virtualCursor.height / 2f).takeIf { it > 0 } ?: 16f
        virtualCursor.translationX = cursorX - halfW
        virtualCursor.translationY = cursorY - halfH
    }

    // Eventi hover del mouse: il sito mostra anteprime/effetti come su PC
    private fun dispatchHover() {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        webView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun dispatchTap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(down)
        down.recycle()
        webView.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 40)
    }

    private fun clickVirtualCursor() {
        virtualCursor.animate().cancel()
        virtualCursor.animate()
            .scaleX(0.72f)
            .scaleY(0.72f)
            .setDuration(60)
            .withEndAction {
                virtualCursor.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
            }
            .start()

        dispatchTap(cursorX, cursorY)
    }

    companion object {
        private const val LONG_PRESS_THRESHOLD = 600L
        private const val NAV_REPEAT_INTERVAL_MS = 110L

        // Velocità del cursore in frazioni della larghezza schermo al secondo
        private const val CURSOR_MIN_SPEED = 0.22f
        private const val CURSOR_MAX_SPEED = 1.05f
        private const val CURSOR_ACCEL_SECONDS = 1.1f

        // Reti pubblicitarie bloccate a livello di rete (anche dentro gli iframe del player)
        private val AD_HOSTS = listOf(
            "spbgc.com",
            "doubleclick.net",
            "googlesyndication.com",
            "adservice.google.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "exoclick.com",
            "juicyads.com",
            "hilltopads.net",
            "onclickads.net",
            "adcash.com",
            "mgid.com",
            "a-ads.com"
        )
    }
}
