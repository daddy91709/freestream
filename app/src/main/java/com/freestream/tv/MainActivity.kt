package com.freestream.tv

import android.annotation.SuppressLint
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
import android.webkit.CookieManager
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
    private lateinit var tvModeHud: TextView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var activeDomain: String? = null
    private var lastBackPressTime = 0L

    // Cursore virtuale per telecomando TV
    private var isMouseMode = false
    private var cursorX = 640f
    private var cursorY = 360f
    private val cursorSpeed = 35f

    // Gestione pressione prolungata OK / CENTER
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var okLongPressTriggered = false
    private val LONG_PRESS_THRESHOLD = 600L // millisecondi per attivare la modalità cursore

    private val longPressRunnable = Runnable {
        okLongPressTriggered = true
        toggleMouseMode()
    }

    private val hudHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable {
        tvModeHud.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction { tvModeHud.visibility = View.GONE }
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schermo intero e mantieni display sempre acceso durante la visione
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
        tvModeHud = findViewById(R.id.tvModeHud)

        btnRetry.setOnClickListener {
            startDomainDiscoveryAndLoad()
        }

        setupWebView()
        startDomainDiscoveryAndLoad()
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

        // User-Agent Desktop moderno
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
                // Consenti solo navigazioni sul dominio del servizio
                return if (url.contains("streamingcommunity") || (activeDomain != null && url.contains(activeDomain!!))) {
                    false
                } else {
                    // Blocca popup e reindirizzamenti esterni indesiderati
                    true
                }
            }
        }
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

    // Iniezione file CSS e JS dagli asset
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

    // Intercettazione e routing di tutti i tasti del telecomando TV
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

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
                // Tasti fisici Menu o Info per telecomandi che ne sono provvisti
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
                    toggleMouseMode() // Esce subito dalla modalità puntatore
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

        // 3. Tasto OK / Invio (DPAD_CENTER o ENTER): Pressione breve = Click, Pressione lunga (>600ms) = Cursore
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
                // Pressione breve normale
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
            // Consuma l'evento sia su ACTION_DOWN che su ACTION_UP per evitare conflitti col motore nativo di WebView
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

    // Modalità Puntatore Virtuale
    private fun toggleMouseMode() {
        isMouseMode = !isMouseMode
        hudHandler.removeCallbacks(hideHudRunnable)

        if (isMouseMode) {
            // Posiziona il cursore al centro dello schermo
            val w = if (webView.width > 0) webView.width else 1920
            val h = if (webView.height > 0) webView.height else 1080
            cursorX = w / 2f
            cursorY = h / 2f
            updateCursorPosition()

            virtualCursor.visibility = View.VISIBLE
            webView.evaluateJavascript("window.tvClearFocus()", null)

            // Mostra badge persistente in modalità puntatore
            tvModeHud.text = "🖱️ Cursore Attivo (Premi INDIETRO o tieni premuto OK per uscire)"
            tvModeHud.alpha = 1f
            tvModeHud.visibility = View.VISIBLE
        } else {
            virtualCursor.visibility = View.GONE

            // Ripristina il focus telecomando vicino alle ultime coordinate del cursore
            webView.evaluateJavascript("window.tvFocusNear($cursorX, $cursorY)", null)

            // Notifica temporanea ritorno al telecomando
            tvModeHud.text = "🎮 Telecomando Attivo (Tieni premuto OK per puntatore)"
            tvModeHud.alpha = 1f
            tvModeHud.visibility = View.VISIBLE
            hudHandler.postDelayed(hideHudRunnable, 2500)
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = if (webView.width > 0) webView.width.toFloat() else 1920f
        val h = if (webView.height > 0) webView.height.toFloat() else 1080f
        cursorX = (cursorX + dx).coerceIn(10f, w - 10f)
        cursorY = (cursorY + dy).coerceIn(10f, h - 10f)
        updateCursorPosition()

        // Scorrimento pagina se il cursore raggiunge i bordi
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
        // Feedback visivo immediato di click
        virtualCursor.animate()
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(70)
            .withEndAction {
                virtualCursor.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
            }
            .start()

        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(down)
        webView.postDelayed({
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }, 30)
    }
}
