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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamvault.tv.VerflixedApp
import com.streamvault.tv.data.catalog.StreamKind
import com.streamvault.tv.data.catalog.StreamLanguage
import com.streamvault.tv.data.db.StreamCacheEntity
import com.streamvault.tv.data.model.Episode
import com.streamvault.tv.data.model.Series
import com.streamvault.tv.databinding.ActivityPlayerBinding
import com.streamvault.tv.ui.util.FocusFx
import com.streamvault.tv.util.toVfMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Playback strategy for SerienStream-like hosts:
 * - Never load `/r?t=` play-blobs as top-level WebView URLs (iframe-only; redirects home).
 * - Always prefer the episode watch page; captcha/gate stays usable inside the page.
 * - Intercept real `.m3u8` / HLS CDN requests and hand off to ExoPlayer.
 * - Mode bar (HLS / Web) stays above the WebView so D-pad switching works.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {
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
    private val handler = Handler(Looper.getMainLooper())

    private val progressTick = object : Runnable {
        override fun run() {
            persistProgress(forceCompleted = false)
            maybeShowNext()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val resolveTimeout = Runnable {
        if (!handedOffToExo && !allowEmbeddedFallback) {
            if (isMoviePlayback()) {
                showPlayerError("[VF-302] Film-Stream timeout – kein Web-Player.")
                return@Runnable
            }
            val page = playbackPageUrl()
            if (!page.isNullOrBlank()) {
                binding.resolveStatus.visibility = View.VISIBLE
                binding.resolveStatus.text = "Stream wird vorbereitet…"
                allowEmbeddedFallback = true
                startWebResolver(page, keepVisible = true)
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

        FocusFx.bindScale(binding.btnRetryHls, 1.06f)
        FocusFx.bindScale(binding.btnUseWebPlayer, 1.06f)
        FocusFx.bindScale(binding.btnPlayNext, 1.06f)

        binding.btnPlayNext.setOnClickListener { playNext(auto = false) }
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
            allowEmbeddedFallback = true
            handedOffToExo = false
            val page = playbackPageUrl()
            if (!page.isNullOrBlank()) {
                startWebResolver(page, keepVisible = true)
            } else {
                showPlayerError("Keine Episode-Seite gefunden")
            }
        }

        setupWebPlayer()
        setupPlayerView()
        binding.playerLoading.visibility = View.VISIBLE
        binding.playerError.visibility = View.GONE
        binding.playerErrorPanel.visibility = View.GONE
        showModeBar(false)
        // Language switching is detail/settings only — never in the player.
        binding.btnLangToggle.visibility = View.GONE

        lifecycleScope.launch {
            val repo = (application as VerflixedApp).container.catalog
            runCatching {
                val s = repo.getSeries(seriesId, enrich = false)
                val ep = s.flatEpisodes().find { it.id == episodeId }
                    ?: error("[VF-203] Episode nicht gefunden")
                playReferer = ep.streamPageUrl
                    ?: s.detailPath
                    ?: (application as VerflixedApp).container.prefs.activeBaseUrl()
                val url = repo.resolveStream(ep)
                val progress = repo.getProgress(ep.id)
                val resume = progress?.takeIf { !it.completed }?.positionMs ?: 0L
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
        binding.webPlayer.webViewClient = object : WebViewClient() {
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!usingWebPlayer || handedOffToExo) return
                if (!url.isNullOrBlank() && !StreamKind.isPlayBlobUrl(url)) {
                    playReferer = url
                }
                binding.playerLoading.visibility = View.GONE
                if (!url.isNullOrBlank() && (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url))) {
                    maybeClaimVoe(url)
                }
                view?.evaluateJavascript(playerBootstrapJs(), null)
                view?.evaluateJavascript(IFRAME_VOE_WATCH_JS, null)
                view?.evaluateJavascript(
                    "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})();",
                ) { htmlJson ->
                    val html = unescapeJsString(htmlJson)
                    Regex(
                        """https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""",
                        RegexOption.IGNORE_CASE,
                    ).find(html)?.value?.let { maybeCaptureMedia(it) }
                    Regex(
                        """https?://[^"'\\\s<>]+/e/[a-zA-Z0-9]+""",
                        RegexOption.IGNORE_CASE,
                    ).findAll(html).map { it.value }.forEach { maybeClaimVoe(it) }
                    // Decode VOE payload from current page HTML if present
                    if (!url.isNullOrBlank() && (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url))) {
                        val fromHtml = runCatching {
                            (application as VerflixedApp).container.voeExtractor.extractSourceFromHtml(html)
                        }.getOrNull()
                        if (!fromHtml.isNullOrBlank()) maybeCaptureMedia(fromHtml)
                    }
                }
                // After episode page + cookies exist, retry native VOE/HLS claim (no SerienStream UI).
                if (url != null && StreamKind.isEpisodeWatchPage(url)) {
                    retryNativeClaimAfterCookies()
                }
                showModeBar(false)
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
        if (handedOffToExo || allowEmbeddedFallback) return
        if (StreamKind.isPlayBlobUrl(url)) return
        if (StreamKind.isVoePlayerUrl(url) || StreamKind.isVoeEmbedPath(url)) {
            maybeClaimVoe(url)
            return
        }
        if (!StreamKind.isDirectMediaUrl(url) && !looksLikeVoeHls(url)) return
        handler.post {
            if (handedOffToExo || allowEmbeddedFallback) return@post
            if (lastMediaUrl == url) return@post
            lastMediaUrl = url
            binding.resolveStatus.visibility = View.VISIBLE
            binding.resolveStatus.text = "Starte Wiedergabe…"
            showModeBar(false)
            handOffToExoPlayer(url, force = false)
        }
    }

    private var voeClaimInFlight: String? = null

    private fun maybeClaimVoe(voeUrl: String) {
        if (handedOffToExo || allowEmbeddedFallback) return
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
                handOffToExoPlayer(hls, force = false)
            } else {
                // Series only: VOE embed WebView claim. Movies never use WebView.
                if (isMoviePlayback()) {
                    showPlayerError("[VF-302] Film-Hoster ohne direkten Stream – kein Web-Player.")
                    return@launch
                }
                voeClaimInFlight = null
                if (!usingWebPlayer || binding.webPlayer.url?.let { StreamKind.isEpisodeWatchPage(it) } == true) {
                    handler.post {
                        if (handedOffToExo) return@post
                        binding.resolveStatus.text = "Stream vorbereiten…"
                        playReferer = voeUrl
                        startWebResolver(voeUrl, keepVisible = false)
                    }
                }
            }
        }
    }

    private fun retryNativeClaimAfterCookies() {
        val ep = episode ?: return
        if (handedOffToExo || allowEmbeddedFallback) return
        lifecycleScope.launch {
            val url = runCatching {
                (application as VerflixedApp).container.catalog.resolveStream(ep)
            }.getOrNull() ?: return@launch
            when {
                StreamKind.isDirectMediaUrl(url) -> handOffToExoPlayer(url, force = false)
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
        exoRetryUsed = false
        voeClaimInFlight = null
        resolvingProbeUrl = url
        lastMediaUrl = url.takeIf { StreamKind.isDirectMediaUrl(it) || looksLikeSignedMedia(url) }
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
                        startWebResolver(url, keepVisible = false)
                    }
                }
            }
            else -> startWebResolver(url, keepVisible = false)
        }
        handler.post(progressTick)
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
        // ExoPlayer custom controls (all native streams)
        if (binding.playerView.visibility == View.VISIBLE &&
            binding.playerView.isControllerFullyVisible
        ) {
            binding.playerView.hideController()
            return true
        }
        // WebView history only while actively using web player (series fallback)
        if (usingWebPlayer &&
            binding.webPlayer.visibility == View.VISIBLE &&
            binding.webPlayer.canGoBack()
        ) {
            binding.webPlayer.goBack()
            return true
        }
        return false
    }

    /** Back: dismiss controls first; require a second Back within 2s to leave the stream. */
    private fun handlePlaybackBack() {
        // Deduplicate OnBackPressedCallback + onKeyDown on some Fire OS builds
        val now = System.currentTimeMillis()
        if (now - lastBackHandledAt < 60L) return
        lastBackHandledAt = now
        if (consumeBackForControls()) return
        if (now - lastBackExitAt < 2_000L) {
            finish()
            return
        }
        lastBackExitAt = now
        Toast.makeText(this, "Nochmal Zurück zum Beenden", Toast.LENGTH_SHORT).show()
        // Do not force-show Exo controls — that fights "hide first" on the next Back.
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

    private fun startWebResolver(url: String, keepVisible: Boolean) {
        if (isMoviePlayback()) {
            showPlayerError("[VF-302] Kein Web-Player für Filme – nur direkter Stream.")
            return
        }
        val target = normalizePlaybackUrl(url, episode)
        usingWebPlayer = true
        player?.release()
        player = null
        binding.playerView.visibility = View.GONE
        binding.playerView.player = null
        binding.webPlayer.visibility = View.VISIBLE
        binding.playerChrome.bringToFront()
        binding.playerLoading.visibility = if (keepVisible) View.GONE else View.VISIBLE
        binding.resolveStatus.visibility = View.VISIBLE
        binding.resolveStatus.text = when {
            keepVisible -> "Web-Player aktiv"
            StreamKind.isVoePlayerUrl(target) || StreamKind.isVoeEmbedPath(target) ->
                "VOE laden → m3u8 claimen…"
            else -> "Episode laden → VOE/HLS claimen…"
        }
        binding.nextEpisodeBanner.visibility = View.GONE
        hideError()
        if (keepVisible) {
            showModeBar(false)
            handedOffToExo = false
        } else {
            showModeBar(false)
        }

        val headers = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
        val ref = playReferer ?: (application as VerflixedApp).container.prefs.activeBaseUrl()
        if (!ref.isNullOrBlank()) {
            headers["Referer"] = if (ref.endsWith("/")) ref else "$ref/"
        }
        try {
            if (binding.webPlayer.url == target) {
                binding.webPlayer.reload()
            } else {
                binding.webPlayer.loadUrl(target, headers)
            }
        } catch (t: Throwable) {
            showPlayerError("[VF-303] ${t.message}")
        }

        if (!keepVisible) {
            handler.postDelayed(resolveTimeout, 45_000L)
        } else {
            handler.removeCallbacks(resolveTimeout)
        }

        // Mark in-progress so continue-watching works even if HLS claim fails.
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
        handler.removeCallbacks(resolveTimeout)
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
                app.container.db.streams().upsert(
                    StreamCacheEntity(
                        profileId = profileId,
                        episodeId = ep.id,
                        seriesId = ep.seriesId,
                        streamUrl = mediaUrl,
                        kind = StreamKind.streamKindLabel(mediaUrl),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun startExoPlayer(url: String, resumeMs: Long) {
        usingWebPlayer = false
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
                    showModeBar(false)
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
                    allowEmbeddedFallback = true
                    binding.resolveStatus.text = "Stream wird aufgelöst…"
                    startWebResolver(page, keepVisible = true)
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

    private fun playerBootstrapJs(): String {
        val pref = currentPreferredLang()
        val preferDe = pref == StreamLanguage.DE
        return """
(function(){
  try {
    var preferDe = ${if (preferDe) "true" else "false"};
    var hide = ['.ads','.ad-banner','#cookie-consent','.cookie-consent','.cc-window','.fc-consent-root','.navbar','.top-nav'];
    hide.forEach(function(s){
      document.querySelectorAll(s).forEach(function(n){ try { n.style.display='none'; } catch(e){} });
    });

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
      var buttons = Array.prototype.slice.call(document.querySelectorAll(
        'button.link-box, .link-box, [data-play-url], [data-provider-name], a[data-play-url], .hosterSiteVideoButton'
      ));
      var scored = buttons.map(function(b){
        var p = ((b.getAttribute('data-provider-name')||'') + ' ' + (b.textContent||'')).toLowerCase();
        var l = ((b.getAttribute('data-language-label')||'') + ' ' + (b.getAttribute('data-language')||'') + ' ' +
                 (b.getAttribute('data-language-id')||'') + ' ' + (b.getAttribute('data-lang-key')||'') + ' ' +
                 (b.getAttribute('title')||''));
        // inherit from nearest heading
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
      var frame = document.getElementById('player-iframe') || document.querySelector('iframe[src*="/r?t="], iframe[src*="voe"], iframe[src*="/e/"], iframe');
      if (!frame) return;
      try { frame.scrollIntoView({block:'center'}); } catch(e) {}
      try {
        var src = frame.src || '';
        if (src && window.AndroidBridge && AndroidBridge.onVoeUrl) {
          if (/voe|\/e\//i.test(src)) AndroidBridge.onVoeUrl(src);
        }
        var doc = frame.contentDocument || frame.contentWindow.document;
        if (doc) {
          var v = doc.querySelector('video');
          if (v) { try { v.muted = true; v.play(); } catch(e) {}
            if (v.currentSrc && AndroidBridge.onMediaUrl) AndroidBridge.onMediaUrl(v.currentSrc);
          }
          var pb = doc.querySelector('.vjs-big-play-button, button.play, .play-button');
          if (pb) { try { pb.click(); } catch(e) {} }
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
    }

    private fun maybeShowNext() {
        val s = series ?: return
        val ep = episode ?: return
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0L) return
        val left = dur - p.currentPosition
        val next = (application as VerflixedApp).container.catalog.nextEpisode(s, ep) ?: return
        if (left in 1..35_000L) {
            binding.nextEpisodeBanner.visibility = View.VISIBLE
            binding.nextEpisodeText.text = "Nächste: S${next.seasonNumber}E${next.number} · ${next.title}"
        } else if (left > 35_000L) {
            binding.nextEpisodeBanner.visibility = View.GONE
        }
    }

    private fun playNext(auto: Boolean) {
        val s = series ?: return
        val ep = episode ?: return
        val next = (application as VerflixedApp).container.catalog.nextEpisode(s, ep) ?: run {
            if (!auto) Toast.makeText(this, "Keine nächste Episode", Toast.LENGTH_SHORT).show()
            return
        }
        persistProgress(forceCompleted = true)
        episode = next
        handedOffToExo = false
        allowEmbeddedFallback = false
        exoRetryUsed = false
        lastMediaUrl = null
        binding.nextEpisodeBanner.visibility = View.GONE
        binding.playerLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val repo = (application as VerflixedApp).container.catalog
            runCatching { repo.resolveStream(next) }
                .onSuccess { url ->
                    playReferer = next.streamPageUrl ?: playReferer
                    startPlayback(normalizePlaybackUrl(url, next), 0L)
                }
                .onFailure { showPlayerError(it.toVfMessage()) }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handlePlaybackBack()
            return true
        }
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                if (usingWebPlayer) {
                    binding.playerView.showController()
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
                if (p != null && binding.playerView.isControllerFullyVisible) {
                    p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration.coerceAtLeast(0L)))
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> {
                if (p != null && binding.playerView.isControllerFullyVisible) {
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
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(resolveTimeout)
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
    }
}
