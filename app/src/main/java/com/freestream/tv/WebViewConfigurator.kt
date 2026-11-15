package com.freestream.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

class WebViewConfigurator(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPageStarted()
        fun onPageFinished(url: String?, mainFrameError: Boolean)
        fun onMainFrameError()
        fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?)
        fun onHideCustomView()
        fun onInputFocusChanged(focused: Boolean)
        fun onNativeTap(cssX: Float, cssY: Float, cssViewportWidth: Float)
        fun onExternalLinkBlocked()
        fun onRenderProcessGone(crashedWebView: WebView)
    }

    private val adblockJs = readAssetFile(context, "adblock.js").orEmpty()
    private val playerAgentJs = readAssetFile(context, "player_agent.js").orEmpty()
    private val topFrameJs: String = run {
        val css = readAssetFile(context, "tv_styles.css").orEmpty()
        val nav = readAssetFile(context, "tv_navigation.js").orEmpty()
        "if (window.top === window) {\nwindow.__fsCss = ${JSONObject.quote(css)};\n$nav\n}"
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun configure(webView: WebView, isTv: Boolean) {
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
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = UserAgentProvider.getUserAgent(webView.context, isTv)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Niente setLayerType(HARDWARE): su Fire TV il video è su una superficie separata dietro al WebView
        // e un layer offscreen ne impedisce la composizione (audio presente ma schermo nero).
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "AndroidBridge",
                setOf("*")
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                if (sourceOrigin.scheme != "https" || !DomainResolver.isOfficialHost(sourceOrigin.host)) {
                    return@addWebMessageListener
                }
                handleBridgeMessage(message.data)
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(webView), "AndroidBridge")
        }

        // Iniezione a document-start in TUTTI i frame: adblock prima degli script pubblicitari,
        // agente player anche nell'iframe cross-origin di JWPlayer, navigazione TV senza attendere onPageFinished
        var docStartScripts = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                val allOrigins = setOf("*")
                WebViewCompat.addDocumentStartJavaScript(webView, adblockJs, allOrigins)
                WebViewCompat.addDocumentStartJavaScript(webView, playerAgentJs, allOrigins)
                WebViewCompat.addDocumentStartJavaScript(webView, topFrameJs, allOrigins)
                docStartScripts = true
            } catch (_: Exception) {
                docStartScripts = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Blocca nativamente l'apertura di nuove finestre/tab popup anche da iframe cross-origin
                return false
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                callbacks.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                callbacks.onHideCustomView()
            }
        }

        var mainFrameError = false
        var hasLoadedSite = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameError = false
                callbacks.onPageStarted()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!mainFrameError && !docStartScripts) {
                    webView.evaluateJavascript(adblockJs, null)
                    webView.evaluateJavascript(playerAgentJs, null)
                    webView.evaluateJavascript(topFrameJs, null)
                }
                if (!mainFrameError && url != null) {
                    val host = runCatching { URI(url).host }.getOrNull()
                    if (DomainResolver.isOfficialHost(host)) {
                        hasLoadedSite = true
                    }
                }
                callbacks.onPageFinished(url, mainFrameError)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val isMainFrame = request?.isForMainFrame == true
                val allowed = AdBlocker.shouldAllowNavigation(request?.url?.toString(), isMainFrame)
                if (!allowed && isMainFrame && request?.hasGesture() == true) {
                    callbacks.onExternalLinkBlocked()
                }
                return !allowed
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                if (AdBlocker.isAdHost(host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                if (error?.errorCode == ERROR_UNSUPPORTED_SCHEME) return
                mainFrameError = true
                callbacks.onMainFrameError()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val status = errorResponse?.statusCode ?: 0
                if (AdBlocker.isFatalMainFrameHttpError(status, request?.isForMainFrame == true, hasLoadedSite)) {
                    mainFrameError = true
                    callbacks.onMainFrameError()
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.w("WebViewConfigurator", "Render process gone (didCrash=${detail?.didCrash()})")
                }
                if (view != null) {
                    callbacks.onRenderProcessGone(view)
                }
                return true
            }
        }
    }

    private fun handleBridgeMessage(raw: String?) {
        val data = raw?.takeIf { it.isNotBlank() } ?: return
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            return
        }
        when (json.optString("type")) {
            "inputFocus" -> callbacks.onInputFocusChanged(json.optBoolean("focused", false))
            "tapAt" -> {
                val x = json.optDouble("cssX", Double.NaN).toFloat()
                val y = json.optDouble("cssY", Double.NaN).toFloat()
                val vw = json.optDouble("cssViewportWidth", Double.NaN).toFloat()
                if (x.isFinite() && y.isFinite() && vw.isFinite() && x >= 0f && y >= 0f && vw > 0f) {
                    callbacks.onNativeTap(x, y, vw)
                }
            }
        }
    }

    private inner class LegacyBridge(private val webView: WebView) {
        private fun isMainFrameTrusted(): Boolean {
            val currentUrl = webView.url ?: return false
            val uri = try {
                URI(currentUrl)
            } catch (_: Exception) {
                return false
            }
            return uri.scheme?.lowercase() == "https" && DomainResolver.isOfficialHost(uri.host)
        }

        @JavascriptInterface
        fun onInputFocus(focused: Boolean) {
            webView.post {
                if (isMainFrameTrusted()) {
                    callbacks.onInputFocusChanged(focused)
                }
            }
        }

        @JavascriptInterface
        fun tapAt(cssX: Float, cssY: Float, cssViewportWidth: Float) {
            webView.post {
                if (isMainFrameTrusted() &&
                    cssX.isFinite() && cssY.isFinite() && cssViewportWidth.isFinite() &&
                    cssX >= 0f && cssY >= 0f && cssViewportWidth > 0f
                ) {
                    callbacks.onNativeTap(cssX, cssY, cssViewportWidth)
                }
            }
        }
    }

    private fun readAssetFile(context: Context, fileName: String): String? {
        return try {
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
