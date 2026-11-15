package com.freestream.tv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
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

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var activeDomain: String? = null
    private var lastBackPressTime = 0L

    // Stato cursore virtuale (per telecomando TV)
    private var isMouseMode = false
    private var cursorX = 640f
    private var cursorY = 360f
    private val cursorSpeed = 30f

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
        settings.mediaPlaybackRequiresUserGesture = false // Consente autoplay del video
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // User-Agent Desktop moderno per evitare blocchi Cloudflare e avere la griglia TV widescreen
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
                val currentBase = activeDomain ?: "streamingcommunity"

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

    // Gestione Telecomando e Tasti Multimediali
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Tasto Play/Pause fisico
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    toggleVideoPlayPause()
                    return true
                }

                // Avanzamento rapido
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    seekVideo(10)
                    return true
                }

                // Riavvolgimento
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    seekVideo(-10)
                    return true
                }

                // Tasto Menu o Info: Attiva/Disattiva Cursore Virtuale
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> {
                    toggleMouseMode()
                    return true
                }

                // Tasto Indietro
                KeyEvent.KEYCODE_BACK -> {
                    if (isMouseMode) {
                        toggleMouseMode()
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
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastBackPressTime < 2500) {
                        finish()
                    } else {
                        lastBackPressTime = currentTime
                        Toast.makeText(this, "Premi di nuovo per uscire da FreeStream", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }

            // Se la modalità mouse virtuale è attiva
            if (isMouseMode) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(0f, -cursorSpeed); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(0f, cursorSpeed); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-cursorSpeed, 0f); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(cursorSpeed, 0f); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> { clickVirtualCursor(); return true }
                }
            }
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

    // Modalità Mouse Virtuale
    private fun toggleMouseMode() {
        isMouseMode = !isMouseMode
        virtualCursor.visibility = if (isMouseMode) View.VISIBLE else View.GONE
        if (isMouseMode) {
            cursorX = webView.width / 2f
            cursorY = webView.height / 2f
            updateCursorPosition()
            Toast.makeText(this, "Modalità Mouse Attiva (Tasto Menu per uscire)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, (webView.width - 20).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (webView.height - 20).toFloat())
        updateCursorPosition()

        // Scroll automatico se il puntatore tocca i bordi dello schermo
        if (cursorY >= webView.height - 40) {
            webView.scrollBy(0, 50)
        } else if (cursorY <= 40) {
            webView.scrollBy(0, -50)
        }
    }

    private fun updateCursorPosition() {
        virtualCursor.x = cursorX
        virtualCursor.y = cursorY
    }

    private fun clickVirtualCursor() {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }
}
