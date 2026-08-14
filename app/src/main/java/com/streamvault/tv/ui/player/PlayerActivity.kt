package com.streamvault.tv.ui.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.streamvault.tv.ui.util.ScaledAppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ead.lib.cloudflare_bypass.BypassClient
import com.streamvault.tv.R
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.catalog.StreamKind
import com.streamvault.tv.data.catalog.StreamLanguage
import com.streamvault.tv.data.db.StreamCacheEntity
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.data.skip.EpisodeSkipPlan
import com.streamvault.tv.data.skip.SkipSegment
import com.streamvault.tv.databinding.ActivityPlayerBinding
import com.streamvault.tv.ui.brand.BrandSting
import com.streamvault.tv.ui.brand.VerflixedIntroView
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playback strategy for SerienStream-like hosts:
 * - Never load `/r?t=` play-blobs as top-level WebView URLs (iframe-only; redirects home).
 * - Always prefer the episode watch page; captcha/gate stays usable inside the page.
 * - Intercept real `.m3u8` / HLS CDN requests and hand off to ExoPlayer.
 * - Mode bar (HLS / Web) stays above the WebView so D-pad switching works.
 */
@UnstableApi
class PlayerActivity : ScaledAppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var series: Series? = null
    private var episode: Episode? = null
    private var usingWebPlayer = false
    private var playReferer: String? = null
    private var handedOffToExo = false
    private var lastMediaUrl: String? = null
    private var resolvingProbeUrl: String? = null
    private var exoRetryUsed = false
    private var allowEmbeddedFallback = false
    private var resumeMs: Long = 0L
    private var lastBackExitAt = 0L
    private var lastBackHandledAt = 0L
    private var exoControllerVisible = false
    /** First Back while in WebView dismisses overlays once; next Back uses double-back-to-exit. */
    private var webChromeDismissed = false
    private var nextPromptVisible = false
    private var nextPromptDismissed = false
    private var nextAutoAtMs = 0L
    private var advancingToNext = false
    /** True while Cloudflare Turnstile / click-captcha needs remote OK input. */
    private var captchaMode = false
    private var captchaSolvedPending = false
    private var skipPlan: EpisodeSkipPlan? = null
    private var activeSkip: SkipSegment? = null
    private val dismissedSkipTypes = mutableSetOf<SkipSegment.Type>()
    private var skipPlanEpisodeId: String? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Branded pre-roll that hides raw loading / WebView switching until playback starts. */
    private val brandSting by lazy { BrandSting(this) }
    private var brandGateShown = false
    private var brandGateMinUntilMs = 0L

    private val brandGateTimeout = Runnable { hideBrandGate(force = true) }

    /** Mirrors resolve status under the logo so we keep one source of truth. */
    private val brandStatusTick = object : Runnable {
        override fun run() {
            if (!brandGateShown) return
            val txt = binding.resolveStatus.text?.toString().orEmpty()
            if (txt.isNotBlank() && binding.playerBrandStatus.text?.toString() != txt) {
                binding.playerBrandStatus.text = txt
                if (binding.playerBrandStatus.alpha < 1f) {
                    binding.playerBrandStatus.animate().alpha(1f).setDuration(260).start()
                }
            }
            handler.postDelayed(this, 350L)
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            persistProgress(forceCompleted = false)
            maybeShowSkipSegment()
            maybeShowNext()
            val interval = when {
                nextPromptVisible -> 400L
                activeSkip != null -> 500L
                else -> 1_000L
            }
            handler.postDelayed(this, interval)
        }
    }

    private val captchaPoll = object : Runnable {
        override fun run() {
            if (!usingWebPlayer || handedOffToExo) return
            binding.webPlayer.evaluateJavascript(CAPTCHA_WATCH_JS, null)
            handler.postDelayed(this, if (captchaMode) 700L else 1_500L)
        }
    }

    private val resolveTimeout = Runnable {
        if (handedOffToExo) return@Runnable
        // Never wipe a visible captcha with a reload — that causes infinite loading.
        if (captchaMode) {
            binding.resolveStatus.visibility = View.VISIBLE
            binding.resolveStatus.text = getString(R.string.player_captcha_status)
            binding.captchaHint.visibility = View.VISIBLE
            focusWebPlayerForCaptcha()
            return@Runnable
        }
        if (!allowEmbeddedFallback) {
            if (isMoviePlayback()) {
                showPlayerError("[VF-302] Film-Stream timeout – kein Web-Player.")
                return@Runnable
            }
            val page = playbackPageUrl()
            if (!page.isNullOrBlank()) {
                // Keep WebView up without destructive allowEmbeddedFallback that blocks HLS handoff.
                binding.resolveStatus.visibility = View.VISIBLE
                binding.resolveStatus.text = "Stream vorbereiten… Captcha ggf. mit OK bestätigen"
                binding.playerLoading.visibility = View.GONE
                enterCaptchaMode(force = true)
                if (binding.webPlayer.url.isNullOrBlank() ||
                    binding.webPlayer.url == "about:blank"
                ) {
                    startWebResolver(page, keepVisible = true, forCaptcha = true)
                } else {
                    binding.webPlayer.evaluateJavascript(CAPTCHA_WATCH_JS, null)
                    binding.webPlayer.evaluateJavascript(CAPTCHA_FOCUS_JS, null)
                }
            } else {
                showPlayerError("Stream konnte nicht geladen werden.")
            }
        }
    }

    private fun isMoviePlayback(): Boolean =
        series?.isMovie == true ||
            episode?.streamPageUrl?.let { StreamKind.isMovieWatchPage(it) } == true ||
            episode?.id?.endsWith("-movie") == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.setBackgroundColor(Color.BLACK)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handlePlaybackBack()
                }
            }
        )

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: run { finish(); return }
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: run { finish(); return }
        val detailPathHint = intent.getStringExtra(EXTRA_DETAIL_PATH)
        val mediaKindHint = intent.getStringExtra(EXTRA_MEDIA_KIND)
        val titleHint = intent.getStringExtra(EXTRA_TITLE)
        val startOverride = if (intent.hasExtra(EXTRA_START_POSITION_MS)) {
            intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)
        } else {
            null
        }

        FocusFx.bindScale(binding.btnRetryHls, 1.06f)
        FocusFx.bindScale(binding.btnUseWebPlayer, 1.06f)
        FocusFx.bindScale(binding.btnPlayNext, 1.06f)
        FocusFx.bindScale(binding.btnSkipNextPrompt, 1.06f)
        FocusFx.bindScale(binding.btnSkipSegment, 1.06f)

        binding.btnPlayNext.setOnClickListener { playNext(auto = false) }
        binding.btnSkipNextPrompt.setOnClickListener { dismissNextPrompt(keepPlaying = true) }
        binding.btnSkipSegment.setOnClickListener { skipActiveSegment() }
        binding.btnRetryHls.setOnClickListener {
            val hls = lastMediaUrl?.takeIf { StreamKind.isDirectMediaUrl(it) }
            if (hls != null) {
                handOffToExoPlayer(hls, force = true)
            } else {
                lifecycleScope.launch { reResolveNative() }
            }
        }
        binding.btnUseWebPlayer.setOnClickListener {
            if (isMoviePlayback()) {
                Toast.makeText(this, "Kein Web-Player für Filme", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { reResolveNative() }
                return@setOnClickListener
            }
            allowEmbeddedFallback = false
            handedOffToExo = false
            captchaSolvedPending = false
            val page = playbackPageUrl()
            if (!page.isNullOrBlank()) {
                startWebResolver(page, keepVisible = true, forCaptcha = true)
                enterCaptchaMode(force = true)
            } else {
                showPlayerError("Keine Episode-Seite gefunden")
            }
        }

        setupWebPlayer()
        setupPlayerView()
        showBrandGate(titleHint)
        binding.playerLoading.visibility = View.VISIBLE
        binding.playerError.visibility = View.GONE
        binding.playerErrorPanel.visibility = View.GONE
        showModeBar(false)
        // Language switching is detail/settings only — never in the player.
        binding.btnLangToggle.visibility = View.GONE

        lifecycleScope.launch {
            val repo = (application as VerflixedApp).container.catalog
            runCatching {
                val s = repo.getSeries(
                    seriesId,
                    enrich = false,
                    detailPathHint = detailPathHint,
                    titleHint = titleHint,
                    mediaKindHint = mediaKindHint,
                )
                val ep = s.flatEpisodes().find { it.id == episodeId }
                    ?: error("[VF-203] Episode nicht gefunden")
                playReferer = ep.streamPageUrl
                    ?: s.detailPath
                    ?: (application as VerflixedApp).container.prefs.activeBaseUrl()
                val url = repo.resolveStream(ep)
                val progress = repo.getProgress(ep.id)
                val resume = when {
                    startOverride != null -> startOverride.coerceAtLeast(0L)
                    progress != null && !progress.completed -> progress.positionMs
                    else -> 0L
                }
                Triple(s, ep, url to resume)
            }.onSuccess { (s, ep, pair) ->
                series = s
                episode = ep
                resumeMs = pair.second
                startPlayback(normalizePlaybackUrl(pair.first, ep), resumeMs)
            }.onFailure {
                showPlayerError(it.toVfMessage())
            }
        }
    }

    private fun setupPlayerView() {
        binding.playerView.setBackgroundColor(Color.BLACK)
        binding.playerView.controllerShowTimeoutMs = 4_500
        binding.playerView.setShowNextButton(true)
        binding.playerView.setShowPreviousButton(true)
        binding.playerView.setShowFastForwardButton(true)
        binding.playerView.setShowRewindButton(true)
        binding.playerView.setControllerHideOnTouch(true)
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                exoControllerVisible = visibility == View.VISIBLE
            }
        )
        // Mode-Bar nur als stiller Fallback – kein manuelles „VOE erneut / Web-Player“ im Normalfall.
        showModeBar(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebPlayer() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webPlayer, true)
        binding.webPlayer.setBackgroundColor(Color.BLACK)
        binding.webPlayer.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = USER_AGENT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        binding.webPlayer.addJavascriptInterface(JsBridge(), "AndroidBridge")
        binding.webPlayer.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (usingWebPlayer && newProgress >= 75 && !handedOffToExo) {
                    binding.playerLoading.visibility = View.GONE
                }
            }
        }
        // darkryh Cloudflare-Bypass: auto-clicks classic CF IUAM / challenge pages.
        // In-page SerienStream Turnstile still uses our captchaMode helpers below.
        binding.webPlayer.webViewClient = object : BypassClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.isBlank()) return false
                // Never navigate top-level to iframe-only play blobs.
                if (StreamKind.isPlayBlobUrl(url) && request?.isForMainFrame == true) {
                    return true
                }
                // VOE embed (main or iframe): claim m3u8 natively — don't stay in VOE player.
                if (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url)) {
                    maybeClaimVoe(url)
                    // Allow iframe navigation so cookies settle; handoff happens async.
                    return request?.isForMainFrame == true
                }
                maybeCaptureMedia(url)
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (url.isBlank()) return super.shouldInterceptRequest(view, request)
                if (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url)) {
                    maybeClaimVoe(url)
                } else {
                    maybeCaptureMedia(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStartedPassed(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank() && !StreamKind.isPlayBlobUrl(url)) {
                    playReferer = url
                }
                // If site redirected a top-level blob to homepage, bounce back to episode page.
                val page = episode?.streamPageUrl
                if (!page.isNullOrBlank() && !url.isNullOrBlank() && looksLikeHomepage(url) && !handedOffToExo) {
                    handler.post {
                        if (binding.webPlayer.url != page) binding.webPlayer.loadUrl(page)
                    }
                }
                val title = view?.title.orEmpty()
                if (looksLikeCloudflareChallengeTitle(title)) {
                    handler.post { enterCaptchaMode(force = true) }
                }
            }

            @Deprecated("Use onPageFinishedByPassed")
            override fun onPageFinished(view: WebView?, url: String?) {
                val title = view?.title.orEmpty()
                if (looksLikeCloudflareChallengeTitle(title) || looksLikeCaptchaHtml(title)) {
                    handler.post {
                        enterCaptchaMode(force = true)
                        binding.resolveStatus.text = "Cloudflare wird gelöst…"
                    }
                }
                // BypassClient injects challenge clicker when title matches, then calls ByPassed.
                @Suppress("DEPRECATION")
                super.onPageFinished(view, url)
            }

            override fun onPageFinishedByPassed(view: WebView?, url: String?) {
                if (!usingWebPlayer || handedOffToExo) return
                if (!url.isNullOrBlank() && !StreamKind.isPlayBlobUrl(url)) {
                    playReferer = url
                }
                binding.playerLoading.visibility = View.GONE
                if (captchaMode) {
                    binding.resolveStatus.visibility = View.VISIBLE
                    binding.resolveStatus.text = getString(R.string.player_captcha_status)
                }
                if (!url.isNullOrBlank() && (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url))) {
                    maybeClaimVoe(url)
                }
                // Always: hide SerienStream chrome, force gate UI, try VOE click, watch captcha.
                view?.evaluateJavascript(GATE_MODE_JS, null)
                view?.evaluateJavascript(CAPTCHA_WATCH_JS, null)
                view?.evaluateJavascript(CF_CHALLENGE_CLICK_JS, null)
                view?.evaluateJavascript(CAPTCHA_FOCUS_JS, null)
                view?.evaluateJavascript(playerBootstrapJs(), null)
                view?.evaluateJavascript(IFRAME_VOE_WATCH_JS, null)
                view?.evaluateJavascript(
                    "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})();",
                ) { htmlJson ->
                    val html = unescapeJsString(htmlJson)
                    if (looksLikeCaptchaHtml(html)) {
                        handler.post { enterCaptchaMode(force = true) }
                    }
                    Regex(
                        """https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""",
                        RegexOption.IGNORE_CASE,
                    ).find(html)?.value?.let { maybeCaptureMedia(it) }
                    Regex(
                        """https?://[^"'\\\s<>]+/e/[a-zA-Z0-9]+""",
                        RegexOption.IGNORE_CASE,
                    ).findAll(html).map { it.value }.forEach { maybeClaimVoe(it) }
                    if (!url.isNullOrBlank() && (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url))) {
                        val fromHtml = runCatching {
                            (application as VerflixedApp).container.voeExtractor.extractSourceFromHtml(html)
                        }.getOrNull()
                        if (!fromHtml.isNullOrBlank()) maybeCaptureMedia(fromHtml)
                    }
                }
                if (url != null && StreamKind.isEpisodeWatchPage(url)) {
                    // Soft retry; captchaMode no longer blocks this.
                    retryNativeClaimAfterCookies()
                }
                showModeBar(false)
                handler.removeCallbacks(captchaPoll)
                handler.postDelayed(captchaPoll, 500L)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true && !handedOffToExo) {
                    showModeBar(false)
                    showPlayerError("[VF-303] WebPlayer: ${error?.description}")
                }
            }
        }
    }

    private fun maybeCaptureMedia(url: String) {
        if (handedOffToExo) return
        // During captcha wait we still accept a real media URL (force handoff).
        if (allowEmbeddedFallback && !captchaMode && !captchaSolvedPending) return
        if (StreamKind.isPlayBlobUrl(url)) return
        if (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url)) {
            maybeClaimVoe(url)
            return
        }
        if (!StreamKind.isDirectMediaUrl(url) && !looksLikeVoeHls(url)) return
        handler.post {
            if (handedOffToExo) return@post
            if (lastMediaUrl == url) return@post
            lastMediaUrl = url
            exitCaptchaMode()
            binding.resolveStatus.visibility = View.VISIBLE
            binding.resolveStatus.text = "Starte Wiedergabe…"
            showModeBar(false)
            handOffToExoPlayer(url, force = true)
        }
    }

    private var voeClaimInFlight: String? = null

    private fun maybeClaimVoe(voeUrl: String) {
        if (handedOffToExo) return
        if (allowEmbeddedFallback && !captchaMode && !captchaSolvedPending) return
        if (voeClaimInFlight == voeUrl) return
        val ep = episode ?: return
        voeClaimInFlight = voeUrl
        handler.post {
            binding.resolveStatus.visibility = View.VISIBLE
            binding.resolveStatus.text = "Stream wird aufgelöst…"
        }
        lifecycleScope.launch {
            val hls = runCatching {
                (application as VerflixedApp).container.catalog.claimVoeToHls(voeUrl, ep)
            }.getOrNull()
            if (!hls.isNullOrBlank()) {
                exitCaptchaMode()
                handOffToExoPlayer(hls, force = true)
            } else {
                if (isMoviePlayback()) {
                    showPlayerError("[VF-302] Film-Hoster ohne direkten Stream – kein Web-Player.")
                    return@launch
                }
                voeClaimInFlight = null
                handler.post {
                    if (handedOffToExo) return@post
                    // NEVER open VOE top-level (black player, no captcha). Stay on episode page.
                    val page = playbackPageUrl()
                    if (!page.isNullOrBlank()) {
                        ensureEpisodeGatePage(page)
                    } else {
                        enterCaptchaMode(force = true)
                        binding.webPlayer.evaluateJavascript(GATE_MODE_JS, null)
                    }
                }
            }
        }
    }

    private fun retryNativeClaimAfterCookies() {
        val ep = episode ?: return
        if (handedOffToExo) return
        lifecycleScope.launch {
            runCatching { CookieManager.getInstance().flush() }
            val url = runCatching {
                (application as VerflixedApp).container.catalog.resolveStream(ep)
            }.getOrNull() ?: return@launch
            when {
                StreamKind.isDirectMediaUrl(url) -> {
                    exitCaptchaMode()
                    handOffToExoPlayer(url, force = true)
                }
                StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) -> maybeClaimVoe(url)
            }
        }
    }

    private fun startPlayback(url: String, resumeMs: Long) {
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(resolveTimeout)
        hideError()
        handedOffToExo = false
        allowEmbeddedFallback = false
        captchaMode = false
        captchaSolvedPending = false
        exoRetryUsed = false
        voeClaimInFlight = null
        resolvingProbeUrl = url
        lastMediaUrl = url.takeIf { StreamKind.isDirectMediaUrl(it) || looksLikeSignedMedia(url) }
        binding.captchaHint.visibility = View.GONE
        // Movies: ExoPlayer only — never WebView / iframe / captcha hoster pages.
        if (isMoviePlayback()) {
            when {
                StreamKind.isDirectMediaUrl(url) || looksLikeSignedMedia(url) ->
                    startExoPlayer(url, resumeMs)
                StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) -> {
                    lifecycleScope.launch {
                        val ep = episode
                        val hls = if (ep != null) {
                            runCatching {
                                (application as VerflixedApp).container.catalog.claimVoeToHls(url, ep)
                            }.getOrNull()
                        } else null
                        if (!hls.isNullOrBlank()) startExoPlayer(hls, resumeMs)
                        else showPlayerError("[VF-302] Film-Hoster ohne direkten Stream – kein Web-Player.")
                    }
                }
                else -> showPlayerError("[VF-302] Kein direkter Film-Stream. Kein Web-Player.")
            }
            handler.post(progressTick)
            return
        }
        when {
            StreamKind.isDirectMediaUrl(url) && !StreamKind.isPlayBlobUrl(url) ->
                startExoPlayer(url, resumeMs)
            StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) -> {
                lifecycleScope.launch {
                    val ep = episode
                    val hls = if (ep != null) {
                        runCatching {
                            (application as VerflixedApp).container.catalog.claimVoeToHls(url, ep)
                        }.getOrNull()
                    } else null
                    if (!hls.isNullOrBlank()) {
                        startExoPlayer(hls, resumeMs)
                    } else {
                        // Prefer episode page (captcha lives there), never black VOE top-level.
                        val page = playbackPageUrl()
                        if (!page.isNullOrBlank()) {
                            startWebResolver(page, keepVisible = true, forCaptcha = true)
                        } else {
                            showPlayerError("Stream-Gate nicht erreichbar (keine Episode-Seite).")
                        }
                    }
                }
            }
            else -> {
                // Episode page / gate: open as captcha-first WebView, not a fake "video" player.
                val page = if (StreamKind.isEpisodeWatchPage(url)) url else playbackPageUrl() ?: url
                startWebResolver(page, keepVisible = true, forCaptcha = true)
            }
        }
        handler.post(progressTick)
    }

    /** Keep WebView on the episode watch page and force the visible captcha gate UI. */
    private fun ensureEpisodeGatePage(page: String) {
        enterCaptchaMode(force = true)
        val current = binding.webPlayer.url
        val onEpisode = !current.isNullOrBlank() &&
            current != "about:blank" &&
            StreamKind.isEpisodeWatchPage(current)
        if (!onEpisode) {
            startWebResolver(page, keepVisible = true, forCaptcha = true)
        } else {
            binding.resolveStatus.text = getString(R.string.player_captcha_status)
            binding.webPlayer.evaluateJavascript(GATE_MODE_JS, null)
            binding.webPlayer.evaluateJavascript(CAPTCHA_FOCUS_JS, null)
            binding.webPlayer.evaluateJavascript(playerBootstrapJs(), null)
        }
    }

    private fun looksLikeSignedMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") && (
            lower.contains(".mp4") ||
                lower.contains(".m3u8") ||
                lower.contains("x-amz-") ||
                lower.contains("signature=")
            )
    }

    private fun consumeBackForControls(): Boolean {
        if (binding.btnSkipSegment.visibility == View.VISIBLE) {
            activeSkip?.let { dismissedSkipTypes += it.type }
            hideSkipSegment()
            return true
        }
        if (binding.captchaHint.visibility == View.VISIBLE) {
            // Keep captcha mode, but allow user to dismiss the hint banner once.
            binding.captchaHint.visibility = View.GONE
            return true
        }
        if (binding.nextEpisodeBanner.visibility == View.VISIBLE) {
            binding.nextEpisodeBanner.visibility = View.GONE
            return true
        }
        // Debug mode bar (HLS/Web) — only counts when its action buttons are actually shown.
        if (binding.modeBar.visibility == View.VISIBLE &&
            (binding.btnRetryHls.visibility == View.VISIBLE || binding.btnUseWebPlayer.visibility == View.VISIBLE)
        ) {
            showModeBar(false)
            return true
        }
        // ExoPlayer controls (all native streams) — track via listener; also probe Media3 API.
        if (binding.playerView.visibility == View.VISIBLE &&
            (exoControllerVisible || binding.playerView.isControllerFullyVisible)
        ) {
            binding.playerView.hideController()
            exoControllerVisible = false
            return true
        }
        // Web player: never navigate history (breaks the stream). Dismiss overlays once.
        if (usingWebPlayer && binding.webPlayer.visibility == View.VISIBLE && !webChromeDismissed) {
            webChromeDismissed = true
            tryHideWebPlayerChrome()
            return true
        }
        return false
    }

    /** Best-effort: exit fullscreen / pause site chrome so first Back feels like “hide controls”. */
    private fun tryHideWebPlayerChrome() {
        runCatching {
            binding.webPlayer.evaluateJavascript(
                """
                (function(){
                  try {
                    if (document.fullscreenElement && document.exitFullscreen) {
                      document.exitFullscreen();
                      return 'fs';
                    }
                    if (document.webkitFullscreenElement && document.webkitExitFullscreen) {
                      document.webkitExitFullscreen();
                      return 'fs';
                    }
                    var v = document.querySelector('video');
                    if (v && !v.paused) { v.pause(); return 'pause'; }
                    var hide = document.querySelectorAll('.vjs-control-bar,.jw-controls,.plyr__controls');
                    var any = false;
                    hide.forEach(function(n){
                      if (n && n.style.display !== 'none') { n.style.display='none'; any=true; }
                    });
                    return any ? 'chrome' : '';
                  } catch(e) { return ''; }
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    /** Back: dismiss controls first; require a second Back within 2s to leave the stream. */
    private fun handlePlaybackBack() {
        // Deduplicate OnBackPressedCallback + dispatchKeyEvent on Fire OS
        val now = System.currentTimeMillis()
        if (now - lastBackHandledAt < 120L) return
        lastBackHandledAt = now
        if (consumeBackForControls()) return
        if (now - lastBackExitAt < 2_000L) {
            finish()
            return
        }
        lastBackExitAt = now
        Toast.makeText(this, "Nochmal Zurück zum Beenden", Toast.LENGTH_SHORT).show()
    }

    private suspend fun reResolveNative() {
        val ep = episode ?: return
        binding.playerLoading.visibility = View.VISIBLE
        allowEmbeddedFallback = false
        handedOffToExo = false
        val url = runCatching {
            (application as VerflixedApp).container.catalog.resolveStream(ep)
        }.getOrElse {
            showPlayerError(it.toVfMessage())
            return
        }
        startPlayback(normalizePlaybackUrl(url, ep), resumeMs)
    }

    private fun startWebResolver(
        url: String,
        keepVisible: Boolean,
        forCaptcha: Boolean = false,
    ) {
        if (isMoviePlayback()) {
            showPlayerError("[VF-302] Kein Web-Player für Filme – nur direkter Stream.")
            return
        }
        // keepVisible retained for call-site clarity; captcha-first always shows the WebView.
        @Suppress("UNUSED_PARAMETER")
        val _keep = keepVisible
        // Always prefer episode watch page — captcha lives there, not on VOE/black embeds.
        val preferred = playbackPageUrl()
        val target = when {
            StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url) || StreamKind.isPlayBlobUrl(url) ->
                preferred ?: normalizePlaybackUrl(url, episode)
            StreamKind.isEpisodeWatchPage(url) -> url
            else -> preferred ?: normalizePlaybackUrl(url, episode)
        }
        usingWebPlayer = true
        webChromeDismissed = false
        player?.release()
        player = null
        binding.playerView.visibility = View.GONE
        binding.playerView.player = null
        binding.webPlayer.visibility = View.VISIBLE
        binding.webPlayer.isFocusable = true
        binding.webPlayer.isFocusableInTouchMode = true
        // Captcha-first: focus WebView immediately, hide spinner, show hint.
        enterCaptchaMode(force = true)
        binding.playerLoading.visibility = View.GONE
        binding.resolveStatus.visibility = View.VISIBLE
        binding.resolveStatus.text = getString(R.string.player_captcha_status)
        binding.nextEpisodeBanner.visibility = View.GONE
        hideError()
        showModeBar(false)
        handedOffToExo = false

        // Do NOT override User-Agent per-request (CF detects UA mismatch with WebView JS APIs).
        val headers = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val ref = playReferer ?: (application as VerflixedApp).container.prefs.activeBaseUrl()
        if (!ref.isNullOrBlank()) {
            headers["Referer"] = if (ref.endsWith("/")) ref else "$ref/"
        }
        try {
            val current = binding.webPlayer.url
            if (!current.isNullOrBlank() && current != "about:blank" &&
                StreamKind.isEpisodeWatchPage(current) &&
                (current == target || StreamKind.isEpisodeWatchPage(target))
            ) {
                binding.webPlayer.evaluateJavascript(GATE_MODE_JS, null)
                binding.webPlayer.evaluateJavascript(CAPTCHA_FOCUS_JS, null)
                binding.webPlayer.evaluateJavascript(playerBootstrapJs(), null)
            } else if (current == target && !forCaptcha) {
                binding.webPlayer.reload()
            } else {
                binding.webPlayer.loadUrl(target, headers)
            }
        } catch (t: Throwable) {
            showPlayerError("[VF-303] ${t.message}")
        }

        handler.removeCallbacks(resolveTimeout)
        // Longer window: user must see & solve captcha; never destructive reload.
        handler.postDelayed(resolveTimeout, 120_000L)
        handler.removeCallbacks(captchaPoll)
        handler.postDelayed(captchaPoll, 400L)

        episode?.let { ep ->
            lifecycleScope.launch {
                runCatching {
                    (application as VerflixedApp).container.catalog.saveProgress(
                        episodeId = ep.id,
                        seriesId = ep.seriesId,
                        positionMs = 1_000L,
                        durationMs = 3_600_000L,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.number,
                    )
                }
            }
        }
    }

    private fun handOffToExoPlayer(mediaUrl: String, force: Boolean) {
        if (handedOffToExo && !force) return
        if (allowEmbeddedFallback && !force) return
        handedOffToExo = true
        allowEmbeddedFallback = false
        lastMediaUrl = mediaUrl
        exitCaptchaMode()
        handler.removeCallbacks(resolveTimeout)
        handler.removeCallbacks(captchaPoll)
        binding.resolveStatus.visibility = View.VISIBLE
        binding.resolveStatus.text = "Starte Wiedergabe…"
        hideError()
        episode?.let { persistDirectMedia(it, mediaUrl) }
        runCatching {
            binding.webPlayer.stopLoading()
            binding.webPlayer.loadUrl("about:blank")
        }
        binding.webPlayer.visibility = View.GONE
        startExoPlayer(mediaUrl, 0L)
    }

    private fun persistDirectMedia(ep: Episode, mediaUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val app = application as VerflixedApp
                val profileId = app.container.profiles.activeId()
                val lang = app.container.prefs.streamLanguage(profileId)
                app.container.db.streams().upsert(
                    StreamCacheEntity(
                        profileId = profileId,
                        episodeId = ep.id,
                        seriesId = ep.seriesId,
                        streamUrl = mediaUrl,
                        kind = "${StreamKind.streamKindLabel(mediaUrl)}|${StreamLanguage.normalize(lang)}",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun startExoPlayer(url: String, resumeMs: Long) {
        usingWebPlayer = false
        webChromeDismissed = false
        handedOffToExo = true
        lastMediaUrl = url
        handler.removeCallbacks(resolveTimeout)
        binding.webPlayer.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.playerLoading.visibility = View.VISIBLE
        binding.resolveStatus.visibility = View.GONE
        hideError()
        showModeBar(false)

        // Firestream CDN: prefer firestream origin as Referer (download/resolve URLs).
        if (url.contains("firestream", ignoreCase = true) &&
            playReferer?.contains("firestream", ignoreCase = true) != true
        ) {
            playReferer = "https://firestream.to/"
        }
        val referer = playReferer
            ?: episode?.streamPageUrl
            ?: (application as VerflixedApp).container.prefs.activeBaseUrl()
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?: runCatching { CookieManager.getInstance().getCookie(referer) }.getOrNull()
            ?: ""
        val origin = runCatching {
            val u = Uri.parse(referer)
            "${u.scheme}://${u.host}"
        }.getOrDefault("https://s.to")

        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Origin" to origin,
        )
        if (cookie.isNotBlank()) headers["Cookie"] = cookie

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(headers)

        player?.release()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()
        player = exo
        binding.playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    binding.playerLoading.visibility = View.GONE
                    binding.resolveStatus.visibility = View.GONE
                    // Playback is live — lift the branded pre-roll.
                    hideBrandGate()
                    showModeBar(false)
                    refreshSkipPlan(force = false)
                }
                if (playbackState == Player.STATE_ENDED) {
                    persistProgress(forceCompleted = true)
                    playNext(auto = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // no-op
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!exoRetryUsed) {
                    exoRetryUsed = true
                    binding.resolveStatus.visibility = View.VISIBLE
                    binding.resolveStatus.text = "Stream erneut verbinden…"
                    startExoPlayer(url, 0L)
                    return
                }
                // Auto: Episode-Seite erneut resolven (wie Desktop Webapp), ohne Debug-Buttons.
                if (isMoviePlayback()) {
                    showPlayerError("[VF-304] Film-Wiedergabe fehlgeschlagen – kein Web-Player.")
                    return
                }
                val page = playbackPageUrl()
                if (!page.isNullOrBlank()) {
                    handedOffToExo = false
                    allowEmbeddedFallback = false
                    binding.resolveStatus.text = "Stream wird aufgelöst… Captcha ggf. mit OK"
                    startWebResolver(page, keepVisible = true, forCaptcha = true)
                    return
                }
                showPlayerError("Wiedergabe fehlgeschlagen.")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (resumeMs > 5_000L) exo.seekTo(resumeMs)
        exo.playWhenReady = true
        binding.playerView.requestFocus()
    }

    private fun showPlayerError(msg: String) {
        hideBrandGate(force = true)
        binding.playerLoading.visibility = View.GONE
        // Kill leaking iframe / WebView so it never stays visible under the error panel.
        runCatching {
            binding.webPlayer.stopLoading()
            binding.webPlayer.loadUrl("about:blank")
        }
        binding.webPlayer.visibility = View.GONE
        usingWebPlayer = false
        binding.playerError.text = msg.replace(Regex("""\[VF-\d+]"""), "").trim()
        binding.playerError.visibility = View.VISIBLE
        binding.playerErrorPanel.visibility = View.VISIBLE
        // Stiller Auto-Retry-Button statt HLS/Web Debug-Leiste
        showModeBar(false)
        binding.playerActions.removeAllViews()
        val retry = android.widget.Button(this).apply {
            text = "Erneut versuchen"
            setOnClickListener {
                hideError()
                val page = playbackPageUrl()
                val ep = episode
                if (ep != null) {
                    binding.playerLoading.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        runCatching {
                            (application as VerflixedApp).container.catalog.resolveStream(ep)
                        }.onSuccess { url ->
                            startPlayback(normalizePlaybackUrl(url, ep), 0L)
                        }.onFailure {
                            if (isMoviePlayback()) showPlayerError(it.toVfMessage())
                            else if (!page.isNullOrBlank()) startWebResolver(page, keepVisible = true)
                            else showPlayerError(it.toVfMessage())
                        }
                    }
                } else if (!isMoviePlayback() && !page.isNullOrBlank()) {
                    startWebResolver(page, keepVisible = true)
                } else {
                    showPlayerError("Kein Stream verfügbar")
                }
            }
        }
        binding.playerActions.addView(retry)
        retry.requestFocus()
    }

    private fun hideError() {
        binding.playerError.visibility = View.GONE
        binding.playerErrorPanel.visibility = View.GONE
    }

    private fun showModeBar(visible: Boolean) {
        // Language toggle is never shown in the player. Debug HLS/Web buttons only when requested.
        binding.btnLangToggle.visibility = View.GONE
        binding.btnRetryHls.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnUseWebPlayer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.modeBar.visibility =
            if (visible && (binding.btnRetryHls.visibility == View.VISIBLE ||
                    binding.btnUseWebPlayer.visibility == View.VISIBLE)
            ) View.VISIBLE else View.GONE
    }

    private fun currentPreferredLang(): String {
        val prefs = (application as VerflixedApp).container.prefs
        return StreamLanguage.normalize(prefs.streamLanguage(prefs.activeProfileId))
    }

    private fun looksLikeCloudflareChallengeTitle(title: String): Boolean {
        if (title.isBlank()) return false
        val t = title.lowercase()
        // darkryh BypassClient uses title.contains("..."); keep that plus common CF titles.
        return title.contains("...") ||
            t.contains("just a moment") ||
            t.contains("attention required") ||
            t.contains("checking your browser") ||
            t.contains("einen moment") ||
            t.contains("security check") ||
            t.contains("cloudflare")
    }

    private fun looksLikeCaptchaHtml(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        return lower.contains("cf-turnstile") ||
            lower.contains("player-prepare-turnstile") ||
            lower.contains("playerpreparemodal") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("cf-challenge") ||
            (lower.contains("turnstile") && lower.contains("cloudflare"))
    }

    /**
     * Branded pre-roll instead of a bare spinner. Also covers the HLS↔WebView
     * switching so the user never sees the SerienStream page flash by.
     */
    private fun showBrandGate(titleHint: String?) {
        if (brandGateShown) return
        brandGateShown = true
        binding.playerBrandGate.visibility = View.VISIBLE
        binding.playerBrandGate.alpha = 1f
        binding.playerBrandStatus.alpha = 0f
        binding.playerBrandStatus.text = titleHint?.takeIf { it.isNotBlank() } ?: ""
        binding.playerIntro.compact = true
        binding.playerIntro.play(VerflixedIntroView.DEFAULT_DURATION_MS)
        brandSting.play(0.9f)
        brandGateMinUntilMs = System.currentTimeMillis() +
            VerflixedIntroView.DEFAULT_DURATION_MS - 250L
        handler.post(brandStatusTick)
        handler.removeCallbacks(brandGateTimeout)
        // Safety: never trap the user behind the logo if resolving drags on.
        handler.postDelayed(brandGateTimeout, 15_000L)
    }

    /**
     * @param force skip the minimum on-screen time (captcha / errors need the UI now)
     */
    private fun hideBrandGate(force: Boolean = false) {
        if (!brandGateShown) return
        val remaining = brandGateMinUntilMs - System.currentTimeMillis()
        if (!force && remaining > 0L) {
            handler.removeCallbacks(brandGateTimeout)
            handler.postDelayed({ hideBrandGate(force = true) }, remaining)
            return
        }
        brandGateShown = false
        handler.removeCallbacks(brandStatusTick)
        handler.removeCallbacks(brandGateTimeout)
        brandSting.stop()
        binding.playerIntro.stop()
        binding.playerBrandGate.animate()
            .alpha(0f)
            .setDuration(if (force) 220L else 320L)
            .withEndAction {
                binding.playerBrandGate.visibility = View.GONE
                binding.playerBrandGate.alpha = 1f
            }
            .start()
    }

    private fun enterCaptchaMode(force: Boolean = false) {
        if (handedOffToExo) return
        if (captchaMode && !force) return
        // Captcha must be visible and solvable — the brand gate steps aside.
        hideBrandGate(force = true)
        captchaMode = true
        captchaSolvedPending = false
        allowEmbeddedFallback = false
        handler.removeCallbacks(resolveTimeout)
        binding.playerLoading.visibility = View.GONE
        binding.resolveStatus.visibility = View.VISIBLE
        binding.resolveStatus.text = getString(R.string.player_captcha_status)
        binding.captchaHint.visibility = View.VISIBLE
        showModeBar(false)
        // Let the WebView sit above chrome so D-pad/OK reach Turnstile.
        binding.webPlayer.elevation = 40f
        binding.webPlayer.translationZ = 40f
        binding.playerChrome.elevation = 8f
        binding.playerChrome.translationZ = 8f
        (binding.playerChrome as? android.view.ViewGroup)?.descendantFocusability =
            android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        focusWebPlayerForCaptcha()
        binding.webPlayer.evaluateJavascript(GATE_MODE_JS, null)
        binding.webPlayer.evaluateJavascript(CAPTCHA_FOCUS_JS, null)
    }

    private fun exitCaptchaMode() {
        captchaMode = false
        binding.captchaHint.visibility = View.GONE
        binding.webPlayer.elevation = 0f
        binding.webPlayer.translationZ = 0f
        binding.playerChrome.elevation = 28f
        binding.playerChrome.translationZ = 28f
        (binding.playerChrome as? android.view.ViewGroup)?.descendantFocusability =
            android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.playerChrome.bringToFront()
    }

    private fun focusWebPlayerForCaptcha() {
        binding.webPlayer.isFocusable = true
        binding.webPlayer.isFocusableInTouchMode = true
        binding.webPlayer.requestFocus()
        runCatching {
            binding.webPlayer.requestFocusFromTouch()
        }
    }

    private fun onCaptchaSignal(status: String) {
        when (status.lowercase()) {
            "need", "visible", "required" -> handler.post { enterCaptchaMode() }
            "solved", "ok", "done" -> handler.post { onCaptchaSolved() }
        }
    }

    private fun onCaptchaSolved() {
        if (handedOffToExo) return
        captchaSolvedPending = true
        captchaMode = false
        binding.captchaHint.visibility = View.GONE
        binding.playerLoading.visibility = View.VISIBLE
        binding.resolveStatus.visibility = View.VISIBLE
        binding.resolveStatus.text = getString(R.string.player_captcha_solved)
        runCatching { CookieManager.getInstance().flush() }
        // Resume bootstrap scrape now that the gate is open.
        binding.webPlayer.evaluateJavascript(playerBootstrapJs(), null)
        binding.webPlayer.evaluateJavascript(IFRAME_VOE_WATCH_JS, null)
        retryNativeClaimAfterCookies()
        handler.postDelayed({
            if (!handedOffToExo) retryNativeClaimAfterCookies()
        }, 1_500L)
        handler.postDelayed({
            if (!handedOffToExo) retryNativeClaimAfterCookies()
        }, 4_000L)
    }

    private fun injectCaptchaClick() {
        binding.webPlayer.evaluateJavascript(CAPTCHA_CLICK_JS, null)
        focusWebPlayerForCaptcha()
    }

    private fun playerBootstrapJs(): String {
        val pref = currentPreferredLang()
        val preferDe = pref == StreamLanguage.DE
        return """
(function(){
  try {
    function tokenOk(){
      var t=document.querySelector('[name="cf-turnstile-response"]');
      return !!(t && t.value && String(t.value).length>10);
    }
    function hasCaptcha(){
      try {
        if (tokenOk()) return false;
        return !!(document.querySelector('.cf-turnstile, #player-prepare-turnstile, #playerPrepareModal.show, #playerPrepareModal.in, #playerPrepareModal[style*="display: block"], #playerPrepareModal[style*="display:block"], #playerPrepareModal:not([aria-hidden="true"]), iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"]'));
      } catch(e){ return false; }
    }
    function notifyNeed(){
      try { if (window.AndroidBridge && AndroidBridge.onCaptcha) AndroidBridge.onCaptcha('need'); } catch(e){}
    }
    if (tokenOk()) {
      try { if (window.AndroidBridge && AndroidBridge.onCaptcha) AndroidBridge.onCaptcha('solved'); } catch(e){}
      return;
    }
    if (hasCaptcha()) {
      notifyNeed();
      return;
    }
    var preferDe = ${if (preferDe) "true" else "false"};

    function langScore(l){
      l = (l||'').toLowerCase();
      var isDe = l.indexOf('deutsch')>=0 || l.indexOf('german')>=0 || l==='de' || l==='1';
      var isEn = l.indexOf('englisch')>=0 || l.indexOf('english')>=0 || l==='en' || l==='2';
      if (preferDe) {
        if (isDe) return 100;
        if (isEn) return 5;
      } else {
        if (isEn) return 100;
        if (isDe) return 5;
      }
      return 0;
    }

    function clickVoe(){
      if (tokenOk()) return false;
      if (hasCaptcha()) { notifyNeed(); return false; }
      var buttons = Array.prototype.slice.call(document.querySelectorAll(
        'button.link-box, .link-box, [data-play-url], [data-provider-name], a[data-play-url], .hosterSiteVideoButton'
      ));
      var scored = buttons.map(function(b){
        var p = ((b.getAttribute('data-provider-name')||'') + ' ' + (b.textContent||'')).toLowerCase();
        var l = ((b.getAttribute('data-language-label')||'') + ' ' + (b.getAttribute('data-language')||'') + ' ' +
                 (b.getAttribute('data-language-id')||'') + ' ' + (b.getAttribute('data-lang-key')||'') + ' ' +
                 (b.getAttribute('title')||''));
        try {
          var n = b;
          for (var i=0;i<6 && n;i++){
            var h = n.querySelector && n.querySelector('h5,h4,h3');
            if (!h && n.previousElementSibling) h = n.previousElementSibling;
            if (h && (h.tagName||'').match(/^H[345]$/)) l += ' ' + (h.textContent||'');
            n = n.parentElement;
          }
        } catch(e){}
        var score = langScore(l);
        if (p.indexOf('voe') >= 0) score += 50;
        return {b:b, score:score};
      }).filter(function(x){ return x.score > 0; })
        .sort(function(a,b){ return b.score - a.score; });
      if (scored.length) {
        try { scored[0].b.focus(); scored[0].b.click(); } catch(e) {}
        // Captcha modal usually appears after hoster click — force UI next ticks.
        setTimeout(function(){ if (hasCaptcha()) notifyNeed(); }, 400);
        setTimeout(function(){ if (hasCaptcha()) notifyNeed(); }, 1200);
        setTimeout(function(){ if (hasCaptcha()) notifyNeed(); }, 2500);
        return true;
      }
      var play = document.querySelector('.hosterSiteVideo .play, .play-button, button[data-play-url], .vjs-big-play-button, button.play');
      if (play) { try { play.click(); } catch(e) {} }
      return false;
    }

    clickVoe();
    setTimeout(clickVoe, 700);
    setTimeout(clickVoe, 1800);
    setTimeout(clickVoe, 3600);

    function focusFrame(){
      if (hasCaptcha()) { notifyNeed(); return; }
      var frame = document.getElementById('player-iframe') || document.querySelector('iframe[src*="/r?t="], iframe[src*="voe"], iframe[src*="/e/"], iframe');
      if (!frame) return;
      try { frame.scrollIntoView({block:'center'}); } catch(e) {}
      try {
        var src = frame.src || '';
        if (src && window.AndroidBridge && AndroidBridge.onVoeUrl) {
          if (/voe|\/e\//i.test(src)) AndroidBridge.onVoeUrl(src);
        }
      } catch(e) {}
    }
    focusFrame();
    setTimeout(focusFrame, 900);
    setTimeout(focusFrame, 1400);
    setTimeout(focusFrame, 3200);
  } catch(e) {}
})();
""".trimIndent()
    }

    private fun playbackPageUrl(): String? {
        val ep = episode
        val page = ep?.streamPageUrl?.takeIf { it.isNotBlank() }
        if (!page.isNullOrBlank()) return page
        val probe = resolvingProbeUrl
        if (!probe.isNullOrBlank() && !StreamKind.isPlayBlobUrl(probe) && StreamKind.isEpisodeWatchPage(probe)) {
            return probe
        }
        return null
    }

    private fun normalizePlaybackUrl(url: String, ep: Episode?): String {
        if (StreamKind.isDirectMediaUrl(url) && !StreamKind.isPlayBlobUrl(url)) return url
        // Prefer VOE embed over SerienStream episode page (VOE rarely has captcha).
        if (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url)) return url
        if (StreamKind.isPlayBlobUrl(url)) {
            return ep?.streamPageUrl?.takeIf { it.isNotBlank() } ?: url
        }
        if (StreamKind.isEpisodeWatchPage(url)) return url
        return ep?.streamPageUrl?.takeIf { it.isNotBlank() } ?: url
    }

    private fun looksLikeHomepage(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        return path.isBlank() || path == "/"
    }

    private fun looksLikeVoeHls(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".m3u8")) return true
        if (lower.contains("m3u8") && (lower.contains("voe") || lower.contains("delivery") || lower.contains("stream"))) {
            return true
        }
        return false
    }

    private fun unescapeJsString(htmlJson: String?): String {
        if (htmlJson.isNullOrBlank() || htmlJson == "null") return ""
        return htmlJson.trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\u003C", "<")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun onMediaUrl(url: String?) {
            val u = url?.trim().orEmpty()
            if (u.isBlank()) return
            maybeCaptureMedia(u)
        }

        @android.webkit.JavascriptInterface
        fun onVoeUrl(url: String?) {
            val u = url?.trim().orEmpty()
            if (u.isBlank()) return
            maybeClaimVoe(u)
        }

        @android.webkit.JavascriptInterface
        fun onCaptcha(status: String?) {
            val s = status?.trim().orEmpty()
            if (s.isBlank()) return
            onCaptchaSignal(s)
        }
    }

    private fun absoluteEpisodeNumber(s: Series, ep: Episode): Int {
        var prior = 0
        for (season in s.seasons.sortedBy { it.number }) {
            if (season.number < ep.seasonNumber) {
                prior += season.episodes.size.coerceAtLeast(0)
            } else if (season.number == ep.seasonNumber) {
                return prior + ep.number.coerceAtLeast(1)
            }
        }
        return ep.number.coerceAtLeast(1)
    }

    private fun refreshSkipPlan(force: Boolean) {
        val s = series ?: return
        val ep = episode ?: return
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0L) return
        if (!force && skipPlanEpisodeId == ep.id && skipPlan != null) return
        skipPlanEpisodeId = ep.id
        dismissedSkipTypes.clear()
        hideSkipSegment()
        lifecycleScope.launch {
            val app = application as VerflixedApp
            val resolved = withContext(Dispatchers.IO) {
                var cur = s
                if (cur.imdbId.isNullOrBlank() && cur.tmdbId == null) {
                    cur = runCatching { app.container.wikidata.enrich(cur) }.getOrDefault(cur)
                }
                cur
            }
            if (resolved.imdbId != s.imdbId || resolved.tmdbId != s.tmdbId) {
                series = resolved
            }
            val absEp = absoluteEpisodeNumber(resolved, ep)
            val crowd = withContext(Dispatchers.IO) {
                val ani = runCatching {
                    app.container.aniSkip.skipSegments(
                        series = resolved,
                        episodeNumber = ep.number,
                        durationMs = dur,
                        absoluteEpisodeNumber = absEp,
                    )
                }.getOrDefault(emptyList())
                val db = runCatching {
                    app.container.crowdSkip.skipSegments(
                        series = resolved,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.number,
                        durationMs = dur,
                    )
                }.getOrDefault(emptyList())
                ani + db
            }
            // Persist MAL id on in-memory series for this session when resolved.
            val mal = withContext(Dispatchers.IO) {
                runCatching { app.container.aniSkip.resolveMalId(s) }.getOrNull()
            }
            if (mal != null && s.malId == null) {
                series = s.copy(malId = mal)
            }
            val plan = app.container.skipMarks.buildPlan(
                episodeId = ep.id,
                seriesId = s.id,
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.number,
                durationMs = dur,
                crowd = crowd,
            )
            skipPlan = plan
        }
    }

    private fun maybeShowSkipSegment() {
        if (captchaMode || nextPromptVisible) {
            hideSkipSegment()
            return
        }
        val p = player ?: return
        if (!p.isPlaying || p.duration <= 0L) {
            hideSkipSegment()
            return
        }
        val pos = p.currentPosition
        val plan = skipPlan
        if (plan == null) {
            hideSkipSegment()
            return
        }
        // Prefer intro/recap over credits chip (credits use next-ep banner).
        val hit = plan.segments.firstOrNull { seg ->
            seg.type != SkipSegment.Type.CREDITS &&
                seg.type !in dismissedSkipTypes &&
                seg.contains(pos)
        } ?: plan.segments.firstOrNull { seg ->
            seg.type == SkipSegment.Type.CREDITS &&
                seg.type !in dismissedSkipTypes &&
                seg.contains(pos) &&
                !nextPromptVisible
        }
        if (hit == null) {
            hideSkipSegment()
            return
        }
        if (activeSkip?.type == hit.type && binding.btnSkipSegment.visibility == View.VISIBLE) {
            return
        }
        activeSkip = hit
        binding.btnSkipSegment.text = hit.label
        binding.btnSkipSegment.visibility = View.VISIBLE
        binding.btnSkipSegment.post { binding.btnSkipSegment.requestFocus() }
    }

    private fun hideSkipSegment() {
        activeSkip = null
        binding.btnSkipSegment.visibility = View.GONE
    }

    private fun skipActiveSegment() {
        val seg = activeSkip ?: return
        val p = player ?: return
        val s = series
        dismissedSkipTypes += seg.type
        hideSkipSegment()
        // Learn intro length from where the user skipped (works for heuristic + AniSkip).
        if (s != null && (seg.type == SkipSegment.Type.INTRO || seg.type == SkipSegment.Type.RECAP || seg.type == SkipSegment.Type.PREVIEW)) {
            val end = seg.endMs.coerceAtLeast(p.currentPosition)
            if (end in 8_000L..240_000L) {
                (application as VerflixedApp).container.skipMarks.recordIntroEnd(s.id, end)
            }
        }
        val target = when (seg.type) {
            SkipSegment.Type.CREDITS -> p.duration.coerceAtLeast(seg.endMs)
            else -> seg.endMs
        }
        p.seekTo(target.coerceAtMost(p.duration.coerceAtLeast(0L)))
        if (seg.type == SkipSegment.Type.CREDITS) {
            // Jumping into/over credits → offer next episode promptly.
            maybeShowNext()
        }
    }

    private fun nextPromptLeadMs(durationMs: Long): Long {
        val plan = skipPlan
        if (plan != null && plan.nextPromptLeadMs > 0L) return plan.nextPromptLeadMs
        val s = series
        val store = (application as VerflixedApp).container.skipMarks
        return s?.let { store.creditsLeadMs(it.id) }
            ?: store.heuristicLeadMs(durationMs)
    }

    private fun maybeShowNext() {
        val s = series ?: return
        val ep = episode ?: return
        if (s.isMovie || ep.id.endsWith("-movie")) {
            hideNextPrompt()
            return
        }
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0L || !p.isPlaying) return
        if (skipPlanEpisodeId != ep.id || skipPlan == null) {
            refreshSkipPlan(force = false)
        }
        val lead = nextPromptLeadMs(dur)
        val left = dur - p.currentPosition
        val next = (application as VerflixedApp).container.catalog.nextEpisode(s, ep)
        if (next == null) {
            hideNextPrompt()
            return
        }
        if (nextPromptDismissed) {
            if (left > lead) nextPromptDismissed = false
            return
        }
        // Don't stack intro skip + next banner.
        if (activeSkip != null && activeSkip?.type != SkipSegment.Type.CREDITS) {
            hideNextPrompt()
            return
        }
        if (left in 1..lead) {
            hideSkipSegment()
            showNextPrompt(next, left)
        } else if (left > lead) {
            hideNextPrompt()
        }
    }

    private fun showNextPrompt(next: Episode, leftMs: Long) {
        if (!nextPromptVisible) {
            nextPromptVisible = true
            nextAutoAtMs = System.currentTimeMillis() + 10_000L
            binding.nextEpisodeBanner.visibility = View.VISIBLE
            binding.nextEpisodeText.text = getString(
                R.string.player_next_title,
                next.seasonNumber,
                next.number,
                next.title,
            )
            binding.btnPlayNext.post {
                if (nextPromptVisible) binding.btnPlayNext.requestFocus()
            }
        }
        val remainSec = ((nextAutoAtMs - System.currentTimeMillis()) / 1000L)
            .coerceIn(0L, 10L)
            .toInt()
        binding.nextEpisodeCountdown.text = getString(R.string.player_next_countdown, remainSec)
        if (remainSec <= 0 && !advancingToNext) {
            playNext(auto = true)
        }
    }

    private fun hideNextPrompt() {
        nextPromptVisible = false
        nextAutoAtMs = 0L
        binding.nextEpisodeBanner.visibility = View.GONE
    }

    private fun dismissNextPrompt(keepPlaying: Boolean) {
        val s = series
        val p = player
        if (s != null && p != null && p.duration > 0L) {
            val left = (p.duration - p.currentPosition).coerceAtLeast(0L)
            // „Weiter schauen“ ⇒ Abspann länger als unser Fenster → lernen.
            if (left > 15_000L) {
                (application as VerflixedApp).container.skipMarks
                    .recordCreditsLeadAtLeast(s.id, left)
                skipPlan = null
                skipPlanEpisodeId = null
            }
        }
        nextPromptDismissed = true
        hideNextPrompt()
        if (keepPlaying) {
            Toast.makeText(this, "Läuft bis zum Schluss", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playNext(auto: Boolean) {
        if (advancingToNext) return
        val s = series ?: return
        val ep = episode ?: return
        val next = (application as VerflixedApp).container.catalog.nextEpisode(s, ep) ?: run {
            if (!auto) Toast.makeText(this, "Keine nächste Episode", Toast.LENGTH_SHORT).show()
            hideNextPrompt()
            return
        }
        // Learn credits length from manual next (position when user skipped ahead).
        if (!auto) {
            val p = player
            val dur = p?.duration ?: 0L
            val pos = p?.currentPosition ?: 0L
            if (dur > 0L && pos > 0L && pos < dur) {
                val lead = (dur - pos).coerceIn(15_000L, 8 * 60_000L)
                (application as VerflixedApp).container.skipMarks.recordCreditsLead(s.id, lead)
            }
        }
        advancingToNext = true
        persistProgress(forceCompleted = true)
        episode = next
        skipPlan = null
        skipPlanEpisodeId = null
        dismissedSkipTypes.clear()
        hideSkipSegment()
        handedOffToExo = false
        allowEmbeddedFallback = false
        exoRetryUsed = false
        lastMediaUrl = null
        nextPromptDismissed = false
        hideNextPrompt()
        binding.playerLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val repo = (application as VerflixedApp).container.catalog
            runCatching { repo.resolveStream(next) }
                .onSuccess { url ->
                    advancingToNext = false
                    playReferer = next.streamPageUrl ?: playReferer
                    startPlayback(normalizePlaybackUrl(url, next), 0L)
                }
                .onFailure {
                    advancingToNext = false
                    showPlayerError(it.toVfMessage())
                }
        }
    }

    private fun persistProgress(forceCompleted: Boolean) {
        val ep = episode ?: return
        val p = player
        val pos = p?.currentPosition ?: 0L
        var dur = p?.duration?.takeIf { it > 0 } ?: 0L
        if (forceCompleted && dur <= 0L) dur = pos.coerceAtLeast(1L)
        val storePos = if (forceCompleted && dur > 0L) dur else pos.coerceAtLeast(0L)
        lifecycleScope.launch {
            runCatching {
                (application as VerflixedApp).container.catalog.saveProgress(
                    episodeId = ep.id,
                    seriesId = ep.seriesId,
                    positionMs = storePos,
                    durationMs = dur.coerceAtLeast(0L),
                    seasonNumber = ep.seasonNumber,
                    episodeNumber = ep.number,
                )
            }
        }
    }

    private fun prefetchNext() {
        val s = series ?: return
        val ep = episode ?: return
        val next = (application as VerflixedApp).container.catalog.nextEpisode(s, ep) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { (application as VerflixedApp).container.catalog.resolveStream(next) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercept Back before WebView/PlayerView so hide-controls + double-back works everywhere.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            handlePlaybackBack()
            return true
        }
        // While Cloudflare captcha is up, route D-pad/OK into the WebView (don't steal focus).
        if (captchaMode && usingWebPlayer && binding.webPlayer.visibility == View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        injectCaptchaClick()
                    }
                    focusWebPlayerForCaptcha()
                    return binding.webPlayer.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    focusWebPlayerForCaptcha()
                    return binding.webPlayer.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK is handled in dispatchKeyEvent
        if (captchaMode && usingWebPlayer) {
            // Captcha keys handled in dispatchKeyEvent → WebView
            return false
        }
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                if (usingWebPlayer) {
                    // Prefer focusing WebView over showing empty Exo controls (infinite-loading trap).
                    focusWebPlayerForCaptcha()
                    injectCaptchaClick()
                    binding.webPlayer.evaluateJavascript(CAPTCHA_WATCH_JS, null)
                    return true
                }
                if (p != null) {
                    if (p.isPlaying) p.pause() else p.play()
                    binding.playerView.showController()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                if (p != null && (exoControllerVisible || binding.playerView.isControllerFullyVisible)) {
                    p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration.coerceAtLeast(0L)))
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> {
                if (p != null && (exoControllerVisible || binding.playerView.isControllerFullyVisible)) {
                    p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNext(auto = false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        prefetchNext()
    }

    override fun onStop() {
        persistProgress(forceCompleted = false)
        player?.playWhenReady = false
        brandSting.stop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(resolveTimeout)
        handler.removeCallbacks(captchaPoll)
        handler.removeCallbacks(brandStatusTick)
        handler.removeCallbacks(brandGateTimeout)
        brandSting.stop()
        binding.playerIntro.stop()
        persistProgress(forceCompleted = false)
        runCatching {
            binding.webPlayer.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
        }
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_DETAIL_PATH = "detail_path"
        const val EXTRA_MEDIA_KIND = "media_kind"
        const val EXTRA_TITLE = "title"
        /** Explicit start position in ms (0 = from beginning). If absent, saved progress is used. */
        const val EXTRA_START_POSITION_MS = "start_position_ms"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Watch iframe.src mutations for VOE embeds after captcha unlock. */
        private const val IFRAME_VOE_WATCH_JS = """
(function(){
  try {
    var frame = document.getElementById('player-iframe') || document.querySelector('iframe');
    if (!frame || !window.AndroidBridge) return;
    function report(){
      try {
        var src = frame.src || '';
        if (!src) return;
        if (/voe|\/e\//i.test(src) && AndroidBridge.onVoeUrl) AndroidBridge.onVoeUrl(src);
        if (/\.m3u8/i.test(src) && AndroidBridge.onMediaUrl) AndroidBridge.onMediaUrl(src);
      } catch(e) {}
    }
    report();
    try {
      new MutationObserver(report).observe(frame, {attributes:true, attributeFilter:['src']});
    } catch(e) {}
    setInterval(report, 1500);
  } catch(e) {}
})();
"""

        /** Detect Cloudflare Turnstile / prepare modal / IUAM and notify Android. */
        private const val CAPTCHA_WATCH_JS = """
(function(){
  try {
    function tokenOk(){
      var t=document.querySelector('[name="cf-turnstile-response"], textarea[name="cf-turnstile-response"], input[name="cf-turnstile-response"]');
      return !!(t && t.value && String(t.value).length>10);
    }
    function challengeForm(){
      return document.querySelector('#challenge-form, #challenge-stage, .cf-browser-verification, #cf-challenge-running');
    }
    function visibleTurnstile(){
      var nodes=document.querySelectorAll('#playerPrepareModal, .cf-turnstile, #player-prepare-turnstile, iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"], .cf-challenge');
      for (var i=0;i<nodes.length;i++){
        var n=nodes[i];
        var st=window.getComputedStyle?getComputedStyle(n):null;
        if (!st || (st.display!=='none' && st.visibility!=='hidden' && st.opacity!=='0')) return true;
      }
      var modal=document.getElementById('playerPrepareModal');
      if (modal && modal.classList && !modal.classList.contains('hidden')) return true;
      return false;
    }
    if (!window.AndroidBridge || !AndroidBridge.onCaptcha) return;
    if (tokenOk()) { AndroidBridge.onCaptcha('solved'); window.__vfCaptchaLock=false; window.__vfHadChallenge=false; return; }
    if (challengeForm() || visibleTurnstile()) {
      window.__vfHadChallenge=true;
      AndroidBridge.onCaptcha('need');
      window.__vfCaptchaLock=true;
      return;
    }
    if (window.__vfHadChallenge) {
      window.__vfHadChallenge=false;
      AndroidBridge.onCaptcha('solved');
      window.__vfCaptchaLock=false;
    }
  } catch(e) {}
})();
"""

        /**
         * Adapted from darkryh/Cloudflare-Bypass Scripts.CLOUDFLARE_BYPASS (MIT).
         * Auto-clicks classic CF challenge buttons; also tries Turnstile host checkbox.
         */
        private const val CF_CHALLENGE_CLICK_JS = """
(function(){
  try {
    if (window.__vfCfClickTimer) return;
    window.__vfCfClickTimer = setInterval(function(){
      try {
        var form = document.querySelector('#challenge-form');
        if (form) {
          var simple = document.querySelector("#challenge-stage > div > input[type='button'], #challenge-stage input[type='button'], input[type='button'].big-button");
          if (simple) { try { simple.click(); } catch(e){} }
          var box = document.querySelector('div.hcaptcha-box > iframe, .cf-turnstile iframe, iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"]');
          if (box) {
            try { box.focus(); box.click(); } catch(e){}
            try {
              var button = box.contentWindow && box.contentWindow.document && box.contentWindow.document.querySelector("input[type='checkbox']");
              if (button) button.click();
            } catch(e) { /* cross-origin expected for Turnstile */ }
          }
          if (window.AndroidBridge && AndroidBridge.onCaptcha) AndroidBridge.onCaptcha('need');
        } else if (window.__vfHadChallenge) {
          if (window.AndroidBridge && AndroidBridge.onCaptcha) AndroidBridge.onCaptcha('solved');
          window.__vfHadChallenge = false;
        }
      } catch(e) {}
    }, 2000);
  } catch(e) {}
})();
"""

        /**
         * Hide SerienStream site chrome; leave a black stage so only the captcha/gate is visible.
         * Without this the user only sees the episode page and never finds the Turnstile.
         */
        private const val GATE_MODE_JS = """
(function(){
  try {
    if (document.getElementById('vf-gate-css')) return;
    var style=document.createElement('style');
    style.id='vf-gate-css';
    style.textContent=[
      'html,body{background:#000!important;overflow:hidden!important;}',
      'nav,.navbar,.top-nav,.sidebar,.footer,footer,header,.breadcrumb,.seriesBanner,',
      '.hosterSiteTitle,.hosterSiteDescription,.hosterSiteVideo + *,',
      '#footer,.ads,.ad-banner,#cookie-consent,.cookie-consent,.cc-window,',
      '.fc-consent-root,.alert,.news,.comments,#comments,.row.mt-5,',
      '.col-md-4,.spin-container,.cf-turnstile-response{display:none!important;}',
      /* Keep player / gate host visible */
      '.hosterSiteVideo,.hosterSiteVideo *,#player,#player-container,.player-wrap,',
      '#playerPrepareModal,#playerPrepareModal *,.cf-turnstile,#player-prepare-turnstile,',
      'iframe[src*="challenges.cloudflare"],iframe[src*="turnstile"]{',
      '  display:block!important; visibility:visible!important; opacity:1!important;',
      '  pointer-events:auto!important;',
      '}',
      '#playerPrepareModal, .player-prepare-modal, .modal.show, .modal.in {',
      '  display:flex!important; align-items:center!important; justify-content:center!important;',
      '  position:fixed!important; inset:0!important; z-index:2147483646!important;',
      '  background:rgba(0,0,0,.92)!important; margin:0!important; padding:24px!important;',
      '  max-width:none!important; width:100%!important; height:100%!important;',
      '}',
      '#playerPrepareModal .modal-dialog, #playerPrepareModal .modal-content {',
      '  margin:auto!important; max-width:92vw!important; background:#111!important;',
      '  color:#fff!important; border:0!important; box-shadow:none!important;',
      '}',
      '.cf-turnstile, #player-prepare-turnstile, iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"] {',
      '  transform:scale(1.45)!important; transform-origin:center center!important;',
      '  margin:28px auto!important;',
      '}'
    ].join('');
    document.documentElement.appendChild(style);
    // Observe late-injected Turnstile / prepare modal
    try {
      if (!window.__vfGateObs) {
        window.__vfGateObs = new MutationObserver(function(){
          var modal=document.getElementById('playerPrepareModal') ||
            document.querySelector('.cf-turnstile, #player-prepare-turnstile, iframe[src*="challenges.cloudflare"]');
          if (modal && window.AndroidBridge && AndroidBridge.onCaptcha) {
            AndroidBridge.onCaptcha('need');
          }
        });
        window.__vfGateObs.observe(document.documentElement,{subtree:true,childList:true,attributes:true});
      }
    } catch(e) {}
  } catch(e) {}
})();
"""

        /** Enlarge/center captcha UI and focus the Turnstile iframe for TV D-pad. */
        private const val CAPTCHA_FOCUS_JS = """
(function(){
  try {
    // Ensure gate CSS is present
    if (!document.getElementById('vf-gate-css') && window.__vfNeedGate !== false) {
      /* GATE_MODE_JS is injected separately */
    }
    var modal=document.getElementById('playerPrepareModal') ||
      document.querySelector('.player-prepare-modal, .modal.show, .cf-turnstile, #player-prepare-turnstile');
    if (modal) {
      try {
        modal.style.display='flex';
        modal.style.visibility='visible';
        modal.style.opacity='1';
        modal.classList.add('show');
        modal.classList.remove('hidden','fade');
        modal.removeAttribute('aria-hidden');
        modal.scrollIntoView({block:'center'});
      } catch(e){}
      if (window.AndroidBridge && AndroidBridge.onCaptcha) AndroidBridge.onCaptcha('need');
    }
    var frame=document.querySelector('#player-prepare-turnstile iframe, .cf-turnstile iframe, iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"]');
    if (frame) {
      try { frame.setAttribute('tabindex','0'); frame.focus(); } catch(e){}
      try { frame.scrollIntoView({block:'center'}); } catch(e){}
    }
  } catch(e) {}
})();
"""

        /** Best-effort activate Turnstile checkbox / host confirm button for OK key. */
        private const val CAPTCHA_CLICK_JS = """
(function(){
  try {
    function fire(el){
      if (!el) return false;
      try { el.focus(); } catch(e){}
      try {
        var r=el.getBoundingClientRect();
        var x=r.left+Math.min(30, Math.max(8,r.width/2));
        var y=r.top+Math.min(30, Math.max(8,r.height/2));
        ['pointerdown','mousedown','mouseup','click'].forEach(function(type){
          el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y}));
        });
      } catch(e) {
        try { el.click(); } catch(e2){}
      }
      return true;
    }
    // darkryh-style classic CF challenge button
    var simple=document.querySelector("#challenge-stage > div > input[type='button'], #challenge-stage input[type='button'], input[type='button'].big-button");
    if (simple) fire(simple);
    var frame=document.querySelector('#player-prepare-turnstile iframe, .cf-turnstile iframe, iframe[src*="challenges.cloudflare"], iframe[src*="turnstile"], div.hcaptcha-box > iframe');
    if (frame) { fire(frame); }
    var box=document.querySelector('.cf-turnstile, #player-prepare-turnstile, [data-sitekey], #challenge-form');
    if (box) fire(box);
    var btn=document.querySelector('#playerPrepareModal button, .player-prepare-modal button, button[type="submit"]');
    if (btn) fire(btn);
    var mark=document.querySelector('#playerPrepareModal input[type="checkbox"], .cf-turnstile input');
    if (mark) fire(mark);
  } catch(e) {}
})();
"""
    }
}
