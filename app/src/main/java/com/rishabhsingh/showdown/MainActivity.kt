package com.rishabhsingh.showdown

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var errorView: View

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackPress = 0L
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge, immersive fullscreen, screen stays on while playing.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        drawIntoCutout()
        enterImmersive()

        // Volume keys control game audio (media stream).
        volumeControlStream = AudioManager.STREAM_MUSIC

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        errorView = findViewById(R.id.errorView)
        findViewById<Button>(R.id.retryButton).setOnClickListener { retry() }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        // Credit toast every time the game is opened.
        Toast.makeText(this, R.string.credit_toast, Toast.LENGTH_LONG).show()

        webView.loadUrl(GAME_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.apply {
            // GPU-accelerated rendering (canvas / WebGL / CSS animations).
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.game_bg))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true } // no text-selection / context menu while playing
            requestFocus()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // allow game / trailer audio
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            textZoom = 100 // system font size must not break the game layout
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Present as regular Chrome so the site's "open in browser" checks for
            // in-app WebViews don't fire.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        }

        // Don't send the X-Requested-With header (it reveals the app package to the server).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                Log.d(TAG, "nav: $uri tap=${request.hasGesture()} redirect=${request.isRedirect}")
                return routeNavigation(view, uri, request.hasGesture(), request.isRedirect)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!loadFailed) errorView.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    loadFailed = true
                    errorView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Supports the HTML5 Fullscreen API if the game / trailer uses it.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() = hideCustomView()

            // The game needs no camera / microphone.
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            // Avoid the grey default play-icon poster on videos.
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == HOST_SUFFIX || host.endsWith(".$HOST_SUFFIX")
    }

    /** Returns true if the navigation was handled here (the WebView must not load it). */
    private fun routeNavigation(
        view: WebView,
        uri: Uri,
        userTap: Boolean,
        redirect: Boolean
    ): Boolean {
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (isInternal(uri)) return false
                // A real tap on an outside link (Instagram, WhatsApp share, ...) opens its app.
                // Anything the page or server does by itself (redirects) stays inside the game.
                if (userTap && !redirect) {
                    openExternally(uri)
                    return true
                }
                return false
            }
            "about", "blob", "data", "javascript" -> return false
            // Chrome-style "open in browser" escape links: keep the page inside the app.
            "intent" -> {
                loadInApp(view, intentFallbackUrl(uri.toString()))
                return true
            }
            "googlechrome", "googlechromes" -> {
                val target = uri.getQueryParameter("url")
                    ?: ("https://" + uri.schemeSpecificPart.trimStart('/'))
                loadInApp(view, target)
                return true
            }
            else -> { // mailto:, tel:, whatsapp:, upi:, ...
                openExternally(uri)
                return true
            }
        }
    }

    private fun intentFallbackUrl(raw: String): String? = try {
        val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url") ?: intent.dataString
    } catch (_: Exception) {
        null
    }

    private fun loadInApp(view: WebView, target: String?) {
        Log.d(TAG, "keeping in app: $target")
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) return
        // Guard against reload loops if the page keeps re-triggering the redirect.
        if (target.trimEnd('/') == view.url?.trimEnd('/')) return
        view.loadUrl(target)
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        } catch (_: Exception) {
            // No app can handle this link; ignore.
        }
    }

    private fun retry() {
        loadFailed = false
        errorView.visibility = View.GONE
        webView.loadUrl(GAME_URL)
    }

    private fun hideCustomView() {
        val view = customView ?: return
        val callback = customViewCallback
        customView = null
        customViewCallback = null
        root.removeView(view)
        webView.visibility = View.VISIBLE
        callback?.onCustomViewHidden()
    }

    private fun handleBack() {
        if (customView != null) {
            hideCustomView()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPress < 2000) {
            finish()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show()
        }
    }

    // Let the game draw under the notch / punch-hole in landscape.
    private fun drawIntoCutout() {
        val mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else -> return
        }
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
    }

    private fun enterImmersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // Pause game rendering and audio when the app leaves the screen.
    override fun onStart() {
        super.onStart()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onStop() {
        webView.onPause()
        webView.pauseTimers()
        super.onStop()
    }

    override fun onDestroy() {
        root.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val GAME_URL = "https://takkar.shivambuilds.app/"
        private const val HOST_SUFFIX = "shivambuilds.app"
        private const val TAG = "ShowdownNav"
    }
}
