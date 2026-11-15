package com.freestream.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.freestream.tv.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), WebViewConfigurator.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private lateinit var keyboardBridge: SoftKeyboardBridge
    private lateinit var cursorController: VirtualCursorController
    private lateinit var remoteKeyHandler: RemoteKeyHandler
    private lateinit var webViewConfigurator: WebViewConfigurator

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var activeDomain: String? = null
    private var discoveryJob: Job? = null
    private var isTv = false
    private var networkCallbackRegistered = false

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (binding.errorLayout.visibility == View.VISIBLE && discoveryJob?.isActive != true) {
                    startDomainDiscoveryAndLoad()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isTv = isTvDevice()
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersiveFullscreen()

        webView = binding.webView

        keyboardBridge = SoftKeyboardBridge(window) { webView }
        cursorController = VirtualCursorController(
            virtualCursor = binding.virtualCursor,
            osdHudContainer = binding.osdHudContainer,
            ivOsdIcon = binding.ivOsdIcon,
            webViewProvider = { webView },
            evalJs = ::js
        )
        remoteKeyHandler = RemoteKeyHandler(
            webViewProvider = { webView },
            keyboardBridge = keyboardBridge,
            cursorController = cursorController,
            isOverlayBlockingNavigation = {
                binding.errorLayout.visibility == View.VISIBLE || binding.loadingLayout.visibility == View.VISIBLE
            },
            isCustomViewActive = { customView != null },
            hideCustomView = { onHideCustomView() },
            evalJs = ::js,
            evalJsWithResult = { code, cb -> webView.evaluateJavascript(code) { cb(it) } },
            onExitConfirmed = { finish() },
            showBackExitToast = {
                Toast.makeText(this, getString(R.string.press_back_again), Toast.LENGTH_SHORT).show()
            }
        )
        webViewConfigurator = WebViewConfigurator(applicationContext, this)

        binding.btnRetry.setOnClickListener {
            startDomainDiscoveryAndLoad()
        }

        webViewConfigurator.configure(webView, isTv)

        if (savedInstanceState != null) {
            activeDomain = savedInstanceState.getString(STATE_ACTIVE_DOMAIN)
            val webState = savedInstanceState.getBundle(STATE_WEBVIEW)
            if (webState != null && webView.restoreState(webState) != null) {
                binding.loadingLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                binding.errorLayout.visibility = View.GONE
                return
            }
        }

        startDomainDiscoveryAndLoad()
    }

    override fun onStart() {
        super.onStart()
        registerNetworkObserver()
    }

    override fun onStop() {
        unregisterNetworkObserver()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        // Evita che l'audio continui quando si torna alla Home della Fire TV
        webView.evaluateJavascript("window.fsPlayer && window.fsPlayer('pause')", null)
        webView.onPause()
        CookieManager.getInstance().flush()
        cursorController.releaseCursorKeys()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ACTIVE_DOMAIN, activeDomain)
        val webState = Bundle()
        webView.saveState(webState)
        outState.putBundle(STATE_WEBVIEW, webState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onDestroy() {
        discoveryJob?.cancel()
        keyboardBridge.destroy()
        cursorController.destroy()
        remoteKeyHandler.destroy()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return remoteKeyHandler.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- WebViewConfigurator.Callbacks

    override fun onPageStarted() {
        keyboardBridge.resetIfFocused()
    }

    override fun onPageFinished(url: String?, mainFrameError: Boolean) {
        if (mainFrameError) return
        // Il sito può reindirizzare a un nuovo dominio: lo ricordiamo per il prossimo avvio
        DomainResolver.rememberIfSite(applicationContext, url)
        binding.loadingLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        if (!webView.hasFocus()) webView.requestFocus()

        if (cursorController.isMouseMode) {
            js("window.tvClearFocus && window.tvClearFocus()")
        }
    }

    override fun onMainFrameError() {
        showErrorState()
    }

    override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (customView != null) {
            onHideCustomView()
            return
        }
        customView = view
        customViewCallback = callback
        binding.customViewContainer.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.customViewContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    override fun onHideCustomView() {
        if (customView == null) return
        binding.customViewContainer.visibility = View.GONE
        binding.customViewContainer.removeView(customView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        webView.visibility = View.VISIBLE
    }

    override fun onInputFocusChanged(focused: Boolean) {
        keyboardBridge.onInputFocusChanged(focused)
    }

    override fun onNativeTap(cssX: Float, cssY: Float, cssViewportWidth: Float) {
        val factor = if (cssViewportWidth > 0f && webView.width > 0) {
            webView.width / cssViewportWidth
        } else {
            resources.displayMetrics.density
        }
        cursorController.dispatchTap(cssX * factor, cssY * factor)
    }

    override fun onExternalLinkBlocked() {
        Toast.makeText(this, getString(R.string.external_link_blocked), Toast.LENGTH_SHORT).show()
    }

    override fun onRenderProcessGone(crashedWebView: WebView) {
        recreateWebView(crashedWebView)
    }

    // ---------------------------------------------------------------- Helpers

    private fun startDomainDiscoveryAndLoad() {
        discoveryJob?.cancel()
        binding.errorLayout.visibility = View.GONE
        binding.loadingLayout.visibility = View.VISIBLE
        binding.tvLoadingStatus.text = getString(R.string.loading_message)

        discoveryJob = lifecycleScope.launch {
            try {
                val domain = DomainResolver.resolveActiveDomain(applicationContext)
                activeDomain = domain
                binding.tvLoadingStatus.text = getString(R.string.connecting_message)
                webView.loadUrl(domain)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showErrorState()
            }
        }
    }

    private fun showErrorState() {
        binding.loadingLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.btnRetry.requestFocus()
    }

    private fun recreateWebView(crashedWebView: WebView) {
        if (customView != null) {
            customView = null
            customViewCallback = null
            binding.customViewContainer.removeAllViews()
            binding.customViewContainer.visibility = View.GONE
        }
        keyboardBridge.resetIfFocused()

        val parent = crashedWebView.parent as? ViewGroup ?: binding.root
        val index = parent.indexOfChild(crashedWebView).coerceAtLeast(0)
        val params = crashedWebView.layoutParams
        parent.removeView(crashedWebView)
        crashedWebView.destroy()

        val fresh = WebView(this).apply {
            id = R.id.webView
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE
        }
        parent.addView(fresh, index, params)
        webView = fresh
        webViewConfigurator.configure(fresh, isTv)

        val targetUrl = activeDomain
        if (targetUrl != null) {
            binding.loadingLayout.visibility = View.VISIBLE
            binding.errorLayout.visibility = View.GONE
            fresh.loadUrl(targetUrl)
        } else {
            startDomainDiscoveryAndLoad()
        }
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun registerNetworkObserver() {
        if (networkCallbackRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkObserver() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        return false
    }

    private fun js(code: String) {
        webView.evaluateJavascript(code, null)
    }

    companion object {
        private const val STATE_ACTIVE_DOMAIN = "active_domain"
        private const val STATE_WEBVIEW = "webview_state"
    }
}
