package com.nexus.browser

import android.Manifest
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnDarkMode: ImageButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnDownload: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnIncognito: ImageButton
    private lateinit var incognitoBanner: View
    private lateinit var homeScreen: View

    // ── Helpers ──────────────────────────────────────────────────────────────
    private lateinit var bookmarksHelper: BookmarksHelper
    private lateinit var downloadHelper: DownloadHelper
    private lateinit var settingsHelper: SettingsHelper
    // BUG FIX: MultiThreadedDownloader added — pehle sirf DownloadManager tha
    private lateinit var multiDownloader: MultiThreadedDownloader

    // ── State ────────────────────────────────────────────────────────────────
    private var isDarkMode = false
    private var isIncognito = false
    private var currentDownloadUrl: String? = null
    private var currentVideoQuality: VideoQuality? = null
    
    // ── Picture-in-Picture (PiP) State ──────────────────────────────────────
    private var isFullScreenVideo = false
    private var fullScreenView: View? = null
    private var isPipModeActive = false

    // ── Video Sniffer ────────────────────────────────────────────────────────
    private lateinit var videoClient: VideoSnifferWebViewClient
    private lateinit var jsInterface: NexusJsInterface
    private val detectedVideos = mutableSetOf<String>()

    // ── Tab Management ───────────────────────────────────────────────────────
    data class TabInfo(val title: String, val url: String, val isHome: Boolean = false)
    private val tabList = mutableListOf<TabInfo>()
    private var activeTabIndex = 0

    // ── Download History ─────────────────────────────────────────────────────
    data class DownloadRecord(val fileName: String, val url: String, val timestamp: Long = System.currentTimeMillis())

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "NexusBrowser"
    }

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bookmarksHelper = BookmarksHelper(this)
        downloadHelper  = DownloadHelper(this)
        settingsHelper  = SettingsHelper(this)
        multiDownloader = MultiThreadedDownloader(this)  // BUG FIX: initialize karo

        initViews()
        setupWebView()
        setupListeners()
        requestPermissionsIfNeeded()
        loadSavedQualityPreference()
        restoreDarkModeState()
        // Initialize with home tab
        tabList.add(TabInfo("Home", "home", isHome = true))
        showHomeScreen()
    }

    override fun onPause()   { super.onPause();   webView.onPause() }
    override fun onResume()  { super.onResume();  webView.onResume() }
    override fun onDestroy() {
        if (::videoClient.isInitialized) videoClient.cleanup()
        webView.destroy()
        super.onDestroy()
    }
    
    // ── 🎬 LEGACY GESTURE SUPPORT (Android 8-11): Swipe-to-PiP ─────────────────────────
    /**
     * Called when user swipes away or uses home gesture (primarily Android 8-11).
     * On Android 12+, auto-enter already handles this, but we keep it for legacy support.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        // Only enter PiP if video is currently in full-screen
        if (isFullScreenVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "👆 User swiped away while in full-screen video")
            
            // Build PiP params with aspect ratio
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val videoAspectRatio = Rational(screenWidth, screenHeight)
            
            val pipBuilder = PictureInPictureParams.Builder()
                .setAspectRatio(videoAspectRatio)
            
            // On Android 12+, ensure auto-enter is set
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pipBuilder.setAutoEnterEnabled(true)
            }
            
            val pipParams = pipBuilder.build()
            
            try {
                enterPictureInPictureMode(pipParams)
                isPipModeActive = true
                Log.d(TAG, "✅ Entered PiP mode from gesture (Android ${Build.VERSION.SDK_INT})")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "⚠️ PiP not available: ${e.message}")
            }
        }
    }
    
    // ── 🎬 PiP MODE CHANGE HANDLER ─────────────────────────────────────────────────────
    /**
     * Called when entering or exiting Picture-in-Picture mode.
     * Synchronizes UI visibility and system settings.
     */
    override fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPipMode)
        isPipModeActive = isInPipMode
        
        if (isInPipMode) {
            Log.d(TAG, "📱 Entering PiP mode - hiding browser UI")
            // Hide ALL UI when entering PiP (only show video)
            hideUiForFullscreen()
        } else {
            Log.d(TAG, "📺 Exiting PiP mode - restoring browser UI")
            // Restore UI when exiting PiP
            restoreUiFromFullscreen()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 🎬 If in full-screen video, enter PiP instead of closing
        if (isFullScreenVideo) {
            Log.d(TAG, "← Back pressed during full-screen - transitioning to PiP")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val videoAspectRatio = Rational(screenWidth, screenHeight)
                
                val pipBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(videoAspectRatio)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    pipBuilder.setAutoEnterEnabled(true)
                }
                
                val pipParams = pipBuilder.build()
                
                try {
                    enterPictureInPictureMode(pipParams)
                    isPipModeActive = true
                    Log.d(TAG, "✅ PiP transition successful")
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ PiP transition failed: ${e.message}")
                }
            }
            return
        }
        
        when {
            webView.canGoBack() && swipeRefresh.visibility == View.VISIBLE -> {
                webView.goBack()
            }
            swipeRefresh.visibility == View.VISIBLE -> showHomeScreen()
            else -> super.onBackPressed()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // INIT
    // ════════════════════════════════════════════════════════════════════════

    private fun initViews() {
        webView          = findViewById(R.id.webView)
        urlBar           = findViewById(R.id.urlBar)
        progressBar      = findViewById(R.id.progressBar)
        swipeRefresh     = findViewById(R.id.swipeRefresh)
        btnBack          = findViewById(R.id.btnBack)
        btnForward       = findViewById(R.id.btnForward)
        btnRefresh       = findViewById(R.id.btnRefresh)
        btnDarkMode      = findViewById(R.id.btnDarkMode)
        btnBookmark      = findViewById(R.id.btnBookmark)
        btnDownload      = findViewById(R.id.btnDownload)
        btnSettings      = findViewById(R.id.btnSettings)
        btnIncognito     = findViewById(R.id.btnIncognito)
        incognitoBanner  = findViewById(R.id.incognitoBanner)
        homeScreen       = findViewById(R.id.homeScreen)
    }

    // ════════════════════════════════════════════════════════════════════════
    // WEBVIEW SETUP
    // ════════════════════════════════════════════════════════════════════════

    private fun setupWebView() {
        applyWebViewSettings(webView.settings)

        jsInterface = NexusJsInterface()
        jsInterface.onVideoDetected = { url ->
            runOnUiThread { handleDetectedVideo(VideoStream(url = url, extension = "", sourceType = "js_scrape")) }
        }
        webView.addJavascriptInterface(jsInterface, "NexusVideoScraper")

        videoClient = VideoSnifferWebViewClient(
            isDarkMode       = { isDarkMode },
            isIncognito      = { isIncognito },
            isAdBlockEnabled = { settingsHelper.isAdBlockEnabled() },
            onPageStartedCallback = { url ->
                showWebView()
                progressBar.visibility = View.VISIBLE
                progressBar.progress   = 0
                urlBar.setText(url ?: "")
                updateNavButtons()
                detectedVideos.clear()
                btnDownload.alpha = 0.6f
            },
            onPageFinishedCallback = { view, url ->
                progressBar.visibility    = View.GONE
                swipeRefresh.isRefreshing = false
                urlBar.setText(url ?: "")
                updateNavButtons()
                if (!isIncognito && url != null && url != "about:blank") {
                    bookmarksHelper.addToHistory(url, view?.title ?: url)
                    updateCurrentTab(url, view?.title ?: url)
                }
            },
            onErrorReceived = { url ->
                webView.loadDataWithBaseURL(null, buildErrorPage(url), "text/html", "UTF-8", null)
            },
            onVideoDetected = { stream ->
                runOnUiThread { handleDetectedVideo(stream) }
            }
        )
        webView.webViewClient = videoClient

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) {
                    swipeRefresh.isRefreshing = false
                    View.GONE
                } else View.VISIBLE
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (!isIncognito) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Alert").setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                } else result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Confirm").setMessage(message)
                    .setPositiveButton("OK")     { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .show()
                return true
            }

            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, msg: android.os.Message?) = false
            override fun onReceivedTitle(view: WebView?, title: String?) = Unit
            
            // ── 🎬 MX PLAYER-STYLE PiP SUPPORT ──────────────────────────────────────────────────
            /**
             * Handles when video goes full-screen.
             * 🎯 For Android 12+: Sets up PiP with auto-enter and proper aspect ratio
             * 🎯 For Android 8-11: Prepares params for manual PiP via onUserLeaveHint()
             */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                if (view == null) return
                
                isFullScreenVideo = true
                fullScreenView = view
                
                // ────────────────────────────────────────────────────────────────
                // STEP 1: Calculate video aspect ratio (width/height of screen in fullscreen)
                // ────────────────────────────────────────────────────────────────
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                
                // For best results with video players, use the actual viewport dimensions
                val videoAspectRatio = Rational(screenWidth, screenHeight)
                
                Log.d(TAG, "📺 Video Fullscreen: ${screenWidth}x${screenHeight} (AR: ${videoAspectRatio})")
                
                // ────────────────────────────────────────────────────────────────
                // STEP 2: Create PiP params with aspect ratio
                // ────────────────────────────────────────────────────────────────
                val pipBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(videoAspectRatio)
                
                // ────────────────────────────────────────────────────────────────
                // STEP 3: Enable auto-enter for Android 12+ (API 31+)
                // ────────────────────────────────────────────────────────────────
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    pipBuilder.setAutoEnterEnabled(true) // ✨ Buttery smooth auto-PiP
                    Log.d(TAG, "✨ Auto-enter PiP enabled (Android 12+)")
                }
                
                val pipParams = pipBuilder.build()
                
                // Apply params immediately (Android 12+) or later on user action (Android 8-11)
                try {
                    setPictureInPictureParams(pipParams)
                    Log.d(TAG, "🎬 PiP params applied successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to apply PiP params: ${e.message}")
                }
                
                // ────────────────────────────────────────────────────────────────
                // STEP 4: Hide browser UI (address bar, navigation, bottom menu)
                // ────────────────────────────────────────────────────────────────
                hideUiForFullscreen()
                
                // ────────────────────────────────────────────────────────────────
                // STEP 5: Add the custom view (video player) to webView hierarchy
                // ────────────────────────────────────────────────────────────────
                webView.addView(view, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                
                Log.d(TAG, "✅ Full-screen video initialized (PiP-ready)")
            }
            
            /**
             * Handles when video exits full-screen (user taps back or completes video)
             */
            override fun onHideCustomView() {
                super.onHideCustomView()
                if (!isFullScreenVideo) return
                
                isFullScreenVideo = false
                
                // Remove the custom view from webView
                if (fullScreenView != null) {
                    try {
                        webView.removeView(fullScreenView)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing fullscreen view: ${e.message}")
                    }
                    fullScreenView = null
                }
                
                // Restore browser UI
                restoreUiFromFullscreen()
                
                Log.d(TAG, "🎬 Full-screen video ended, UI restored")
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            currentDownloadUrl = url
            showDownloadDialog(url, contentDisposition, mimetype, contentLength, userAgent)
        }
    }

    private fun applyWebViewSettings(settings: WebSettings) {
        settings.javaScriptEnabled              = settingsHelper.isJavaScriptEnabled()
        settings.domStorageEnabled              = true
        settings.loadsImagesAutomatically       = settingsHelper.isImagesEnabled()
        settings.mixedContentMode               = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode                      = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        settings.allowFileAccess                = false
        settings.allowContentAccess             = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls            = true
        settings.displayZoomControls            = false
        settings.useWideViewPort                = true
        settings.loadWithOverviewMode           = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    // ════════════════════════════════════════════════════════════════════════
    // VIDEO DETECTION — handleDetectedVideo
    // ════════════════════════════════════════════════════════════════════════

    private fun handleDetectedVideo(stream: VideoStream) {
        if (detectedVideos.contains(stream.url)) return
        detectedVideos.add(stream.url)
        Log.d(TAG, "Video detected [${stream.sourceType}/${stream.extension}]: ${stream.url}")

        currentDownloadUrl = stream.url
        btnDownload.alpha = 1.0f

        if (detectedVideos.size == 1) {
            Toast.makeText(this, "Video detected (${stream.extension.uppercase()}) — tap ⬇️", Toast.LENGTH_SHORT).show()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DOWNLOAD DIALOG — BUG FIX: M3U8 path wired here
    // ════════════════════════════════════════════════════════════════════════

    /**
     * BUG FIX: showDownloadDialog() ab M3U8 aur regular URLs differently handle karta hai.
     *
     * Pehle M3U8 URLs bhi DownloadManager mein diye jaate the —
     * jo sirf .m3u8 playlist file download karta tha (video nahi!).
     *
     * Ab:
     *  - M3U8 URLs → M3u8Parser → Quality sheet → MultiThreadedDownloader
     *  - Regular URLs → DownloadManager (as before)
     */
    private fun showDownloadDialog(
        url: String,
        contentDisposition: String = "",
        mimeType: String = "application/octet-stream",
        contentLength: Long = 0,
        userAgent: String = webView.settings.userAgentString
    ) {
        if (downloadHelper.isM3u8Url(url)) {
            // M3U8 — quality choice dikhao
            showM3u8QualityDialog(url)
        } else {
            // Regular file
            val fileName = downloadHelper.getFileNameFromUrl(url, contentDisposition, mimeType)
            val sizeText = if (contentLength > 0) " (${downloadHelper.formatFileSize(contentLength)})" else ""
            AlertDialog.Builder(this)
                .setTitle("Download File")
                .setMessage("Save: $fileName$sizeText")
                .setPositiveButton("Download") { _, _ ->
                    if (checkStoragePermission()) {
                        recordDownload(url)
                        downloadHelper.startDownload(
                            url       = url,
                            fileName  = fileName,
                            mimeType  = mimeType,
                            userAgent = userAgent,
                            referer   = webView.url ?: url  // BUG FIX: page URL as Referer
                        )
                    } else {
                        requestStoragePermission()
                        currentDownloadUrl = url
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // M3U8 QUALITY SELECTION + DOWNLOAD — Full pipeline wired here
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M3U8 URL ke liye:
     * 1. Master playlist parse karo (coroutine)
     * 2. Quality bottom sheet dikhao
     * 3. User jo quality choose kare, woh download karo (MultiThreadedDownloader)
     * 4. Agar video+audio alag hain to MediaMuxerHelper se merge karo
     */
    private fun showM3u8QualityDialog(masterUrl: String) {
        // Progress dialog dikhao jab tak parse ho
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Analyzing Stream...")
            .setMessage("Fetching available qualities...")
            .setCancelable(true)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val qualities = M3u8Parser.parseMasterPlaylist(masterUrl)
            progressDialog.dismiss()

            if (qualities.isEmpty()) {
                // Single quality stream — seedha download karo
                Log.d(TAG, "No multiple qualities found, downloading directly")
                startM3u8Download(masterUrl, "360p", masterUrl)
                return@launch
            }

            // Quality selection bottom sheet
            runOnUiThread {
                showHlsQualityBottomSheet(qualities, masterUrl)
            }
        }
    }

    /**
     * HLS stream ke liye quality selection bottom sheet.
     * VideoQualityBottomSheet reuse karta hai — same UI, real data.
     */
    private fun showHlsQualityBottomSheet(
        hlsQualities: List<VideoQuality>,
        masterUrl: String
    ) {
        // Bottom sheet dialog
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.video_quality_sheet, null)
        dialog.setContentView(sheetView)

        val rvResolutions = sheetView.findViewById<RecyclerView>(R.id.rvResolutions)
        val tvCurrentQuality = sheetView.findViewById<TextView>(R.id.tvCurrentQuality)

        tvCurrentQuality.text = "Select quality to download (${hlsQualities.size} available)"

        rvResolutions.layoutManager = LinearLayoutManager(this)
        rvResolutions.adapter = VideoQualityAdapter(hlsQualities) { selectedQuality ->
            dialog.dismiss()
            Log.d(TAG, "User selected quality: ${selectedQuality.label} — ${selectedQuality.streamUrl}")
            startM3u8Download(
                variantUrl   = selectedQuality.streamUrl,
                qualityLabel = selectedQuality.label,
                masterUrl    = masterUrl
            )
        }

        dialog.show()
    }

    /**
     * M3U8 variant stream download karta hai MultiThreadedDownloader se.
     * Progress dialog ke saath.
     */
    private fun startM3u8Download(variantUrl: String, qualityLabel: String, masterUrl: String) {
        val outputFileName = "nexus_${System.currentTimeMillis()}_${qualityLabel}.ts"
        val outputPath = downloadHelper.getM3u8OutputPath(outputFileName)

        // Progress dialog — cancel button properly cancels the coroutine job
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading $qualityLabel")
            .setMessage("0%")
            .setCancelable(false)
            .create()
        progressDialog.show()

        var downloadJob: kotlinx.coroutines.Job? = null
        progressDialog.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
            downloadJob?.cancel()
            Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "M3U8 download cancelled by user")
        }

        downloadJob = lifecycleScope.launch {
            val result = multiDownloader.downloadM3u8Stream(
                variantUrl = variantUrl,
                outputPath = outputPath,
                onProgress = { percent ->
                    runOnUiThread {
                        progressDialog.setMessage("Downloading... $percent%")
                    }
                }
            )

            progressDialog.dismiss()

            when (result) {
                is MultiThreadedDownloader.DownloadResult.Success -> {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Downloaded: $outputFileName (${downloadHelper.formatFileSize(result.fileSizeBytes)})",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d(TAG, "Download success: ${result.filePath}")
                }
                is MultiThreadedDownloader.DownloadResult.Failure -> {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ Download failed: ${result.error}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "Download failed: ${result.error}")
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private fun showHomeScreen() {
        homeScreen.visibility   = View.VISIBLE
        swipeRefresh.visibility = View.GONE
        // Hide browser toolbar on home screen
        findViewById<View>(R.id.browserToolbar).visibility = View.GONE
        urlBar.setText("")
        urlBar.hint = "Search or type web address"
        updateNavButtons()
    }

    private fun showWebView() {
        homeScreen.visibility   = View.GONE
        swipeRefresh.visibility = View.VISIBLE
        // Show browser toolbar when browsing
        findViewById<View>(R.id.browserToolbar).visibility = View.VISIBLE
    }

    private fun updateCurrentTab(url: String, title: String) {
        if (tabList.isNotEmpty() && activeTabIndex < tabList.size) {
            tabList[activeTabIndex] = TabInfo(title.ifBlank { url }, url)
        } else {
            tabList.add(TabInfo(title.ifBlank { url }, url))
            activeTabIndex = tabList.size - 1
        }
    }

    private fun updateNavButtons() {
        btnBack.alpha    = if (webView.canGoBack() || swipeRefresh.visibility == View.VISIBLE) 1.0f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1.0f else 0.4f
    }

    // ════════════════════════════════════════════════════════════════════════
    // LISTENERS
    // ════════════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val query = urlBar.text.toString().trim()
                if (query.isNotEmpty()) { loadUrl(query); hideKeyboard() }
                true
            } else false
        }

        findViewById<View>(R.id.homeSearchBar).setOnClickListener {
            urlBar.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
        }

        findViewById<View>(R.id.shortcutGoogle).setOnClickListener   { loadUrl("https://www.google.com") }
        findViewById<View>(R.id.shortcutYoutube).setOnClickListener  { loadUrl("https://www.youtube.com") }
        findViewById<View>(R.id.shortcutFacebook).setOnClickListener { loadUrl("https://www.facebook.com") }
        findViewById<View>(R.id.shortcutWhatsapp).setOnClickListener { loadUrl("https://web.whatsapp.com") }
        findViewById<View>(R.id.shortcutMore).setOnClickListener     { showBookmarkOptions() }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
            else if (swipeRefresh.visibility == View.VISIBLE) showHomeScreen()
        }
        btnForward.setOnClickListener  { if (webView.canGoForward()) webView.goForward() }
        btnRefresh.setOnClickListener  { if (swipeRefresh.visibility == View.VISIBLE) webView.reload() }
        btnDarkMode.setOnClickListener { toggleDarkMode() }
        btnBookmark.setOnClickListener { showBookmarkOptions() }

        // BUG FIX: Download button ab M3U8 check karta hai
        btnDownload.setOnClickListener {
            val url = currentDownloadUrl ?: webView.url
            if (!url.isNullOrBlank() && url != "about:blank") {
                showDownloadDialog(url)
            } else {
                Toast.makeText(this, "No downloadable content found", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener  { showSettingsDialog() }
        btnIncognito.setOnClickListener { toggleIncognito() }

        // ── Bottom Nav click listeners ──────────────────────────────
        findViewById<View>(R.id.navHome).setOnClickListener    { showHomeScreen() }
        findViewById<View>(R.id.navFiles).setOnClickListener   { showDownloadsList() }
        findViewById<View>(R.id.navTabs).setOnClickListener    { showTabsSwitcher() }
        findViewById<View>(R.id.navProfile).setOnClickListener { showSettingsDialog() }

        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setColorSchemeResources(R.color.colorPrimary)
    }

    // ════════════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ════════════════════════════════════════════════════════════════════════

    private fun loadUrl(input: String) {
        val url = when {
            input.isBlank() -> return
            input == "home" -> { showHomeScreen(); return }
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> buildSearchUrl(input)
        }
        // If current active tab is home, update it; otherwise it will be updated on page finish
        if (tabList.isNotEmpty() && activeTabIndex < tabList.size && tabList[activeTabIndex].isHome) {
            tabList[activeTabIndex] = TabInfo(url, url, isHome = false)
        }
        showWebView()
        webView.loadUrl(url)
    }

    private fun buildSearchUrl(query: String): String = when (settingsHelper.getSearchEngine()) {
        "bing"       -> "https://www.bing.com/search?q=${Uri.encode(query)}"
        "duckduckgo" -> "https://duckduckgo.com/?q=${Uri.encode(query)}"
        else         -> "https://www.google.com/search?q=${Uri.encode(query)}"
    }

    // ════════════════════════════════════════════════════════════════════════
    // DARK MODE
    // ════════════════════════════════════════════════════════════════════════

    private fun toggleDarkMode() {
        isDarkMode = !isDarkMode
        settingsHelper.setDarkModeEnabled(isDarkMode)
        applyDarkMode(isDarkMode)
    }

    private fun applyDarkMode(enabled: Boolean) {
        btnDarkMode.alpha = if (enabled) 1.0f else 0.6f
        if (enabled) {
            webView.evaluateJavascript(darkModeJs(), null)
            Toast.makeText(this, "Dark Mode ON", Toast.LENGTH_SHORT).show()
        } else {
            webView.reload()
            Toast.makeText(this, "Dark Mode OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreDarkModeState() {
        isDarkMode = settingsHelper.isDarkModeEnabled()
        btnDarkMode.alpha = if (isDarkMode) 1.0f else 0.6f
        // Apply dark mode JS after page loads if enabled (handled in WebViewClient page load callback)
    }

    private fun darkModeJs() = """
        (function() {
            var s = document.getElementById('nexus-dark-mode');
            if (!s) { s = document.createElement('style'); s.id='nexus-dark-mode'; document.head.appendChild(s); }
            s.textContent = 'html,body,div,section,article,header,footer,main,nav{background-color:#1a1a1a!important;color:#e0e0e0!important}a{color:#7db8ff!important}input,textarea,select{background-color:#2a2a2a!important;color:#e0e0e0!important;border-color:#444!important}img{filter:brightness(0.85)}p,span,h1,h2,h3,h4,h5,h6,li,td,th,label{color:#e0e0e0!important}';
        })();
    """.trimIndent()

    // ════════════════════════════════════════════════════════════════════════
    // BOOKMARKS
    // ════════════════════════════════════════════════════════════════════════

    private fun showBookmarkOptions() {
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(arrayOf("Save Current Page", "View Bookmarks", "Clear History")) { _, which ->
                when (which) {
                    0 -> saveCurrentBookmark()
                    1 -> showBookmarksList()
                    2 -> clearHistory()
                }
            }.show()
    }

    private fun saveCurrentBookmark() {
        val url = webView.url ?: return
        bookmarksHelper.addBookmark(webView.title ?: url, url)
        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksList() {
        val bookmarks = bookmarksHelper.getBookmarks()
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks saved", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog   = BottomSheetDialog(this)
        val view     = layoutInflater.inflate(R.layout.bookmark_list_dialog, null)
        val rv       = view.findViewById<RecyclerView>(R.id.bookmarkRecyclerView)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseBookmarks)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = BookmarkAdapter(bookmarks,
            onItemClick   = { bm -> loadUrl(bm.url); dialog.dismiss() },
            onDeleteClick = { bm -> bookmarksHelper.removeBookmark(bm.url); dialog.dismiss(); showBookmarksList() }
        )
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun clearHistory() {
        bookmarksHelper.clearHistory()
        webView.clearHistory()
        Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // DOWNLOADS LIST (navFiles)
    // ════════════════════════════════════════════════════════════════════════

    // In-memory download log (cleared on app restart; non-persistent by design)
    private val downloadRecords = mutableListOf<DownloadRecord>()

    private fun recordDownload(url: String) {
        val fileName = url.substringAfterLast("/").substringBefore("?").take(60).ifBlank { "file" }
        downloadRecords.add(0, DownloadRecord(fileName, url))
        if (downloadRecords.size > 50) downloadRecords.removeAt(downloadRecords.size - 1)
    }

    private fun showDownloadsList() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view   = layoutInflater.inflate(R.layout.downloads_dialog, null)
        val rv         = view.findViewById<RecyclerView>(R.id.downloadsRecyclerView)
        val btnClose   = view.findViewById<android.widget.ImageButton>(R.id.btnCloseDownloads)
        val tvEmpty    = view.findViewById<android.widget.TextView>(R.id.tvDownloadsEmpty)

        if (downloadRecords.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility      = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility      = View.VISIBLE
            rv.layoutManager   = LinearLayoutManager(this)
            rv.adapter         = DownloadRecordAdapter(downloadRecords) { record ->
                // Open the URL in browser
                loadUrl(record.url)
                dialog.dismiss()
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // TABS SWITCHER (navTabs)
    // ════════════════════════════════════════════════════════════════════════

    private fun showTabsSwitcher() {
        val dialog  = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view    = layoutInflater.inflate(R.layout.tabs_dialog, null)
        val rv      = view.findViewById<RecyclerView>(R.id.tabsRecyclerView)
        val btnClose   = view.findViewById<android.widget.ImageButton>(R.id.btnCloseTabs)
        val btnNewTab  = view.findViewById<android.widget.Button>(R.id.btnNewTab)

        // Sync current tab title/url before showing
        val currentUrl   = if (swipeRefresh.visibility == View.VISIBLE) webView.url ?: "home" else "home"
        val currentTitle = if (swipeRefresh.visibility == View.VISIBLE) webView.title ?: currentUrl else "Home"
        if (tabList.isNotEmpty() && activeTabIndex < tabList.size) {
            tabList[activeTabIndex] = TabInfo(currentTitle, currentUrl, currentUrl == "home")
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter       = TabAdapter(
            tabs         = tabList,
            activeIndex  = activeTabIndex,
            onTabClick   = { idx ->
                val tab = tabList[idx]
                activeTabIndex = idx
                if (tab.isHome || tab.url == "home") {
                    showHomeScreen()
                } else {
                    loadUrl(tab.url)
                }
                dialog.dismiss()
            },
            onTabClose   = { idx ->
                if (tabList.size > 1) {
                    tabList.removeAt(idx)
                    if (activeTabIndex >= tabList.size) activeTabIndex = tabList.size - 1
                    dialog.dismiss()
                    // Re-open dialog with updated list
                    showTabsSwitcher()
                } else {
                    Toast.makeText(this, "Cannot close last tab", Toast.LENGTH_SHORT).show()
                }
            }
        )

        btnNewTab.setOnClickListener {
            tabList.add(TabInfo("Home", "home", isHome = true))
            activeTabIndex = tabList.size - 1
            showHomeScreen()
            dialog.dismiss()
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // INCOGNITO
    // ════════════════════════════════════════════════════════════════════════

    private fun toggleIncognito() {
        isIncognito = !isIncognito
        incognitoBanner.visibility = if (isIncognito) View.VISIBLE else View.GONE
        btnIncognito.alpha         = if (isIncognito) 1.0f else 0.6f
        webView.settings.cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        if (isIncognito) {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            CookieManager.getInstance().removeSessionCookies(null)
            Toast.makeText(this, "Incognito Mode ON", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Incognito Mode OFF", Toast.LENGTH_SHORT).show()
        }
        showHomeScreen()
    }

    // ════════════════════════════════════════════════════════════════════════
    // SETTINGS
    // ════════════════════════════════════════════════════════════════════════

    private fun showSettingsDialog() {
        val view         = layoutInflater.inflate(R.layout.settings_dialog, null)
        val switchJS     = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchJavaScript)
        val switchImages = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchImages)
        val switchAdBlock = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAdBlock)
        val spinner      = view.findViewById<Spinner>(R.id.spinnerSearchEngine)
        val btnClearCache   = view.findViewById<android.widget.Button>(R.id.btnClearCache)
        val btnClearHistory = view.findViewById<android.widget.Button>(R.id.btnClearHistory)

        switchJS.isChecked      = settingsHelper.isJavaScriptEnabled()
        switchImages.isChecked  = settingsHelper.isImagesEnabled()
        switchAdBlock.isChecked = settingsHelper.isAdBlockEnabled()

        val keys   = arrayOf("google", "bing", "duckduckgo")
        val labels = arrayOf("Google", "Bing", "DuckDuckGo")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(keys.indexOf(settingsHelper.getSearchEngine()).coerceAtLeast(0))

        switchJS.setOnCheckedChangeListener { _, v ->
            settingsHelper.setJavaScriptEnabled(v); webView.settings.javaScriptEnabled = v
        }
        switchImages.setOnCheckedChangeListener { _, v ->
            settingsHelper.setImagesEnabled(v); webView.settings.loadsImagesAutomatically = v
        }
        switchAdBlock.setOnCheckedChangeListener { _, v ->
            settingsHelper.setAdBlockEnabled(v)
            Toast.makeText(this, if (v) "Ad blocking enabled" else "Ad blocking disabled", Toast.LENGTH_SHORT).show()
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settingsHelper.setSearchEngine(keys[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnClearCache.setOnClickListener   { webView.clearCache(true); Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show() }
        btnClearHistory.setOnClickListener { clearHistory() }

        AlertDialog.Builder(this).setTitle("Settings").setView(view).setPositiveButton("Close", null).show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // VIDEO QUALITY PREFERENCE (WebView settings, not HLS)
    // ════════════════════════════════════════════════════════════════════════

    private fun loadSavedQualityPreference() {
        val savedId = getSharedPreferences("video_settings", Context.MODE_PRIVATE)
            .getString("preferred_quality", "auto") ?: "auto"
        currentVideoQuality = getQualityById(savedId)
        currentVideoQuality?.let { updateWebViewSettingsForQuality(it) }
    }

    private fun updateWebViewSettingsForQuality(quality: VideoQuality) {
        webView.settings.apply {
            javaScriptEnabled   = true
            builtInZoomControls = quality.id in listOf("720p", "1080p", "1440p", "2160p")
            displayZoomControls = false
        }
    }

    private fun getQualityById(id: String): VideoQuality? = listOf(
        VideoQuality("auto",  "Auto",           "Adaptive", "Adjust based on network",        "Variable",     isAutomatic = true),
        VideoQuality("360p",  "360p (SD)",       "640x360",  "Low bandwidth required",         "0.5-1 Mbps"),
        VideoQuality("480p",  "480p (SD)",       "854x480",  "Balanced quality",               "1-2 Mbps"),
        VideoQuality("720p",  "720p (HD)",       "1280x720", "Good quality, moderate data",    "2-3 Mbps"),
        VideoQuality("1080p", "1080p (Full HD)", "1920x1080","High quality, more data",        "4-5 Mbps"),
        VideoQuality("1440p", "1440p (QHD)",     "2560x1440","Very high quality",              "8-10 Mbps"),
        VideoQuality("2160p", "2160p (4K)",      "3840x2160","Ultra HD, highest data usage",   "15-25 Mbps")
    ).find { it.id == id }

    // ════════════════════════════════════════════════════════════════════════
    // MISC UTILS
    // ════════════════════════════════════════════════════════════════════════

    private fun buildErrorPage(url: String) = """
        <html><body style="background:#1a1a2e;color:#e0e0e0;font-family:sans-serif;text-align:center;padding:40px;">
            <h2 style="color:#ff6b6b;">Page Not Available</h2>
            <p>Could not load: <b>$url</b></p>
            <p>Check your internet connection and try again.</p>
            <button onclick="history.back()" style="background:#4a90d9;color:white;border:none;padding:10px 20px;border-radius:8px;cursor:pointer;">Go Back</button>
        </body></html>
    """.trimIndent()

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun checkStoragePermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) true
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
            currentDownloadUrl != null) {
            showDownloadDialog(currentDownloadUrl!!)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 🎬 PICTURE-IN-PICTURE UI HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Hide the browser UI when entering full-screen or PiP mode
     * Only the video player should be visible
     */
    private fun hideUiForFullscreen() {
        runOnUiThread {
            // Hide entire browser toolbar (urlBar is inside it, not a direct child)
            findViewById<View>(R.id.browserToolbar).visibility = View.GONE
            progressBar.visibility     = View.GONE
            incognitoBanner.visibility = View.GONE
            homeScreen.visibility      = View.GONE

            // Immersive fullscreen
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    /**
     * Restore the browser UI when exiting full-screen or PiP mode
     */
    private fun restoreUiFromFullscreen() {
        runOnUiThread {
            progressBar.visibility     = View.GONE
            btnBack.visibility         = View.VISIBLE
            btnForward.visibility      = View.VISIBLE
            btnRefresh.visibility      = View.VISIBLE
            btnDarkMode.visibility     = View.VISIBLE
            btnBookmark.visibility     = View.VISIBLE
            btnDownload.visibility     = View.VISIBLE
            btnSettings.visibility     = View.VISIBLE
            btnIncognito.visibility    = View.VISIBLE

            if (isIncognito) {
                incognitoBanner.visibility = View.VISIBLE
            }

            // Restore correct view state
            if (swipeRefresh.visibility == View.VISIBLE || webView.url?.isNotBlank() == true) {
                homeScreen.visibility = View.GONE
                swipeRefresh.visibility = View.VISIBLE
                findViewById<View>(R.id.browserToolbar).visibility = View.VISIBLE
            } else {
                showHomeScreen()
            }

            // Restore normal system UI
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}

// ─── DownloadRecord Adapter ───────────────────────────────────────────────────

class DownloadRecordAdapter(
    private val records: List<MainActivity.DownloadRecord>,
    private val onItemClick: (MainActivity.DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadRecordAdapter.ViewHolder>() {

    inner class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val fileName: android.widget.TextView = view.findViewById(R.id.downloadFileName)
        val url: android.widget.TextView      = view.findViewById(R.id.downloadUrl)
        val btnOpen: android.widget.ImageButton = view.findViewById(R.id.btnOpenDownload)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.download_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.fileName.text = record.fileName
        holder.url.text      = record.url.take(60)
        holder.itemView.setOnClickListener { onItemClick(record) }
        holder.btnOpen.setOnClickListener  { onItemClick(record) }
    }

    override fun getItemCount() = records.size
}

// ─── Tab Adapter ──────────────────────────────────────────────────────────────

class TabAdapter(
    private val tabs: List<MainActivity.TabInfo>,
    private val activeIndex: Int,
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.ViewHolder>() {

    inner class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: android.widget.TextView      = view.findViewById(R.id.tabTitle)
        val url: android.widget.TextView        = view.findViewById(R.id.tabUrl)
        val btnClose: android.widget.ImageButton = view.findViewById(R.id.btnCloseTab)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.tab_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.take(40)
        holder.url.text   = if (tab.isHome) "Home Screen" else tab.url.take(50)
        // Highlight active tab
        holder.itemView.setBackgroundColor(
            if (position == activeIndex)
                android.graphics.Color.parseColor("#1A1565C0")
            else
                android.graphics.Color.TRANSPARENT
        )
        holder.itemView.setOnClickListener { onTabClick(position) }
        holder.btnClose.setOnClickListener  { onTabClose(position) }
    }

    override fun getItemCount() = tabs.size
}
