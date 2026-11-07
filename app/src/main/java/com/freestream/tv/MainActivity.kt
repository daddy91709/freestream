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
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import kotlinx.coroutines.launch
import java.io.InputStream

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

    // Cursore virtuale stile Silk Browser Fire TV
    private var isMouseMode = false
    private var cursorX = 640f
    private var cursorY = 360f
    private val cursorSpeed = 35f

    // Stato tastiera virtuale / input field
    private var isInputFocused = false

    // Gestione pressione prolungata OK / CENTER
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var okLongPressTriggered = false
    private val LONG_PRESS_THRESHOLD = 600L

    private val longPressRunnable = Runnable {
        okLongPressTriggered = true
        toggleMouseMode()
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Rileva se il dispositivo è una TV o un telefono/tablet
        isTv = isTvDevice()

        if (isTv) {
            // Su TV: blocca orientamento in Landscape
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            // Su Smartphone/Tablet: consente libera rotazione Portrait e Landscape
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Mantieni display sempre acceso durante la visione
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        setupWebView()
        startDomainDiscoveryAndLoad()
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return true
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false // Autoplay video
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        if (isTv) {
            // Su TV: User-Agent desktop moderno per griglia cinematografica widescreen
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        } else {
            // Su smartphone: User-Agent mobile nativo per layout responsive touch in portrait e landscape
            settings.userAgentString = null
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        // Bridge JavaScript per eventi tastiera virtuale TV
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

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
                injectAdBlock()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                errorLayout.visibility = View.GONE

                injectTvStyles()
                injectTvNavigation()
                injectAdBlock()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.contains("streamingcommunity") || (activeDomain != null && url.contains(activeDomain!!))) {
                    false
                } else {
                    true
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
                if (focused) {
                    showSoftKeyboard()
                } else {
                    hideSoftKeyboard()
                }
            }
        }
    }

    fun showSoftKeyboard() {
        webView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
    }

    fun hideSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    private fun startDomainDiscoveryAndLoad() {
        errorLayout.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        tvLoadingStatus.text = getString(R.string.loading_message)

        lifecycleScope.launch {
            try {
                val domain = DomainResolver.resolveActiveDomain()
                activeDomain = domain
                tvLoadingStatus.text = "Connessione al dominio..."
                webView.loadUrl(domain)
            } catch (e: Exception) {
                loadingLayout.visibility = View.GONE
                errorLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun injectTvStyles() {
        val css = readAssetFile("tv_styles.css") ?: return
        val encoded = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
        val js = """
            (function() {
                var parent = document.head || document.documentElement;
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = window.atob('$encoded');
                parent.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectTvNavigation() {
        val jsCode = readAssetFile("tv_navigation.js") ?: return
        webView.evaluateJavascript(jsCode, null)
    }

    private fun injectAdBlock() {
        val jsCode = readAssetFile("adblock.js") ?: return
        webView.evaluateJavascript(jsCode, null)
    }

    private fun readAssetFile(fileName: String): String? {
        return try {
            val input: InputStream = assets.open(fileName)
            val size = input.available()
            val buffer = ByteArray(size)
            input.read(buffer)
            input.close()
            String(buffer, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // Intercettazione comandi telecomando
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // Se un campo di testo è attivo, lascia che la tastiera virtuale di Fire TV gestisca i tasti D-Pad per scrivere
        if (isInputFocused) {
            if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                hideSoftKeyboard()
                webView.evaluateJavascript("if (document.activeElement) document.activeElement.blur();", null)
                isInputFocused = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 1. Tasti Multimediali
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    toggleVideoPlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    seekVideo(10)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    seekVideo(-10)
                    return true
                }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> {
                    toggleMouseMode()
                    return true
                }
            }
        }

        // 2. Tasto Indietro (BACK)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (isMouseMode) {
                    toggleMouseMode() // Esce dalla modalità mouse e torna a controller
                    return true
                }
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return true
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                val currentTime = SystemClock.uptimeMillis()
                if (currentTime - lastBackPressTime < 2500) {
                    finish()
                } else {
                    lastBackPressTime = currentTime
                    Toast.makeText(this, "Premi di nuovo per uscire da FreeStream", Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }

        // 3. Tasto OK / Invio: Pressione breve = Click, Pressione prolungata (>600ms) = Switch Cursore
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okLongPressTriggered = false
                    longPressHandler.removeCallbacks(longPressRunnable)
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD)
                }
                return true
            } else if (action == KeyEvent.ACTION_UP) {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (okLongPressTriggered) {
                    okLongPressTriggered = false
                    return true
                }
                if (isMouseMode) {
                    clickVirtualCursor()
                } else {
                    webView.evaluateJavascript("window.tvNavigate('Enter')", null)
                }
                return true
            }
            return true
        }

        // 4. Frecce Direzionali D-Pad (UP, DOWN, LEFT, RIGHT)
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {

            if (action == KeyEvent.ACTION_DOWN) {
                if (isMouseMode) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> moveCursor(0f, -cursorSpeed)
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveCursor(0f, cursorSpeed)
                        KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-cursorSpeed, 0f)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(cursorSpeed, 0f)
                    }
                } else {
                    val direction = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
                        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
                        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
                        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
                        else -> ""
                    }
                    if (direction.isNotEmpty()) {
                        webView.evaluateJavascript("window.tvNavigate('$direction')", null)
                    }
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun toggleVideoPlayPause() {
        val js = """
            (function() {
                var v = document.querySelector('video');
                if (v) { v.paused ? v.play() : v.pause(); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun seekVideo(seconds: Int) {
        val js = """
            (function() {
                var v = document.querySelector('video');
                if (v) { v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + ($seconds))); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // Modalità Puntatore Virtuale stile Silk Browser Fire TV
    private fun toggleMouseMode() {
        isMouseMode = !isMouseMode

        val halfW = (virtualCursor.width / 2f).takeIf { it > 0 } ?: 14f
        val halfH = (virtualCursor.height / 2f).takeIf { it > 0 } ?: 14f

        if (isMouseMode) {
            val w = if (webView.width > 0) webView.width else 1920
            val h = if (webView.height > 0) webView.height else 1080
            cursorX = (w / 2f) - halfW
            cursorY = (h / 2f) - halfH
            updateCursorPosition()

            virtualCursor.visibility = View.VISIBLE
            webView.evaluateJavascript("window.tvClearFocus()", null)
        } else {
            virtualCursor.visibility = View.GONE
            val targetX = cursorX + halfW
            val targetY = cursorY + halfH
            webView.evaluateJavascript("window.tvFocusNear($targetX, $targetY)", null)
        }

        // Mostra OSD centrale stile Mac Caps Lock
        showModeOsd(isMouseMode)
    }

    // Mostra OSD centrale stile Mac Caps Lock
    private fun showModeOsd(isMouse: Boolean) {
        osdHandler.removeCallbacks(hideOsdRunnable)
        ivOsdIcon.setImageResource(if (isMouse) R.drawable.ic_osd_mouse else R.drawable.ic_osd_controller)

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

        // Svanisce dopo 1.1 secondi
        osdHandler.postDelayed(hideOsdRunnable, 1100)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = if (webView.width > 0) webView.width.toFloat() else 1920f
        val h = if (webView.height > 0) webView.height.toFloat() else 1080f
        val cursorDim = virtualCursor.width.toFloat().takeIf { it > 0 } ?: 28f

        cursorX = (cursorX + dx).coerceIn(0f, w - cursorDim)
        cursorY = (cursorY + dy).coerceIn(0f, h - cursorDim)
        updateCursorPosition()

        // Scorrimento se il cursore raggiunge i bordi
        if (cursorY >= h - 65) {
            webView.scrollBy(0, 70)
        } else if (cursorY <= 65) {
            webView.scrollBy(0, -70)
        }
        if (cursorX >= w - 65) {
            webView.scrollBy(70, 0)
        } else if (cursorX <= 65) {
            webView.scrollBy(-70, 0)
        }
    }

    private fun updateCursorPosition() {
        virtualCursor.x = cursorX
        virtualCursor.y = cursorY
    }

    private fun clickVirtualCursor() {
        // Animazione pulsazione / contrazione stile Silk Browser
        virtualCursor.animate()
            .scaleX(0.72f)
            .scaleY(0.72f)
            .setDuration(60)
            .withEndAction {
                virtualCursor.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
            }
            .start()

        val halfW = (virtualCursor.width / 2f).takeIf { it > 0 } ?: 14f
        val halfH = (virtualCursor.height / 2f).takeIf { it > 0 } ?: 14f
        val clickX = cursorX + halfW
        val clickY = cursorY + halfH

        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, clickX, clickY, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, clickX, clickY, 0)
        webView.dispatchTouchEvent(down)
        webView.postDelayed({
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }, 30)
    }
}
