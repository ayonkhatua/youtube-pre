package com.example

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())

    // A runnable that continuously injects play state tracking & background playback scripts.
    // This is vital since YouTube is a Single-Page App (SPA) and loads video elements dynamically.
    private val scriptRunnable = object : Runnable {
        override fun run() {
            injectPlayerHooks()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        setupBackPressed()

        // Sync with the Foreground Service.
        // When the user presses play/pause inside the notification or on the Origin Island / lock screen,
        // it triggers this callback, allowing us to directly command the web video.
        MediaPlaybackService.webViewActionCallback = { action ->
            runOnUiThread {
                when (action) {
                    MediaPlaybackService.ACTION_PLAY -> {
                        Log.d(TAG, "Sync action: Play video via JS")
                        webView.evaluateJavascript("document.querySelector('video')?.play();", null)
                    }
                    MediaPlaybackService.ACTION_PAUSE -> {
                        Log.d(TAG, "Sync action: Pause video via JS")
                        webView.evaluateJavascript("document.querySelector('video')?.pause();", null)
                    }
                }
            }
        }

        // Start periodic script injection loop
        handler.post(scriptRunnable)
    }

    private fun setupWebView() {
        val settings = webView.settings

        // 1. Desktop User-Agent to load desktop style layout which bypasses mobile restricts
        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        settings.userAgentString = desktopUserAgent

        // 2. Enable JavaScript & core web features
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // 3. Disable user gesture requirements for media playback
        settings.mediaPlaybackRequiresUserGesture = false

        // Allow debugging WebView contents in debug modes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 4. Set up JavaScript Interface for communication
        webView.addJavascriptInterface(PlayerJavaScriptInterface(this), "AndroidPlayer")

        // 5. Custom WebViewClient for Ad-Blocking
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Filter ad-serving domains or video statistics channels that push banner/popup ads
                val isAdRequest = url.contains("doubleclick.net") ||
                        url.contains("googleads") ||
                        url.contains("googleadservices.com") ||
                        url.contains("googlesyndication.com") ||
                        url.contains("youtube.com/api/stats/ads") ||
                        url.contains("youtube.com/pagead") ||
                        url.contains("youtube.com/ptracking") ||
                        url.contains("/pagead/") ||
                        url.contains("adservice.google.com")

                if (isAdRequest) {
                    Log.d("AdBlocker", "Blocked advertising resource request: $url")
                    // Intercept and return an empty resource response
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPlayerHooks()
            }
        }

        // 6. Custom WebChromeClient for Full-Screen Video & Horizontal Premium Red Progress Loader
        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback

                // Hide standard content container and show the full-screen view
                webView.visibility = View.GONE

                val decor = window.decorView as android.view.ViewGroup
                decor.addView(customView, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                ))

                hideSystemUI()
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
                if (customView == null) return

                val decor = window.decorView as android.view.ViewGroup
                decor.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                webView.visibility = View.VISIBLE
                showSystemUI()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                return super.onConsoleMessage(consoleMessage)
            }
        }

        // Load mobile YouTube but with the desktop UA, which works cleanly
        webView.loadUrl("https://m.youtube.com")
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Injects JavaScript into the page:
     * 1. Bypasses the HTML5 visibility API so background play is never paused when the screen shuts off.
     * 2. Bypasses YouTube's listener for hidden/blur events.
     * 3. Listens to play/pause/duration changes on the video element and forwards them to Kotlin.
     */
    private fun injectPlayerHooks() {
        val script = """
            (function() {
                // 1. Override the Page Visibility API so WebView never thinks it's in background
                if (document.visibilityState !== 'visible' || document.hidden !== false) {
                    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false, configurable: true });
                    Object.defineProperty(document, 'hidden', { value: false, writable: false, configurable: true });
                    document.dispatchEvent(new Event('visibilitychange'));
                    document.dispatchEvent(new Event('webkitvisibilitychange'));
                }

                // 2. Suppress the visibility event listener installation
                if (!window.visibilityHooked) {
                    window.visibilityHooked = true;
                    document.addEventListener = (function(oldAdd) {
                        return function(type, listener, options) {
                            if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                                return;
                            }
                            oldAdd.call(this, type, listener, options);
                        };
                    })(document.addEventListener);
                }

                // 3. Find active video tag and attach callbacks for synchronization
                var video = document.querySelector('video');
                if (video) {
                    if (!video.dataset.hooked) {
                        video.dataset.hooked = "true";

                        function updateState() {
                            var title = "";
                            var titleEl = document.querySelector('.slim-video-information-title-and-badges h1') || 
                                          document.querySelector('.video-title') || 
                                          document.querySelector('h1.title') || 
                                          document.querySelector('h1.ytd-watch-metadata') ||
                                          document.title;
                            if (titleEl) {
                                title = titleEl.innerText || titleEl.textContent;
                            }
                            if (!title) {
                                title = document.title.replace(" - YouTube", "");
                            }

                            // Deliver stats to android interface
                            AndroidPlayer.onVideoStateChanged(
                                title.trim(),
                                !video.paused,
                                Math.round((video.duration || 0) * 1000),
                                Math.round((video.currentTime || 0) * 1000)
                            );
                        }

                        video.addEventListener('play', updateState);
                        video.addEventListener('pause', updateState);
                        video.addEventListener('durationchange', updateState);
                        
                        // Periodic updates on video position (throttled)
                        video.addEventListener('timeupdate', function() {
                            if (Math.round(video.currentTime) % 3 === 0) {
                                updateState();
                            }
                        });

                        updateState();
                    }
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Triggers Picture-in-Picture (PiP) when user minimizes or navigates home.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPictureInPicture()
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Hide progress bar in PiP view
            progressBar.visibility = View.GONE
        } else {
            // Resume progress bar layout normal display if required
            progressBar.visibility = if (webView.progress < 100) View.VISIBLE else View.GONE
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scriptRunnable)
        MediaPlaybackService.webViewActionCallback = null
        if (MediaPlaybackService.isServiceRunning) {
            MediaPlaybackService.stopService(this)
        }
        webView.destroy()
    }

    /**
     * Native JavaScript Interface bound inside the WebView scope as "AndroidPlayer".
     */
    inner class PlayerJavaScriptInterface(private val context: Context) {
        @JavascriptInterface
        fun onVideoStateChanged(title: String, isPlaying: Boolean, durationMs: Long, positionMs: Long) {
            Log.d(TAG, "JS Interface state change: Title='$title', isPlaying=$isPlaying, duration=$durationMs, position=$positionMs")
            
            val action = if (isPlaying) MediaPlaybackService.ACTION_PLAY else MediaPlaybackService.ACTION_PAUSE
            MediaPlaybackService.startService(
                context,
                MediaPlaybackService.ACTION_UPDATE_STATE,
                title = title,
                isPlaying = isPlaying,
                duration = durationMs,
                position = positionMs
            )
        }
    }
}
