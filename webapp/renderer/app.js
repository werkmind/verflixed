(() => {
  const $ = (id) => document.getElementById(id);
  const BROWSE_PAGE_SIZE = 24;
  const PROGRESS_SAVE_MS = 15000;
  const SEEK_STEP_S = 10;

  const state = {
    mediaKind: localStorage.getItem("vf_media_kind") || "series",
    homeMode: "library",
    lastContentMode: "library",
    seriesBaseUrl:
      localStorage.getItem("vf_base_series") ||
      localStorage.getItem("vf_base") ||
      "https://aniworld.to",
    moviesBaseUrl: localStorage.getItem("vf_base_movies") || "https://filmpalast.to",
    baseUrl: "",
    series: [],
    movies: [],
    seriesRows: [],
    moviesRows: [],
    rows: [],
    seriesLoaded: false,
    moviesLoaded: false,
    current: null,
    season: 1,
    lastPlay: null,
    hls: null,
    artInflight: new Map(),
    progressTimer: null,
    playerReady: false,
    languagePages: {},
    activePageLang: "de",
    controlsVisible: false,
    lastBackExitAt: 0,
    lastAppBackAt: 0,
    warmingStreams: false,
    warmToken: 0,
    nextPromptVisible: false,
    nextPromptDismissed: false,
    nextAutoAtMs: 0,
    advancingToNext: false,
    activeSkip: null,
    dismissedSkipTypes: new Set(),
    seriesLoading: false,
    moviesLoading: false,
    seriesLoadPromise: null,
    moviesLoadPromise: null,
  };
  state.baseUrl =
    state.mediaKind === "movie" ? state.moviesBaseUrl : state.seriesBaseUrl;

  const placeholder =
    "data:image/svg+xml," +
    encodeURIComponent(
      `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360">
        <rect fill="#151922" width="100%" height="100%"/>
        <text x="50%" y="50%" fill="#6b7385" font-family="sans-serif" font-size="22" text-anchor="middle" dy=".3em">Verflixed</text>
      </svg>`,
    );

  function setStatus(el, msg) {
    if (el) el.textContent = msg || "";
  }

  function contentViews() {
    return ["library", "series", "movies", "search", "profiles", "detail", "player"];
  }

  function showView(name) {
    contentViews().forEach((v) => {
      const node = $(`view-${v}`);
      if (node) node.classList.toggle("active", v === name);
    });
    if (name !== "player") {
      state.controlsVisible = false;
      state.lastBackExitAt = 0;
    }
  }

  /** @deprecated alias — prefer showView / setHomeMode */
  function showTab(name) {
    if (name === "browse") {
      setHomeMode(state.lastContentMode === "movies" ? "movies" : state.lastContentMode === "series" ? "series" : "library");
      return;
    }
    if (["library", "series", "movies", "search", "profiles"].includes(name)) {
      setHomeMode(name);
      return;
    }
    showView(name);
  }

  function paintNavActive(mode) {
    document.querySelectorAll("[data-nav]").forEach((el) => {
      el.classList.toggle("active", el.dataset.nav === mode);
    });
  }

  function applyChromePrefs() {
    const app = $("app");
    if (!app) return;
    const nav = window.VfProfiles?.navLayout?.() || "sidebar";
    const lib = window.VfProfiles?.libraryView?.() || "tiles";
    app.classList.toggle("nav-sidebar", nav === "sidebar");
    app.classList.toggle("nav-topbar", nav === "topbar");
    app.classList.toggle("lib-cards", lib === "cards");
    app.classList.toggle("lib-tiles", lib !== "cards");
  }

  function applyUiScale() {
    const pct = window.VfProfiles?.uiScale?.() || 100;
    document.documentElement.style.setProperty("zoom", pct === 100 ? "" : `${pct / 100}`);
  }

  function renderCategoryChips() {
    const host = $("categoryChips");
    if (!host || !window.ContentGate) return;
    const blocked = new Set(window.VfProfiles?.blockedGenres?.() || []);
    host.innerHTML = "";
    for (const g of window.ContentGate.GENRES) {
      const chip = document.createElement("button");
      chip.type = "button";
      const enabled = !blocked.has(g.id);
      chip.className = "chip category-chip" + (enabled ? " active" : " off");
      chip.textContent = g.label;
      chip.title = enabled ? `${g.label} wird geladen` : `${g.label} ist ausgeblendet`;
      chip.onclick = () => {
        window.VfProfiles.toggleBlockedGenre(g.id);
        renderCategoryChips();
        refreshBrowseFromState();
        const q = $("searchInput")?.value || "";
        if (q) runSearch(q);
      };
      host.appendChild(chip);
    }
  }

  function paintSettingsToggles() {
    const navBtn = $("btnToggleNavLayout");
    if (navBtn) {
      const nav = window.VfProfiles?.navLayout?.() || "sidebar";
      navBtn.textContent = nav === "topbar" ? "Layout: Topbar" : "Layout: Sidebar";
    }
    const libBtn = $("btnToggleLibraryView");
    if (libBtn) {
      const lib = window.VfProfiles?.libraryView?.() || "tiles";
      libBtn.textContent = lib === "cards" ? "Bibliothek: Cards" : "Bibliothek: Kacheln";
    }
    const zoomBtn = $("btnToggleZoom");
    if (zoomBtn) {
      zoomBtn.textContent = `Zoom: ${window.VfProfiles?.uiScale?.() || 100}%`;
    }
    renderCategoryChips();
    paintLanguagePrefButton();
  }

  function setHomeMode(mode, opts = {}) {
    const m = mode === "update" ? "update" : mode;
    if (m === "update") {
      runUpdateCheck();
      return;
    }
    if (["library", "series", "movies"].includes(m)) {
      state.lastContentMode = m;
      state.homeMode = m;
      if (m === "series") state.mediaKind = "series";
      if (m === "movies") state.mediaKind = "movie";
      localStorage.setItem("vf_media_kind", state.mediaKind);
      syncBaseUrlInputs();
      updateSearchPlaceholder();
    } else if (m === "search" || m === "profiles") {
      state.homeMode = m;
    } else {
      state.homeMode = "library";
    }
    paintNavActive(state.homeMode);
    showView(state.homeMode);
    if (state.homeMode === "library") {
      refreshLibrary();
    } else if (state.homeMode === "series") {
      ensureSeriesCatalog(opts.force);
    } else if (state.homeMode === "movies") {
      ensureMoviesCatalog(opts.force);
    } else if (state.homeMode === "profiles") {
      renderProfileGrid();
      paintSettingsToggles();
    } else if (state.homeMode === "search") {
      const q = $("searchInput")?.value || "";
      if (q.trim()) scheduleSearch(q);
    }
  }

  function preferredLang() {
    return window.StreamLanguage?.normalize?.(window.VfProfiles?.streamLanguage?.()) || "de";
  }

  function paintLanguagePrefButton() {
    const btn = $("btnToggleLanguagePref");
    if (!btn) return;
    btn.textContent = `Profil-Ton: ${window.StreamLanguage?.label?.(preferredLang()) || "Deutsch"}`;
  }

  async function runUpdateCheck() {
    setHomeMode("profiles");
    const status = $("updateStatus");
    if (status) status.textContent = "Prüfe GitHub Releases…";
    try {
      const m = await window.VfUpdates.check();
      if (!m) {
        if (status) status.textContent = "Kein Update-Manifest gefunden.";
        return;
      }
      const newer = window.VfUpdates.isNewer(m);
      if (!newer) {
        if (status) {
          status.textContent = `Aktuell (${window.VfUpdates.currentVersion()}). Latest: ${m.versionName}`;
        }
        return;
      }
      if (status) {
        status.innerHTML = `Update ${escapeHtml(m.versionName)} verfügbar · <a href="${escapeHtml(m.webappUrl || m.htmlUrl)}" target="_blank" rel="noopener">Webapp</a> · <a href="${escapeHtml(m.apkUrl || m.htmlUrl)}" target="_blank" rel="noopener">Fire TV APK</a>`;
      }
    } catch (e) {
      if (status) status.textContent = `Update-Check fehlgeschlagen: ${e.message || e}`;
    }
  }

  function paintLanguageButton() {
    const btn = $("btnLanguage");
    if (!btn) return;
    const pages = state.languagePages || {};
    const keys = Object.keys(pages).filter((k) => pages[k]);
    if (keys.length < 2) {
      btn.hidden = true;
      return;
    }
    btn.hidden = false;
    btn.textContent = window.StreamLanguage?.shortLabel?.(state.activePageLang) || "DE";
    btn.title = `Ton: ${window.StreamLanguage?.label?.(state.activePageLang) || ""}`;
  }

  async function discoverTitleLanguages(series, html, pageUrl) {
    const pages = {};
    if (isMovieItem(series) || isMovieItem({ detailPath: pageUrl })) {
      const title = series.title || "";
      const current = window.FilmParser.detectPageLanguage(html, title, pageUrl);
      pages[current] = pageUrl;
      for (const want of ["de", "en"]) {
        if (pages[want]) continue;
        const alt = await findMovieLanguagePage(pageUrl, html, title, want);
        if (alt) pages[want] = alt;
      }
      return pages;
    }
    // Series: labeled hosters / headings on episode page
    const hosters = parseEpisodeHosters(html, pageUrl);
    const langs = new Set();
    hosters.forEach((h) => {
      if (h.language) langs.add(window.StreamLanguage.normalize(h.language));
    });
    const doc = new DOMParser().parseFromString(html, "text/html");
    doc
      .querySelectorAll("h3, h4, h5, .hosterSiteTitle, .language, [data-language-label]")
      .forEach((el) => {
        const t = `${el.textContent || ""} ${el.getAttribute("data-language-label") || ""}`.toLowerCase();
        if (t.includes("deutsch") || t.includes("german")) langs.add("de");
        if (t.includes("englisch") || t.includes("english")) langs.add("en");
      });
    [...langs].forEach((l) => {
      pages[l] = pageUrl;
    });
    return pages;
  }

  async function findMovieLanguagePage(pageUrl, html, title, wantedLang) {
    const want = window.StreamLanguage.normalize(wantedLang);
    const current = window.FilmParser.detectPageLanguage(html, title, pageUrl);
    if (current === want) return pageUrl;
    for (const cand of window.FilmParser.siblingLanguageUrls(pageUrl, current, html)) {
      try {
        const { text, finalUrl } = await getText(cand);
        if (!text || text.length < 2000) continue;
        const url = finalUrl || cand;
        const lang = window.FilmParser.detectPageLanguage(
          text,
          window.FilmParser.cleanTitle(
            new DOMParser()
              .parseFromString(text, "text/html")
              .querySelector("article.detail h2, h2.bgDark, h2")?.textContent || "",
          ),
          url,
        );
        const hosters = window.FilmParser.parseHosters(text, url, want);
        if (lang === want && hosters.length) return url;
      } catch (_) {}
    }
    const cleaned = window.StreamLanguage.cleanTitleForSearch(title) || title;
    const queries = [
      cleaned,
      cleaned.replace(/&/g, " ").replace(/\s+/g, " ").trim(),
      cleaned.split(/\s+/).slice(0, 2).join(" "),
      cleaned.split(/\s+/)[0],
    ].filter((q) => q && q.length >= 2);
    const base = state.moviesBaseUrl || activeBase();
    for (const query of queries) {
      try {
        const hits =
          (await window.SiteSearch?.searchSite?.(
            { getText },
            base,
            query,
            { mediaKind: "movie" },
          )) || [];
        for (const hit of hits) {
          const hitUrl = hit.detailPath;
          if (!hitUrl) continue;
          const hitLang = window.FilmParser.languageFromMovieHit(hit.title, hitUrl);
          if (hitLang !== want) continue;
          const a = window.StreamLanguage.cleanTitleForSearch(hit.title).toLowerCase();
          const b = cleaned.toLowerCase();
          const close =
            a.includes(b.slice(0, 8)) ||
            b.includes(a.slice(0, 8)) ||
            a === b ||
            a.split(/\s+/)[0] === b.split(/\s+/)[0];
          if (!close) continue;
          const { text, finalUrl } = await getText(hitUrl);
          const url = finalUrl || hitUrl;
          if (text && window.FilmParser.parseHosters(text, url, want).length) return url;
        }
      } catch (_) {}
    }
    return null;
  }

  async function refreshAvailableLanguages(series, html, pageUrl) {
    state.languagePages = {};
    paintLanguageButton();
    try {
      const pages = await discoverTitleLanguages(series, html, pageUrl);
      state.languagePages = pages;
      const pref = preferredLang();
      state.activePageLang =
        (pref in pages && pref) ||
        Object.keys(pages).find((k) => pages[k] === pageUrl) ||
        Object.keys(pages)[0] ||
        "de";
      paintLanguageButton();
      if (series) {
        series.availableLanguages = Object.keys(pages);
        series.languagePages = pages;
      }
    } catch (_) {
      paintLanguageButton();
    }
  }

  async function toggleStreamLanguage() {
    const pages = state.languagePages || {};
    const keys = Object.keys(pages).filter((k) => pages[k]);
    if (keys.length < 2) {
      paintLanguageButton();
      return;
    }
    const next =
      keys.find((k) => k !== state.activePageLang) ||
      window.StreamLanguage.toggle(state.activePageLang);
    const nextPage = pages[next];
    window.VfProfiles.setStreamLanguage(next);
    paintLanguagePrefButton();
    const s = state.current;
    if (s?.seasons) {
      s.seasons.forEach((season) =>
        (season.episodes || []).forEach((ep) =>
          window.VfProfiles.clearCachedStream(ep.id),
        ),
      );
    }
    state.activePageLang = next;
    paintLanguageButton();
    if (isMovieItem(s) && nextPage) {
      setStatus($("detailMeta"), `Ton: ${window.StreamLanguage.label(next)} – lade Version…`);
      await openDetail({
        ...s,
        detailPath: nextPage,
        mediaKind: "movie",
        languagePages: pages,
      });
    } else {
      setStatus(
        $("detailMeta"),
        [
          s?.year || null,
          `Ton: ${window.StreamLanguage.label(next)}`,
          s?.seasons?.length ? `${s.seasons.length} Staffeln` : null,
        ]
          .filter(Boolean)
          .join(" · "),
      );
    }
  }

  function showBrandGate(status) {
    const gate = $("playerBrandGate");
    if (!gate) return;
    gate.classList.remove("hidden");
    gate.setAttribute("aria-hidden", "false");
    if ($("playerBrandStatus")) $("playerBrandStatus").textContent = status || "";
    $("playerOverlay")?.classList.add("hidden");
    const canvas = $("playerIntro");
    window.VfIntro?.stop?.(canvas);
    window.VfIntro?.play?.(canvas, { compact: true });
    window.VfIntro?.sting?.();
  }

  function hideBrandGate() {
    const gate = $("playerBrandGate");
    if (!gate) return;
    gate.classList.add("hidden");
    gate.setAttribute("aria-hidden", "true");
    window.VfIntro?.stop?.($("playerIntro"));
    if ($("playerBrandStatus")) $("playerBrandStatus").textContent = "";
  }

  function leavePlayer() {
    stopProgressTimer();
    stopHls();
    const video = $("video");
    if (video) {
      try {
        video.pause();
        video.removeAttribute("src");
        video.load();
      } catch (_) {}
    }
    state.playerReady = false;
    state.controlsVisible = false;
    state.lastBackExitAt = 0;
    hideNextPrompt();
    hideBrandGate();
    showTvControls(false);
    if (state.current) showView("detail");
    else setHomeMode(state.lastContentMode || "library");
  }

  /** First Back hides controls; second within 2s leaves the player. */
  function handlePlaybackBack() {
    if (!$("view-player")?.classList.contains("active")) return false;
    const tc = $("tvControls");
    if (state.controlsVisible || (tc && !tc.classList.contains("hidden"))) {
      showTvControls(false);
      state.controlsVisible = false;
      return true;
    }
    const now = Date.now();
    if (now - state.lastBackExitAt < 2000) {
      leavePlayer();
      return true;
    }
    state.lastBackExitAt = now;
    if ($("playerStatus")) {
      $("playerStatus").textContent = "Nochmal Zurück zum Beenden";
      $("playerOverlay")?.classList.remove("hidden");
      setTimeout(() => {
        if (Date.now() - state.lastBackExitAt >= 1900) {
          $("playerOverlay")?.classList.add("hidden");
          if ($("playerStatus")) $("playerStatus").textContent = "";
        }
      }, 2000);
    }
    return true;
  }

  function activeBase() {
    return state.mediaKind === "movie" ? state.moviesBaseUrl : state.seriesBaseUrl;
  }

  function syncBaseUrlInputs() {
    if ($("seriesBaseUrl")) $("seriesBaseUrl").value = state.seriesBaseUrl;
    if ($("moviesBaseUrl")) $("moviesBaseUrl").value = state.moviesBaseUrl;
    const active = activeBase();
    state.baseUrl = active;
    if ($("baseUrl")) $("baseUrl").value = active;
    if ($("baseUrlSettings")) $("baseUrlSettings").value = active;
  }

  function persistBasesFromInputs() {
    const seriesIn = ($("seriesBaseUrl")?.value || "").trim().replace(/\/$/, "");
    const moviesIn = ($("moviesBaseUrl")?.value || "").trim().replace(/\/$/, "");
    const activeIn = ($("baseUrl")?.value || "").trim().replace(/\/$/, "");

    if (seriesIn) state.seriesBaseUrl = seriesIn;
    if (moviesIn) state.moviesBaseUrl = moviesIn;

    if (activeIn) {
      if (state.mediaKind === "movie") state.moviesBaseUrl = activeIn;
      else state.seriesBaseUrl = activeIn;
    }

    localStorage.setItem("vf_base_series", state.seriesBaseUrl);
    localStorage.setItem("vf_base_movies", state.moviesBaseUrl);
    localStorage.setItem("vf_base", state.seriesBaseUrl);
    state.baseUrl = activeBase();
    syncBaseUrlInputs();
  }

  function updateKindButtons() {
    // Kind switcher removed — Serien/Filme are dedicated nav pages.
  }

  function updateSearchPlaceholder() {
    const input = $("searchInput");
    if (!input) return;
    input.placeholder = "Bibliothek, Serien & Filme suchen…";
  }

  function setMediaKind(kind) {
    const k = kind === "movie" ? "movie" : "series";
    state.mediaKind = k;
    localStorage.setItem("vf_media_kind", k);
    state.baseUrl = activeBase();
    updateSearchPlaceholder();
    syncBaseUrlInputs();
  }

  function isMovieItem(item) {
    if (item?.mediaKind === "movie") return true;
    if (item?.mediaKind === "series") return false;
    if (state.mediaKind === "movie") return true;
    const path = String(item?.detailPath || "");
    if (!path) return false;
    // Series roots (AniWorld / SerienStream) are never movies
    if (/\/(?:serie|series|anime\/stream)\b/i.test(path)) return false;
    // Filmpalast-style: /stream/slug without a series prefix
    return /\/stream\/[^/?#]+/i.test(path);
  }

  function syncProfileChip() {
    const p = window.VfProfiles.activeProfile();
    const el = $("profileChipName");
    if (el && p) el.textContent = p.name;
    const letter = $("sideAvatarLetter");
    if (letter) letter.textContent = (p?.name || "V").charAt(0).toUpperCase();
  }

  function formatTime(sec) {
    if (!sec || !Number.isFinite(sec)) return "0:00";
    const s = Math.max(0, Math.floor(sec));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const r = s % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}`;
    return `${m}:${String(r).padStart(2, "0")}`;
  }

  async function getText(url, headers = {}) {
    if (window.verflixed?.getText) {
      return window.verflixed.getText(url, headers);
    }
    const r = await fetch(url, { headers });
    return { status: r.status, finalUrl: r.url, text: await r.text() };
  }

  async function postText(url, body, headers = {}) {
    if (window.verflixed?.postText) {
      return window.verflixed.postText(url, body, headers);
    }
    const r = await fetch(url, { method: "POST", headers, body });
    return { status: r.status, finalUrl: r.url, text: await r.text() };
  }

  async function extractVoeHls(embedUrl, referer) {
    if (window.verflixed?.extractVoeHls) {
      return window.verflixed.extractVoeHls(embedUrl, referer);
    }
    throw new Error("VOE extractor nur in der Desktop-App verfügbar");
  }

  function tileArt(s) {
    const cached = window.VfProfiles.cachedArt(s.id);
    const poster =
      window.SiteImages.preferJpeg(s.posterUrl) ||
      window.SiteImages.preferJpeg(cached?.posterUrl);
    const backdrop =
      window.SiteImages.preferJpeg(s.backdropUrl) ||
      window.SiteImages.preferJpeg(cached?.backdropUrl) ||
      poster;
    return backdrop || poster || null;
  }

  function bindImg(img, url, fallback = null) {
    const src = window.SiteImages.preferJpeg(url) || fallback || placeholder;
    img.loading = "lazy";
    img.decoding = "async";
    img.referrerPolicy = "no-referrer-when-downgrade";
    img.src = src;
    img.onerror = () => {
      if (fallback && img.src !== fallback && !fallback.startsWith("data:")) {
        img.onerror = () => {
          img.onerror = null;
          img.src = placeholder;
        };
        img.src = fallback;
      } else {
        img.onerror = null;
        img.src = placeholder;
      }
    };
  }

  function storeArt(seriesId, posterUrl, backdropUrl) {
    if (!posterUrl && !backdropUrl) return;
    window.VfProfiles.cacheArt(seriesId, posterUrl, backdropUrl);
  }

  async function resolveArt(series) {
    if (tileArt(series)) {
      const art = {
        posterUrl:
          window.SiteImages.preferJpeg(series.posterUrl) ||
          window.SiteImages.preferJpeg(window.VfProfiles.cachedArt(series.id)?.posterUrl),
        backdropUrl:
          window.SiteImages.preferJpeg(series.backdropUrl || series.posterUrl) ||
          window.SiteImages.preferJpeg(window.VfProfiles.cachedArt(series.id)?.backdropUrl),
      };
      storeArt(series.id, art.posterUrl, art.backdropUrl);
      return { ...series, ...art };
    }
    const cached = window.VfProfiles.cachedArt(series.id);
    if (cached?.posterUrl || cached?.backdropUrl) {
      return {
        ...series,
        posterUrl: window.SiteImages.preferJpeg(cached.posterUrl),
        backdropUrl: window.SiteImages.preferJpeg(cached.backdropUrl || cached.posterUrl),
      };
    }
    if (state.artInflight.has(series.id)) return state.artInflight.get(series.id);

    const job = (async () => {
      try {
        const detail = series.detailPath;
        if (!detail) return series;
        const { text, finalUrl } = await getText(detail);
        const parsed = isMovieItem(series)
          ? window.FilmParser.parseMovieDetail(text, finalUrl || detail, series.id)
          : window.CatalogParser.parseSeriesDetail(
              text,
              finalUrl || detail,
              series.id,
            );
        let art = {
          posterUrl: window.SiteImages.preferJpeg(parsed.posterUrl),
          backdropUrl: window.SiteImages.preferJpeg(parsed.backdropUrl || parsed.posterUrl),
        };
        if (!art.posterUrl && !art.backdropUrl) {
          const basic = await window.TvMaze.enrichBasic({
            ...series,
            title: parsed.title || series.title,
          });
          art = {
            posterUrl: window.SiteImages.preferJpeg(basic.posterUrl),
            backdropUrl: window.SiteImages.preferJpeg(basic.backdropUrl || basic.posterUrl),
          };
        }
        if (art.posterUrl || art.backdropUrl) {
          storeArt(series.id, art.posterUrl, art.backdropUrl);
        }
        return { ...series, title: series.title, ...art };
      } catch {
        return series;
      } finally {
        state.artInflight.delete(series.id);
      }
    })();
    state.artInflight.set(series.id, job);
    return job;
  }

  function posterCard(s, opts = {}) {
    const el = document.createElement("button");
    el.type = "button";
    const libView = window.VfProfiles?.libraryView?.() || "tiles";
    const viewClass = libView === "cards" ? " lib-cards" : " lib-tiles";
    el.className = "card" + viewClass + (opts.continuePct ? " card-continue" : "");
    const pct = opts.continuePct ? Math.min(100, Math.round(opts.continuePct)) : 0;
    el.innerHTML = `
      <div class="card-media">
        <img alt="" />
        <div class="card-veil"></div>
        ${pct > 0 ? `<div class="card-progress"><span style="width:${pct}%"></span></div>` : ""}
        <div class="cap">${escapeHtml(s.title)}</div>
      </div>`;
    const img = el.querySelector("img");
    bindImg(img, tileArt(s));
    el.addEventListener("click", () => openDetail(s));
    if (!tileArt(s)) {
      resolveArt(s).then((filled) => {
        if (tileArt(filled)) bindImg(img, tileArt(filled));
        const cap = el.querySelector(".cap");
        if (cap && filled.title) cap.textContent = filled.title;
        Object.assign(s, filled);
      });
    }
    return el;
  }

  function renderFeaturedHero(host, item) {
    if (!host || !item) return;
    const s = item.series || item;
    const art = s.backdropUrl || s.posterUrl || tileArt(s) || placeholder;
    const hero = document.createElement("article");
    hero.className = "home-hero";
    hero.tabIndex = 0;
    const meta = [s.year, s.mediaKind === "movie" ? "Film" : "Serie", (s.genres || []).slice(0, 2).join(" · ")]
      .filter(Boolean)
      .join("  ·  ");
    hero.innerHTML = `
      <img alt="" />
      <div class="home-hero-scrim"></div>
      <div class="home-hero-copy">
        <h2 class="home-hero-title">${escapeHtml(s.title || "")}</h2>
        <p class="home-hero-meta">${escapeHtml(meta)}</p>
        ${s.overview ? `<p class="home-hero-overview">${escapeHtml(s.overview)}</p>` : ""}
        <div class="home-hero-actions">
          <button type="button" class="btn primary" data-act="play">Play</button>
          <button type="button" class="btn ghost" data-act="info">Mehr Infos</button>
        </div>
      </div>`;
    bindImg(hero.querySelector("img"), art, placeholder);
    const open = () => openDetail(s);
    const play = async (e) => {
      e?.stopPropagation?.();
      await openDetail(s);
      const ep = state.current && window.VfProfiles.continueForSeries(state.current);
      if (ep) playEpisode(ep);
    };
    hero.addEventListener("click", (e) => {
      if (e.target.closest("button")) return;
      open();
    });
    hero.querySelector("[data-act=play]").onclick = play;
    hero.querySelector("[data-act=info]").onclick = (e) => {
      e.stopPropagation();
      open();
    };
    host.appendChild(hero);
  }

  function renderRowsInto(host, rows) {
    if (!host) return;
    host.innerHTML = "";
    const blocked = window.VfProfiles?.blockedGenres?.() || [];
    let delay = 0;
    let featured = false;
    for (let g of rows) {
      if (!g.items?.length) continue;
      g = { ...g, items: window.ContentGate ? window.ContentGate.filterList(g.items, blocked) : g.items };
      if (!g.items.length) continue;
      if (!featured) {
        renderFeaturedHero(host, g.items[0]);
        featured = true;
      }
      const wrap = document.createElement("section");
      wrap.className = "shelf";
      wrap.style.animationDelay = `${delay}ms`;
      delay += 70;
      wrap.innerHTML = `<div class="shelf-head"><h2 class="row-title">${escapeHtml(g.title)}</h2><span class="shelf-count">${g.items.length}</span></div>`;
      const scroller = document.createElement("div");
      scroller.className = "scroller";
      for (const item of g.items) {
        const s = item.series || item;
        const p = item._continue || s._continue;
        let continuePct = 0;
        if (p?.durationMs > 0) continuePct = (p.positionMs / p.durationMs) * 100;
        scroller.appendChild(posterCard(s, { continuePct }));
      }
      wrap.appendChild(scroller);
      host.appendChild(wrap);
    }
  }

  function renderBrowseRows(rows) {
    const host =
      state.homeMode === "movies"
        ? $("moviesRows")
        : state.homeMode === "series"
          ? $("seriesRows")
          : $("libraryRows");
    renderRowsInto(host, rows);
  }

  function catalogIndex() {
    const map = new Map();
    for (const s of state.series || []) if (s?.id) map.set(s.id, s);
    for (const m of state.movies || []) if (m?.id) map.set(m.id, m);
    return map;
  }

  function recentlyWatchedRow(continueIdSet) {
    const map = window.VfProfiles.progressMap();
    const index = catalogIndex();
    const favs = window.VfProfiles.listFavorites();
    const favById = new Map(favs.map((f) => [f.id, f]));
    const ordered = Object.values(map)
      .filter((p) => p.completed)
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
      .map((p) => p.seriesId)
      .filter((id, i, arr) => arr.indexOf(id) === i)
      .filter((id) => !continueIdSet.has(id))
      .map((id) => favById.get(id) || index.get(id))
      .filter((s) => s?.detailPath)
      .slice(0, 12);
    return ordered;
  }

  function usefulGenres(genres) {
    const noise = new Set(["deutsch", "englisch", "german", "english"]);
    return new Set(
      (genres || [])
        .map((g) => String(g).toLowerCase().trim())
        .filter((k) => k.length > 2 && !noise.has(k) && !k.includes("demnächst") && !k.includes("uhr")),
    );
  }

  /** Seeds for „Weil du X geschaut hast" — recently actually watched titles. */
  function becauseYouWatchedSeeds(indexById) {
    const map = window.VfProfiles.progressMap();
    const byId = new Map(indexById);
    for (const f of window.VfProfiles.listFavorites()) {
      if (!byId.has(f.id)) byId.set(f.id, f);
    }
    return Object.values(map)
      .filter((p) => p.completed || (p.positionMs || 0) > 30000)
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
      .map((p) => p.seriesId)
      .filter((id, i, arr) => arr.indexOf(id) === i)
      .map((id) => byId.get(id))
      .filter((s) => s && usefulGenres(s.genres).size > 0)
      .slice(0, 2);
  }

  function becauseYouWatched(catalog, seed, excludeIds) {
    const seedGenres = usefulGenres(seed.genres);
    if (!seedGenres.size) return [];
    const seedKind = seed.mediaKind === "movie" ? "movie" : "series";
    return (catalog || [])
      .filter((s) => s?.id && s.id !== seed.id && !excludeIds.has(s.id))
      .filter((s) => (s.mediaKind === "movie" ? "movie" : "series") === seedKind)
      .map((s) => {
        let score = 0;
        for (const g of usefulGenres(s.genres)) if (seedGenres.has(g)) score++;
        return { s, score };
      })
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .map((x) => x.s)
      .filter((s, i, arr) => arr.findIndex((x) => x.id === s.id) === i)
      .slice(0, 16);
  }

  function becauseYouWatchedTitle(seed) {
    const name = String(seed.title || "diesen Titel").trim();
    const short = name.length > 36 ? name.slice(0, 34).trimEnd() + "…" : name;
    return `Weil du ${short} geschaut hast`;
  }

  function buildLibraryRows() {
    const index = [...(state.series || []), ...(state.movies || [])];
    const continueItems = window.VfProfiles.continueRow(index).slice(0, 8);
    const continueIdSet = new Set(continueItems.map((s) => s.id));
    const seriesFavs = window.VfProfiles.listFavorites("series").sort((a, b) =>
      String(a.title || "").localeCompare(String(b.title || ""), "de"),
    );
    const movieFavs = window.VfProfiles.listFavorites("movie").sort((a, b) =>
      String(a.title || "").localeCompare(String(b.title || ""), "de"),
    );
    const az = [...seriesFavs, ...movieFavs]
      .filter((s, i, arr) => arr.findIndex((x) => x.id === s.id) === i)
      .sort((a, b) => String(a.title || "").localeCompare(String(b.title || ""), "de"));
    const recent = recentlyWatchedRow(continueIdSet);
    const rows = [];
    if (continueItems.length) {
      rows.push({
        title: "Weiterschauen",
        items: continueItems.map((s) => ({ series: s, _continue: s._continue })),
      });
    }
    // „Weil du X geschaut hast" — one local row per recently watched seed.
    const exclude = new Set([
      ...seriesFavs.map((s) => s.id),
      ...movieFavs.map((s) => s.id),
      ...continueIdSet,
    ]);
    const indexById = new Map(index.map((s) => [s.id, s]));
    for (const seed of becauseYouWatchedSeeds(indexById)) {
      const similar = becauseYouWatched(index, seed, exclude);
      if (similar.length < 4) continue;
      rows.push({ title: becauseYouWatchedTitle(seed), items: similar });
      similar.forEach((s) => exclude.add(s.id));
    }
    if (seriesFavs.length) rows.push({ title: "Meine Serien", items: seriesFavs });
    if (movieFavs.length) rows.push({ title: "Meine Filme", items: movieFavs });
    if (az.length >= 8) rows.push({ title: "A–Z", items: az });
    if (recent.length) rows.push({ title: "Zuletzt gesehen", items: recent });
    return rows;
  }

  function buildSeriesBrowseRows() {
    const browse = (state.series || []).slice(0, BROWSE_PAGE_SIZE);
    const rows = [];
    if (browse.length) rows.push({ title: "Browse", items: browse });
    for (const shelf of state.seriesRows || []) {
      if (shelf.title === "Browse") continue;
      rows.push(shelf);
    }
    return rows;
  }

  function buildMoviesBrowseRows() {
    const rows = [];
    if (state.freshMovies?.length) {
      rows.push({ title: "Neu erschienen", items: state.freshMovies });
    }
    for (const shelf of state.moviesRows || []) rows.push(shelf);
    if (!rows.length && (state.movies || []).length) {
      rows.push({ title: "Browse", items: state.movies.slice(0, BROWSE_PAGE_SIZE) });
    }
    return rows;
  }

  // ---- "Neu erschienen": rank newest platform additions by REAL release year (TMDb) ----
  // Built-in public scraper key — same Plex/Kodi model as the Fire TV app.
  const TMDB_APP_KEY = "af3a53eb387d57fc935e9128468b1899";

  function tmdbYearCache() {
    try {
      return JSON.parse(localStorage.getItem("vf_tmdb_years") || "{}");
    } catch (_) {
      return {};
    }
  }

  async function movieReleaseYear(title) {
    const key = String(title || "").trim().toLowerCase();
    if (!key) return null;
    const cache = tmdbYearCache();
    if (key in cache) return cache[key];
    let year = null;
    try {
      const url =
        `https://api.themoviedb.org/3/search/movie?api_key=${TMDB_APP_KEY}` +
        `&language=de-DE&query=${encodeURIComponent(key)}`;
      const body = await getText(url);
      const json = JSON.parse(body);
      const hit = (json?.results || [])[0];
      const date = hit?.release_date || "";
      const y = parseInt(date.slice(0, 4), 10);
      if (Number.isFinite(y) && y > 1900) year = y;
    } catch (_) {}
    cache[key] = year;
    try {
      localStorage.setItem("vf_tmdb_years", JSON.stringify(cache));
    } catch (_) {}
    return year;
  }

  let freshMoviesToken = 0;

  /** Resolve real release years for the newest additions, keep current/last year. */
  async function computeFreshMovies() {
    const token = ++freshMoviesToken;
    const candidates = (state.movies || []).slice(0, 44);
    if (!candidates.length) return;
    const nowYear = new Date().getFullYear();
    const dated = [];
    const queue = [...candidates];
    const workers = Array.from({ length: 6 }, async () => {
      while (queue.length) {
        const movie = queue.shift();
        const year = movie.year || (await movieReleaseYear(movie.title));
        if (year != null) dated.push({ movie, year });
      }
    });
    await Promise.all(workers);
    if (token !== freshMoviesToken) return;
    state.freshMovies = dated
      .filter((x) => x.year >= nowYear - 1)
      .sort(
        (a, b) =>
          b.year - a.year ||
          candidates.findIndex((c) => c.id === a.movie.id) -
            candidates.findIndex((c) => c.id === b.movie.id),
      )
      .map((x) => ({ ...x.movie, year: x.year }))
      .slice(0, 16);
    if (state.freshMovies.length && state.homeMode === "movies") {
      renderRowsInto($("moviesRows"), buildMoviesBrowseRows());
    }
  }

  /** Legacy combined builder — used when a caller still expects browse shelves. */
  function buildBrowseRows() {
    if (state.homeMode === "library") return buildLibraryRows();
    if (state.homeMode === "movies" || state.mediaKind === "movie") return buildMoviesBrowseRows();
    return buildSeriesBrowseRows();
  }

  function refreshLibrary() {
    const rows = buildLibraryRows();
    renderRowsInto($("libraryRows"), rows);
    const status = $("libraryStatus");
    if (!rows.length) {
      setStatus(status, "Noch nichts in der Bibliothek — füge Favoriten hinzu oder schaue etwas an.");
    } else {
      setStatus(status, "");
    }
  }

  function refreshBrowseFromState() {
    if (state.homeMode === "library") {
      refreshLibrary();
      return;
    }
    if (state.homeMode === "movies" || state.mediaKind === "movie") {
      renderRowsInto($("moviesRows"), buildMoviesBrowseRows());
      return;
    }
    if (state.homeMode === "series") {
      renderRowsInto($("seriesRows"), buildSeriesBrowseRows());
    }
  }

  function fillSkeleton(host, shelves = 3, tiles = 6) {
    if (!host) return;
    host.innerHTML = "";
    for (let i = 0; i < shelves; i++) {
      const shelf = document.createElement("div");
      shelf.className = "skeleton-shelf";
      shelf.innerHTML = `<div class="skeleton-title"></div><div class="skeleton-row"></div>`;
      const row = shelf.querySelector(".skeleton-row");
      for (let j = 0; j < tiles; j++) {
        const t = document.createElement("div");
        t.className = "skeleton-tile";
        row.appendChild(t);
      }
      host.appendChild(shelf);
    }
  }

  function showSkeleton(kind, show) {
    const sk =
      kind === "movie"
        ? $("moviesSkeleton")
        : kind === "library"
          ? $("librarySkeleton")
          : $("seriesSkeleton");
    const rows =
      kind === "movie"
        ? $("moviesRows")
        : kind === "library"
          ? $("libraryRows")
          : $("seriesRows");
    if (!sk) return;
    if (show) {
      fillSkeleton(sk);
      sk.classList.remove("hidden");
      sk.setAttribute("aria-hidden", "false");
      if (rows) rows.classList.add("hidden");
    } else {
      sk.classList.add("hidden");
      sk.setAttribute("aria-hidden", "true");
      sk.innerHTML = "";
      if (rows) rows.classList.remove("hidden");
    }
  }

  function renderProfileGrid() {
    const grid = $("profileGrid");
    if (!grid) return;
    grid.innerHTML = "";
    const active = window.VfProfiles.activeProfile();
    const profiles = window.VfProfiles.listProfiles();

    for (const p of profiles) {
      const card = document.createElement("button");
      card.type = "button";
      card.className = "profile-card" + (p.id === active.id ? " active" : "");
      card.innerHTML = `
        <div class="profile-avatar">${escapeHtml(p.name.charAt(0).toUpperCase())}</div>
        <div class="profile-name">${escapeHtml(p.name)}</div>
        ${profiles.length > 1 ? `<span class="profile-del" title="Löschen" data-del="${p.id}">✕</span>` : ""}`;
      card.addEventListener("click", (e) => {
        if (e.target.closest(".profile-del")) return;
        window.VfProfiles.switchProfile(p.id);
        syncProfileChip();
        applyChromePrefs();
        paintSettingsToggles();
        renderProfileGrid();
        refreshBrowseFromState();
        if (state.current) {
          updateDetailFavButton();
          updatePlayContinueButton();
          renderEpisodes();
        }
      });
      const del = card.querySelector(".profile-del");
      if (del) {
        del.addEventListener("click", (e) => {
          e.stopPropagation();
          if (!confirm(`Profil „${p.name}" löschen?`)) return;
          try {
            window.VfProfiles.deleteProfile(p.id);
            syncProfileChip();
            applyChromePrefs();
            paintSettingsToggles();
            renderProfileGrid();
            refreshBrowseFromState();
          } catch (err) {
            alert(err.message || err);
          }
        });
      }
      grid.appendChild(card);
    }
  }

  function seasonHeroArt(seasonNum) {
    if (!state.current) return null;
    const season = state.current.seasons.find((s) => s.number === seasonNum);
    const epStill = season?.episodes?.[0]?.stillUrl;
    if (epStill) return window.SiteImages.preferJpeg(epStill);
    return window.SiteImages.preferJpeg(
      state.current.backdropUrl || state.current.posterUrl,
    );
  }

  function applyDetailHero(seasonNum) {
    const hero = seasonHeroArt(seasonNum);
    const heroEl = $("detailHero");
    if (hero) heroEl.style.backgroundImage = `url("${hero}")`;
    else heroEl.style.backgroundImage = "";
    bindImg(
      $("detailPoster"),
      state.current?.posterUrl || tileArt(state.current) || hero,
      tileArt(state.current),
    );
  }

  function updateDetailFavButton() {
    const btn = $("btnToggleFav");
    if (!btn || !state.current) return;
    const fav = window.VfProfiles.isFavorite(state.current.id);
    btn.textContent = fav ? "✓ Meine Liste" : "＋ Meine Liste";
    btn.classList.toggle("fav-on", fav);
  }

  function updateSeasonWatchedButton() {
    const btn = $("btnSeasonWatched");
    if (!btn || !state.current) return;
    if (isMovieItem(state.current)) {
      const ep = (state.current.seasons || []).flatMap((s) => s.episodes || [])[0];
      const seen = !!(ep && window.VfProfiles.getProgress(ep.id)?.completed);
      btn.textContent = seen ? "Als ungesehen" : "Als gesehen";
      btn.hidden = false;
      return;
    }
    const eps =
      state.current.seasons.find((s) => s.number === state.season)?.episodes || [];
    const allSeen =
      eps.length > 0 && eps.every((ep) => window.VfProfiles.getProgress(ep.id)?.completed);
    btn.textContent = allSeen ? "Staffel ungesehen" : "Staffel als gesehen";
    btn.hidden = false;
  }

  function toggleSeasonWatched() {
    if (!state.current) return;
    if (isMovieItem(state.current)) {
      const ep = (state.current.seasons || []).flatMap((s) => s.episodes || [])[0];
      if (!ep) return;
      const seen = !!window.VfProfiles.getProgress(ep.id)?.completed;
      window.VfProfiles.setEpisodeWatched(ep, !seen);
    } else {
      const eps =
        state.current.seasons.find((s) => s.number === state.season)?.episodes || [];
      const allSeen =
        eps.length > 0 && eps.every((ep) => window.VfProfiles.getProgress(ep.id)?.completed);
      eps.forEach((ep) => window.VfProfiles.setEpisodeWatched(ep, !allSeen));
    }
    renderEpisodes();
    updateSeasonWatchedButton();
    updatePlayContinueButton();
    refreshBrowseFromState();
  }

  function updatePlayContinueButton() {
    const btn = $("btnPlayContinue");
    if (!btn || !state.current) return;
    if (state.current.mediaKind === "movie" || state.mediaKind === "movie") {
      const ep = window.VfProfiles.continueForSeries(state.current);
      const prog = ep ? window.VfProfiles.getProgress(ep.id) : null;
      if (prog && !prog.completed && prog.positionMs > 5000) {
        btn.textContent = "Weiter";
      } else {
        btn.textContent = "Play";
      }
      return;
    }
    const ep = window.VfProfiles.continueForSeries(state.current);
    if (!ep) {
      btn.textContent = "Play";
      return;
    }
    const prog = window.VfProfiles.getProgress(ep.id);
    if (prog && !prog.completed && prog.positionMs > 5000) {
      btn.textContent = `Weiter · S${ep.seasonNumber}E${ep.number}`;
    } else {
      btn.textContent = `Play · S${ep.seasonNumber}E${ep.number}`;
    }
  }

  async function openDetail(seriesLight) {
    showView("detail");
    state.warmingStreams = false;
    state.warmToken += 1;
    $("detailTitle").textContent = seriesLight.title;
    $("detailMeta").textContent = isMovieItem(seriesLight) ? "Lade Film…" : "Lade Staffeln…";
    $("detailOverview").textContent = "";
    bindImg($("detailPoster"), tileArt(seriesLight));
    $("detailHero").style.backgroundImage = "";
    $("seasonTabs").innerHTML = "";
    $("seasonTabs").style.display = "";
    $("episodeList").innerHTML = "";
    try {
      const { text, finalUrl } = await getText(seriesLight.detailPath);
      const pageUrl = finalUrl || seriesLight.detailPath;

      if (isMovieItem(seriesLight) || isMovieItem({ detailPath: pageUrl })) {
        let workingHtml = text;
        let workingUrl = pageUrl;
        const pref = preferredLang();
        let pageLang = window.FilmParser.detectPageLanguage(
          workingHtml,
          seriesLight.title || "",
          workingUrl,
        );
        if (pageLang !== pref) {
          const alt = await findMovieLanguagePage(
            workingUrl,
            workingHtml,
            seriesLight.title || "",
            pref,
          );
          if (alt && alt !== workingUrl) {
            try {
              const altRes = await getText(alt);
              if (altRes?.text && window.FilmParser.parseHosters(altRes.text, alt, pref).length) {
                workingHtml = altRes.text;
                workingUrl = altRes.finalUrl || alt;
                pageLang = window.FilmParser.detectPageLanguage(
                  workingHtml,
                  seriesLight.title || "",
                  workingUrl,
                );
              }
            } catch (_) {}
          }
        }

        let detailed = window.FilmParser.parseMovieDetail(
          workingHtml,
          workingUrl,
          seriesLight.id,
        );
        detailed.detailPath = workingUrl;
        detailed.posterUrl = detailed.posterUrl || seriesLight.posterUrl;
        detailed.backdropUrl = detailed.backdropUrl || seriesLight.backdropUrl;
        detailed.mediaKind = "movie";

        if (detailed.posterUrl || detailed.backdropUrl) {
          storeArt(
            detailed.id,
            window.SiteImages.preferJpeg(detailed.posterUrl),
            window.SiteImages.preferJpeg(detailed.backdropUrl || detailed.posterUrl),
          );
        }

        state.current = detailed;
        state.season = 1;
        state.activePageLang = pageLang;
        $("detailTitle").textContent = detailed.title;
        $("detailMeta").textContent = [
          detailed.year || null,
          detailed.runtime || null,
          window.StreamLanguage?.label?.(pageLang) || null,
          detailed.genres?.length
            ? detailed.genres.filter((g) => !/deutsch|englisch/i.test(g)).slice(0, 2).join(", ")
            : "Film",
        ]
          .filter(Boolean)
          .join(" · ");
        $("detailOverview").textContent = detailed.overview || "Keine Beschreibung";
        $("seasonTabs").innerHTML = "";
        $("seasonTabs").style.display = "none";
        if ($("episodeList")) $("episodeList").style.display = "none";
        applyDetailHero(state.season);
        updateDetailFavButton();
        updatePlayContinueButton();
        updateSeasonWatchedButton();
        await refreshAvailableLanguages(detailed, workingHtml, workingUrl);
        if (window.VfProfiles.isFavorite(detailed.id)) warmEpisodeStreamsLight(detailed);
        return;
      }

      let detailed = window.CatalogParser.parseSeriesDetail(
        text,
        pageUrl,
        seriesLight.id,
      );
      detailed.detailPath = pageUrl;
      detailed.posterUrl = detailed.posterUrl || seriesLight.posterUrl;
      detailed.backdropUrl = detailed.backdropUrl || seriesLight.backdropUrl;
      detailed.mediaKind = detailed.mediaKind || "series";

      await hydrateSeasons(detailed, text, detailed.detailPath);
      detailed = await window.TvMaze.enrich(detailed);

      if (detailed.posterUrl || detailed.backdropUrl) {
        storeArt(
          detailed.id,
          window.SiteImages.preferJpeg(detailed.posterUrl),
          window.SiteImages.preferJpeg(detailed.backdropUrl || detailed.posterUrl),
        );
      }

      state.current = detailed;
      state.season = detailed.seasons[0]?.number || 1;
      $("detailTitle").textContent = detailed.title;
      const epCount = detailed.seasons.reduce((n, s) => n + s.episodes.length, 0);
      $("detailMeta").textContent = [
        detailed.year || null,
        `Ton: ${window.StreamLanguage?.label?.(preferredLang()) || "Deutsch"}`,
        detailed.seasons.length ? `${detailed.seasons.length} Staffeln` : null,
        epCount ? `${epCount} Episoden` : null,
      ]
        .filter(Boolean)
        .join(" · ");
      $("detailOverview").textContent = detailed.overview || "Keine Beschreibung";
      $("seasonTabs").style.display = "";
      if ($("episodeList")) $("episodeList").style.display = "";
      applyDetailHero(state.season);
      updateDetailFavButton();
      updatePlayContinueButton();
      updateSeasonWatchedButton();
      renderSeasons();
      renderEpisodes();
      if (window.VfProfiles.isFavorite(detailed.id)) warmEpisodeStreamsLight(detailed);
      const firstEp = detailed.seasons[0]?.episodes?.[0];
      const probeUrl = firstEp?.streamPageUrl || detailed.detailPath;
      if (probeUrl) {
        try {
          const probe = probeUrl === pageUrl ? { text, finalUrl: pageUrl } : await getText(probeUrl);
          await refreshAvailableLanguages(detailed, probe.text || text, probe.finalUrl || probeUrl);
        } catch (_) {
          paintLanguageButton();
        }
      } else {
        paintLanguageButton();
      }
    } catch (e) {
      $("detailMeta").textContent = `Fehler: ${e.message || e}`;
    }
  }

  async function hydrateSeasons(series, rootHtml, rootUrl) {
    const discovered = window.CatalogParser.discoverSeasonUrls(rootHtml, rootUrl);
    const maxKnown = Math.max(
      1,
      ...series.seasons.map((s) => s.number),
      ...discovered.map(([n]) => n),
    );
    const urls = new Map(discovered);
    for (let n = 1; n <= maxKnown + 2; n++) {
      if (!urls.has(n)) {
        urls.set(n, `${series.detailPath.replace(/\/$/, "")}/staffel-${n}`);
      }
    }
    for (const [n, url] of [...urls.entries()].sort((a, b) => a[0] - b[0])) {
      if (n === 1 && series.seasons.some((s) => s.number === 1 && s.episodes.length > 0)) {
        const s1 = series.seasons.find((s) => s.number === 1);
        if (s1 && s1.episodes.length >= 3) continue;
      }
      try {
        const { text, finalUrl } = await getText(url);
        if (!text || text.length < 400) continue;
        if (/nicht gefunden|not found|404/i.test(text) && text.length < 5000) continue;
        const parsed = window.CatalogParser.parseSeriesDetail(
          text,
          finalUrl || url,
          series.id,
        );
        for (const season of parsed.seasons) {
          const existing = series.seasons.find((s) => s.number === season.number);
          if (!existing) series.seasons.push(season);
          else {
            for (const ep of season.episodes) {
              const hit = existing.episodes.find((e) => e.number === ep.number);
              if (!hit) existing.episodes.push(ep);
              else {
                if (/^Episode\b/i.test(hit.title) || /^\d+$/.test(hit.title)) hit.title = ep.title;
                hit.stillUrl = hit.stillUrl || ep.stillUrl;
                hit.streamPageUrl = hit.streamPageUrl || ep.streamPageUrl;
              }
            }
            existing.episodes.sort((a, b) => a.number - b.number);
          }
        }
        if (!series.posterUrl && parsed.posterUrl) series.posterUrl = parsed.posterUrl;
        if (!series.backdropUrl && parsed.backdropUrl) series.backdropUrl = parsed.backdropUrl;
        if ((!series.overview || series.overview.length < 40) && parsed.overview) {
          series.overview = parsed.overview;
        }
      } catch (_) {}
    }
    series.seasons.sort((a, b) => a.number - b.number);
  }

  function renderSeasons() {
    const tabs = $("seasonTabs");
    tabs.innerHTML = "";
    for (const s of state.current.seasons) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "chip" + (s.number === state.season ? " active" : "");
      b.textContent = `Staffel ${s.number}`;
      b.onclick = () => {
        state.season = s.number;
        renderSeasons();
        applyDetailHero(state.season);
        renderEpisodes();
      };
      tabs.appendChild(b);
    }
  }

  function renderEpisodes() {
    const list = $("episodeList");
    list.innerHTML = "";
    const eps =
      state.current.seasons.find((s) => s.number === state.season)?.episodes || [];
    if (!eps.length) {
      list.innerHTML = `<div class="status">Keine Episoden in Staffel ${state.season}</div>`;
      return;
    }
    for (const ep of eps) {
      const prog = window.VfProfiles.getProgress(ep.id);
      const watched = prog?.completed;
      const pct =
        prog && prog.durationMs > 0 && !watched
          ? Math.min(100, (prog.positionMs / prog.durationMs) * 100)
          : 0;
      const ready = !!window.VfProfiles.getCachedStream(ep.id, preferredLang());

      const row = document.createElement("div");
      row.className = "episode" + (watched ? " watched" : "");
      row.dataset.epid = ep.id;
      row.innerHTML = `
        <button type="button" class="ep-play" aria-label="Play">
          <img alt="" />
          <div class="epno">E${ep.number}</div>
        </button>
        <div class="epbody">
          <div class="eptitle">${escapeHtml(ep.title)}</div>
          <div class="epmeta">${escapeHtml(ep.overview || `S${ep.seasonNumber}E${ep.number}`)}</div>
          ${pct > 0 ? `<div class="ep-progress"><span style="width:${Math.round(pct)}%"></span></div>` : ""}
        </div>
        <span class="stream-ready-dot${ready ? " ready" : ""}" title="${ready ? "Stream bereit" : "Noch nicht gecacht"}" aria-hidden="true"></span>
        <button type="button" class="ep-watch ${watched ? "on" : ""}" title="Gesehen">${watched ? "✓" : "○"}</button>
        <button type="button" class="play-pill ep-go">Play</button>`;

      const img = row.querySelector(".ep-play img");
      bindImg(
        img,
        ep.stillUrl,
        state.current.backdropUrl || state.current.posterUrl || placeholder,
      );
      row.querySelector(".ep-play").onclick = () => playEpisode(ep);
      row.querySelector(".ep-go").onclick = () => playEpisode(ep);
      row.querySelector(".ep-watch").onclick = (e) => {
        e.stopPropagation();
        const wasWatched = prog?.completed;
        window.VfProfiles.setEpisodeWatched(ep, !wasWatched);
        renderEpisodes();
        updateSeasonWatchedButton();
        updatePlayContinueButton();
        refreshBrowseFromState();
      };
      list.appendChild(row);
    }
  }

  function renderCacheStatus(cached, total, status, label) {
    const el = $("cacheStatus");
    if (!el) return;
    if (!total && status !== "caching") {
      el.textContent = "";
      return;
    }
    const bit = label ? ` · ${label}` : "";
    if (status === "caching") {
      el.textContent = `◌  Wird vorbereitet · ${cached}/${total}${bit}`;
    } else if (status === "ready") {
      el.textContent = `●  Offline bereit · ${cached} Episoden`;
    } else if (status === "partial") {
      el.textContent = `◐  Teilweise bereit · ${cached}/${total}`;
    } else {
      el.textContent = total > 0 ? `◌  Cache · ${cached}/${total}` : "";
    }
  }

  /** Resolve episode streams to direct HLS/MP4 and cache them (favorite / context refresh). */
  async function warmEpisodeStreams(series, opts = {}) {
    if (!series) return;
    const forceRefresh = !!opts.forceRefresh;
    const seasonNumber = opts.seasonNumber ?? null;
    const episodeId = opts.episodeId ?? null;
    if (state.warmingStreams && !forceRefresh && !episodeId && seasonNumber == null) return;
    const token = ++state.warmToken;
    state.warmingStreams = true;
    const movieMode = isMovieItem(series);
    const all = (series.seasons || []).flatMap((s) => s.episodes || []);
    let targets = all.filter((ep) => ep?.streamPageUrl);
    if (episodeId) targets = targets.filter((ep) => ep.id === episodeId);
    else if (seasonNumber != null) targets = targets.filter((ep) => ep.seasonNumber === seasonNumber);
    if (!forceRefresh && !episodeId && seasonNumber == null) {
      targets = targets.filter((ep) => !window.VfProfiles.getCachedStream(ep.id, preferredLang()));
    }
    if (forceRefresh) {
      if (episodeId) window.VfProfiles.clearCachedStream(episodeId);
      else if (seasonNumber != null) {
        targets.forEach((ep) => window.VfProfiles.clearCachedStream(ep.id));
      } else {
        window.VfProfiles.clearSeriesStreams(series.id);
      }
    }
    const total = Math.max(targets.length, 1);
    let cached = window.VfProfiles.cachedEpisodeIds?.(series.id, preferredLang())?.length || 0;
    renderCacheStatus(cached, all.length || total, "caching");
    try {
      for (const ep of targets) {
        if (token !== state.warmToken) break;
        if (state.current?.id !== series.id && !opts.background) break;
        const label = `S${ep.seasonNumber}E${ep.number}`;
        renderCacheStatus(cached, all.length || total, "caching", label);
        try {
          const { text, finalUrl } = await getText(ep.streamPageUrl);
          const page = finalUrl || ep.streamPageUrl;
          const hosters = movieMode
            ? window.FilmParser?.parseHosters?.(text, page, preferredLang()) || []
            : parseEpisodeHosters(text, page);
          let got = null;
          if (window.verflixed?.resolveHostersToHls && hosters.length) {
            const resolved = await window.verflixed.resolveHostersToHls(hosters, page);
            if (resolved?.ok && resolved.hlsUrl) got = resolved.hlsUrl;
          }
          if (!got) {
            const voe =
              hosters.find((h) => /voe/i.test(h.provider || h.name || "") || /\/e\//.test(h.url || ""))?.url ||
              (text.match(/https?:\/\/[^'"\s]+\/e\/[a-zA-Z0-9]+/) || [])[0];
            if (voe) {
              try {
                const extracted = await extractVoeHls(voe, page);
                got =
                  typeof extracted === "string"
                    ? extracted
                    : extracted?.hls || extracted?.hlsUrl || extracted?.url || null;
              } catch (_) {}
            }
          }
          if (got && /\.m3u8|\.mp4/i.test(got)) {
            window.VfProfiles.cacheStream(ep.id, ep.seriesId || series.id, got, preferredLang());
            cached = window.VfProfiles.cachedEpisodeIds?.(series.id, preferredLang())?.length || cached + 1;
            if (state.current?.id === series.id && token === state.warmToken) renderEpisodes();
          }
        } catch (_) {}
      }
    } finally {
      if (token === state.warmToken) state.warmingStreams = false;
      const ready = window.VfProfiles.cachedEpisodeIds?.(series.id, preferredLang())?.length || 0;
      const tot = all.length || total;
      renderCacheStatus(
        ready,
        tot,
        ready >= tot && tot > 0 ? "ready" : ready > 0 ? "partial" : "partial",
      );
      if (state.current?.id === series.id) renderEpisodes();
    }
  }

  /** @deprecated light warm kept as alias */
  async function warmEpisodeStreamsLight(series) {
    return warmEpisodeStreams(series, { forceRefresh: false });
  }

  function allEpisodesOrdered() {
    if (!state.current?.seasons) return [];
    return state.current.seasons
      .flatMap((s) => s.episodes || [])
      .sort((a, b) => a.seasonNumber - b.seasonNumber || a.number - b.number);
  }

  function nextEpisodeAfter(ep) {
    const eps = allEpisodesOrdered();
    const i = eps.findIndex((e) => e.id === ep.id);
    return i >= 0 && i < eps.length - 1 ? eps[i + 1] : null;
  }

  function stopProgressTimer() {
    if (state.progressTimer) {
      clearInterval(state.progressTimer);
      state.progressTimer = null;
    }
  }

  function saveCurrentProgress(completed = false) {
    const video = $("video");
    const lp = state.lastPlay;
    if (!lp?.ep || !video) return;
    const dur = video.duration;
    if (!dur || !Number.isFinite(dur)) return;
    window.VfProfiles.saveProgress(lp.ep, video.currentTime * 1000, dur * 1000, completed);
    refreshBrowseFromState();
  }

  function startProgressTimer() {
    stopProgressTimer();
    state.progressTimer = setInterval(() => saveCurrentProgress(false), PROGRESS_SAVE_MS);
  }

  function updateSeekUi() {
    const video = $("video");
    const bar = $("seekBar");
    const tCur = $("tCur");
    const tDur = $("tDur");
    if (!video || !bar) return;
    const dur = video.duration;
    if (!dur || !Number.isFinite(dur)) {
      bar.value = 0;
      if (tCur) tCur.textContent = "0:00";
      if (tDur) tDur.textContent = "0:00";
      return;
    }
    bar.value = Math.round((video.currentTime / dur) * 1000);
    if (tCur) tCur.textContent = formatTime(video.currentTime);
    if (tDur) tDur.textContent = formatTime(dur);
  }

  function showTvControls(show) {
    const tc = $("tvControls");
    state.controlsVisible = !!show;
    if (tc) tc.classList.toggle("hidden", !show);
  }

  function bindPlayerUi() {
    const video = $("video");
    if (!video) return;

    video.controls = false;
    video.addEventListener("timeupdate", () => {
      updateSeekUi();
      maybeShowSkipSegment();
      maybeShowNext();
    });
    video.addEventListener("loadedmetadata", () => {
      updateSeekUi();
      state.activeSkip = null;
      state.dismissedSkipTypes = new Set();
      hideSkipSegment();
    });
    video.addEventListener("play", () => showTvControls(true));
    video.addEventListener("pause", () => updateSeekUi());
    video.addEventListener("ended", () => {
      saveCurrentProgress(true);
      stopProgressTimer();
      hideNextPrompt();
      hideSkipSegment();
      const next = state.lastPlay?.ep ? nextEpisodeAfter(state.lastPlay.ep) : null;
      if (next) {
        playNextEpisode(true);
      } else {
        $("playerStatus").textContent = "Fertig";
        showTvControls(true);
      }
    });

    $("btnPlayNext")?.addEventListener("click", () => playNextEpisode(false));
    $("btnSkipNext")?.addEventListener("click", () => dismissNextPrompt(true));
    $("btnSkipSegment")?.addEventListener("click", () => skipActiveSegment());
    $("btnSkipSegment")?.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        skipActiveSegment();
      }
    });

    const bar = $("seekBar");
    if (bar) {
      bar.addEventListener("input", () => {
        const dur = video.duration;
        if (!dur || !Number.isFinite(dur)) return;
        video.currentTime = (bar.value / 1000) * dur;
        updateSeekUi();
      });
    }

    const tc = $("tvControls");
    if (tc) {
      tc.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-act]");
        if (!btn) return;
        const act = btn.dataset.act;
        const dur = video.duration;
        if (act === "play") {
          if (video.paused) video.play().catch(() => {});
          else video.pause();
        } else if (act === "exit") {
          handlePlaybackBack();
        } else if (act === "rew" || act === "back") {
          video.currentTime = Math.max(0, video.currentTime - SEEK_STEP_S);
        } else if (act === "fwd") {
          if (dur) video.currentTime = Math.min(dur, video.currentTime + SEEK_STEP_S);
        } else if (act === "next") {
          playNextEpisode(false);
        }
        updateSeekUi();
      });
    }

    document.addEventListener("keydown", (e) => {
      const tag = (e.target?.tagName || "").toLowerCase();
      if (tag === "input" || tag === "textarea") return;

      // Home / detail: double Escape / Back to quit app (Electron)
      if (!$("view-player")?.classList.contains("active")) {
        if (e.key === "Escape" || e.key === "BrowserBack" || e.code === "BrowserBack") {
          if ($("view-detail")?.classList.contains("active")) {
            e.preventDefault();
            setHomeMode(state.lastContentMode || "library");
            return;
          }
          if ($("view-search")?.classList.contains("active") && state.homeMode === "search") {
            e.preventDefault();
            setHomeMode(state.lastContentMode || "library");
            return;
          }
          e.preventDefault();
          const now = Date.now();
          if (now - state.lastAppBackAt < 2000) {
            if (window.verflixed?.quit) window.verflixed.quit();
            else window.close?.();
            return;
          }
          state.lastAppBackAt = now;
          const st =
            $("libraryStatus") || $("seriesStatus") || $("moviesStatus");
          if (st) {
            const prev = st.textContent;
            st.textContent = "Nochmal Zurück zum Beenden";
            setTimeout(() => {
              if (st.textContent === "Nochmal Zurück zum Beenden") st.textContent = prev || "";
            }, 2000);
          }
          return;
        }
        return;
      }

      if (e.key === "Escape" || e.key === "BrowserBack" || e.code === "BrowserBack") {
        e.preventDefault();
        if (state.activeSkip && !$("btnSkipSegment")?.classList.contains("hidden")) {
          if (!state.dismissedSkipTypes) state.dismissedSkipTypes = new Set();
          state.dismissedSkipTypes.add(state.activeSkip.type);
          hideSkipSegment();
          return;
        }
        if (state.nextPromptVisible) {
          dismissNextPrompt(true);
          return;
        }
        handlePlaybackBack();
        return;
      }
      if (e.code === "Space" || e.key === "Enter") {
        if (state.nextPromptVisible && document.activeElement === $("btnPlayNext")) {
          e.preventDefault();
          playNextEpisode(false);
          return;
        }
        if (e.code === "Space") {
          e.preventDefault();
          if (video.paused) video.play().catch(() => {});
          else video.pause();
          showTvControls(true);
        }
      } else if (e.code === "ArrowLeft") {
        e.preventDefault();
        video.currentTime = Math.max(0, video.currentTime - SEEK_STEP_S);
        showTvControls(true);
      } else if (e.code === "ArrowRight") {
        e.preventDefault();
        const dur = video.duration;
        if (dur) video.currentTime = Math.min(dur, video.currentTime + SEEK_STEP_S);
        showTvControls(true);
      } else if (e.key === "n" || e.key === "N") {
        playNextEpisode(false);
      }
      updateSeekUi();
    });
  }

  async function playEpisode(ep) {
    showTab("player");
    stopHls();
    stopProgressTimer();
    state.playerReady = false;
    state.nextPromptDismissed = false;
    hideNextPrompt();
    showTvControls(false);
    const movieMode =
      state.mediaKind === "movie" ||
      state.current?.mediaKind === "movie" ||
      isMovieItem(state.current);
    $("playerTitle").textContent = movieMode
      ? state.current?.title || ep.title || ""
      : `${state.current?.title || ""} · S${ep.seasonNumber}E${ep.number}`;
    $("playerStatus").textContent = movieMode ? "Film laden…" : "Episode laden…";
    if (state.advancingToNext) {
      $("playerOverlay")?.classList.add("hidden");
    } else {
      showBrandGate(movieMode ? "Film wird vorbereitet…" : "Stream wird vorbereitet…");
    }
    state.lastPlay = { ep, series: state.current };

    try {
      const page = ep.streamPageUrl;
      if (!page) throw new Error(movieMode ? "Keine Film-URL" : "Keine Episode-URL");

      const cached = window.VfProfiles.getCachedStream(ep.id, preferredLang());
      if (cached) {
        $("playerStatus").textContent = "Wiedergabe startet…";
        await playHls(cached, page);
        return;
      }

      const { text, finalUrl } = await getText(page);
      const episodePage = finalUrl || page;
      const hosters = movieMode
        ? window.FilmParser?.parseHosters?.(text, episodePage, preferredLang()) || []
        : parseEpisodeHosters(text, episodePage);

      if (window.verflixed?.resolveHostersToHls && hosters.length) {
        $("playerStatus").textContent = "Hoster werden aufgelöst…";
        const resolved = await window.verflixed.resolveHostersToHls(
          hosters,
          episodePage,
        );
        if (resolved?.ok && resolved.hlsUrl) {
          $("playerStatus").textContent = "Wiedergabe startet…";
          window.VfProfiles.cacheStream(
            ep.id,
            ep.seriesId,
            resolved.hlsUrl,
            preferredLang(),
          );
          await playHls(resolved.hlsUrl, episodePage);
          return;
        }
      }

      if (!movieMode && window.verflixed?.resolveVoeFromEpisode) {
        $("playerStatus").textContent = "Stream wird vorbereitet…";
        try {
          const resolved = await window.verflixed.resolveVoeFromEpisode(page, {
            timeoutMs: 120000,
            allowShowForCaptcha: true,
          });
          if (resolved?.ok && resolved.voeUrl) {
            $("playerStatus").textContent = "Wiedergabe startet…";
            await playVoe(resolved.voeUrl, resolved.episodeUrl || page);
            return;
          }
        } catch (_) {}
      }

      // Manual VOE / Vidara fallback
      const ordered = [...hosters].sort((a, b) => (b.score || 0) - (a.score || 0));
      let lastErr = null;
      for (const h of ordered) {
        const name = h.provider || h.name || "";
        const url = h.url;
        if (!url) continue;
        try {
          if (/voe/i.test(name) || /voe\.sx/i.test(url) || /\/e\//.test(url)) {
            $("playerStatus").textContent = "VOE wird geladen…";
            await playVoe(url, episodePage);
            return;
          }
          if (
            /vidara|vidnest/i.test(name) ||
            /vidaraa?\.cc/i.test(url)
          ) {
            $("playerStatus").textContent = "Vidara wird geladen…";
            const hls = await resolveVidaraHlsManual(url, episodePage);
            if (hls) {
              await playHls(hls, url);
              return;
            }
          }
        } catch (e) {
          lastErr = e;
        }
      }

      let voeUrl = ordered.find((h) => /\/e\//.test(h.url))?.url || null;
      if (!voeUrl) {
        const any = text.match(/https?:\/\/[^'"\s]+\/e\/[a-zA-Z0-9]+/);
        if (any) voeUrl = any[0];
      }
      if (voeUrl) {
        await playVoe(voeUrl, episodePage);
        return;
      }
      throw lastErr || new Error("Kein Stream-Link verfügbar");
    } catch (e) {
      hideBrandGate();
      $("playerOverlay").classList.remove("hidden");
      $("playerStatus").textContent = `Fehler: ${e.message || e}`;
      showTvControls(false);
    }
  }

  async function resolveVidaraHlsManual(embedUrl, referer) {
    let filecode = "";
    let origin = "https://vidaraa.cc";
    try {
      const u = new URL(embedUrl);
      filecode = u.pathname.split("/").filter(Boolean).pop() || "";
      origin = u.origin;
    } catch {
      filecode = String(embedUrl).split("/").pop() || "";
    }
    if (!filecode) return null;
    const body = JSON.stringify({ filecode, device: "desktop" });
    const res = await postText(`${origin}/api/stream`, body, {
      "Content-Type": "application/json",
      Referer: embedUrl,
      Origin: origin,
      Accept: "application/json",
    });
    try {
      const data = JSON.parse(res.text || "{}");
      const hls = data.streaming_url || data.source || null;
      if (hls && /\.m3u8|mpegurl|\/hls\//i.test(hls)) return hls;
    } catch (_) {}
    return null;
  }

  function absUrl(base, href) {
    try {
      return new URL(href, base).toString();
    } catch {
      return href;
    }
  }

  function parseEpisodeHosters(html, pageUrl) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const hosters = [];
    for (const el of doc.querySelectorAll(
      "[data-play-url], [data-provider-name], a[href*='/redirect/'], a[href*='/leave/']",
    )) {
      const provider =
        el.getAttribute("data-provider-name") ||
        el.getAttribute("title") ||
        el.textContent.trim() ||
        "Hoster";
      const lang =
        el.getAttribute("data-language-label") ||
        el.getAttribute("data-lang-key") ||
        "";
      const playUrl = el.getAttribute("data-play-url") || el.getAttribute("href") || "";
      if (!playUrl) continue;
      hosters.push({
        provider,
        language: lang,
        url: absUrl(pageUrl, playUrl),
        score:
          (/voe/i.test(provider) ? 50 : 0) +
          (/firestream/i.test(provider) ? 40 : 0) +
          (window.StreamLanguage?.matchesPreferred?.(lang, preferredLang())
            ? 80
            : lang
              ? -20
              : 0),
      });
    }
    hosters.sort((a, b) => b.score - a.score);
    return hosters;
  }

  async function playVoe(voeUrl, referer) {
    $("playerStatus").textContent = "Stream wird geladen…";
    const result = await extractVoeHls(voeUrl, referer);
    if (!result?.ok || !result.hls) {
      throw new Error(result?.error || "Wiedergabe fehlgeschlagen");
    }
    $("playerStatus").textContent = "Starte Wiedergabe…";
    try {
      await playHls(result.hls, result.pageUrl || result.referer || voeUrl);
    } catch (e) {
      const mp4 = (result.mp4Fallbacks || [])[0];
      if (mp4) {
        $("playerStatus").textContent = "Alternative Quelle…";
        await playMp4(mp4);
      } else {
        throw e;
      }
    }
  }

  function hideNextPrompt() {
    state.nextPromptVisible = false;
    state.nextAutoAtMs = 0;
    $("nextEpBanner")?.classList.add("hidden");
  }

  function hideSkipSegment() {
    state.activeSkip = null;
    $("btnSkipSegment")?.classList.add("hidden");
  }

  function seriesIdForSkip() {
    return state.current?.id || state.lastPlay?.ep?.seriesId || "";
  }

  function maybeShowSkipSegment() {
    const video = $("video");
    const ep = state.lastPlay?.ep;
    if (!video || !ep || state.nextPromptVisible || isMovieItem(state.current)) {
      hideSkipSegment();
      return;
    }
    const dur = video.duration;
    if (!dur || !Number.isFinite(dur) || video.paused) {
      hideSkipSegment();
      return;
    }
    const durationMs = dur * 1000;
    const posMs = video.currentTime * 1000;
    const sid = seriesIdForSkip();
    const dismissed = state.dismissedSkipTypes || new Set();
    const intro = window.VfSkipMarks?.introSegment?.(sid, durationMs, ep.number || 1);
    const credits = window.VfSkipMarks?.creditsSegment?.(sid, durationMs);
    const hit =
      (intro && !dismissed.has("INTRO") && posMs >= intro.startMs && posMs < intro.endMs && intro) ||
      (credits &&
        !dismissed.has("CREDITS") &&
        posMs >= credits.startMs &&
        posMs < credits.endMs &&
        credits) ||
      null;
    if (!hit) {
      hideSkipSegment();
      return;
    }
    if (state.activeSkip?.type === hit.type && !$("btnSkipSegment")?.classList.contains("hidden")) {
      return;
    }
    state.activeSkip = hit;
    const btn = $("btnSkipSegment");
    if (btn) {
      btn.textContent = hit.label;
      btn.classList.remove("hidden");
    }
  }

  function skipActiveSegment() {
    const video = $("video");
    const seg = state.activeSkip;
    if (!video || !seg) return;
    const sid = seriesIdForSkip();
    if (!state.dismissedSkipTypes) state.dismissedSkipTypes = new Set();
    state.dismissedSkipTypes.add(seg.type);
    if ((seg.type === "INTRO" || seg.type === "RECAP") && sid) {
      window.VfSkipMarks?.recordIntroEnd?.(sid, seg.endMs);
    }
    const dur = video.duration || 0;
    const targetSec =
      seg.type === "CREDITS" ? dur : Math.min(dur || seg.endMs / 1000, seg.endMs / 1000);
    try {
      video.currentTime = Math.max(0, targetSec);
    } catch (_) {}
    hideSkipSegment();
    if (seg.type === "CREDITS") maybeShowNext();
  }

  function dismissNextPrompt(keepPlaying) {
    const video = $("video");
    const sid = seriesIdForSkip();
    if (video && sid && video.duration) {
      const leftMs = (video.duration - video.currentTime) * 1000;
      if (leftMs > 15_000) window.VfSkipMarks?.recordCreditsLeadAtLeast?.(sid, leftMs);
    }
    state.nextPromptDismissed = true;
    hideNextPrompt();
    if (keepPlaying && $("playerStatus")) {
      $("playerStatus").textContent = "Läuft bis zum Schluss";
      $("playerOverlay")?.classList.remove("hidden");
      setTimeout(() => {
        if (state.nextPromptDismissed) {
          $("playerOverlay")?.classList.add("hidden");
          if ($("playerStatus")) $("playerStatus").textContent = "";
        }
      }, 1600);
    }
  }

  function showNextPrompt(next) {
    if (!state.nextPromptVisible) {
      state.nextPromptVisible = true;
      state.nextAutoAtMs = Date.now() + 10_000;
      hideSkipSegment();
      const banner = $("nextEpBanner");
      banner?.classList.remove("hidden");
      if ($("nextEpTitle")) {
        $("nextEpTitle").textContent =
          `Als Nächstes: S${next.seasonNumber}E${next.number} · ${next.title || ""}`;
      }
      $("btnPlayNext")?.focus?.();
    }
    const remainSec = Math.max(0, Math.min(10, Math.ceil((state.nextAutoAtMs - Date.now()) / 1000)));
    if ($("nextEpCountdown")) {
      $("nextEpCountdown").textContent = `Automatisch in ${remainSec}s`;
    }
    if (remainSec <= 0 && !state.advancingToNext) {
      playNextEpisode(true);
    }
  }

  function maybeShowNext() {
    const video = $("video");
    const ep = state.lastPlay?.ep;
    if (!video || !ep || isMovieItem(state.current) || String(ep.id || "").endsWith("-movie")) {
      hideNextPrompt();
      return;
    }
    const dur = video.duration;
    if (!dur || !Number.isFinite(dur) || video.paused) return;
    const leftMs = (dur - video.currentTime) * 1000;
    const sid = seriesIdForSkip();
    const lead =
      window.VfSkipMarks?.nextPromptLeadMs?.(sid, dur * 1000) || 60_000;
    const next = nextEpisodeAfter(ep);
    if (!next) {
      hideNextPrompt();
      return;
    }
    if (state.nextPromptDismissed) {
      if (leftMs > lead) state.nextPromptDismissed = false;
      return;
    }
    if (state.activeSkip && state.activeSkip.type !== "CREDITS") {
      hideNextPrompt();
      return;
    }
    if (leftMs > 0 && leftMs <= lead) showNextPrompt(next);
    else if (leftMs > lead) hideNextPrompt();
  }

  async function playNextEpisode(auto) {
    if (state.advancingToNext) return;
    const ep = state.lastPlay?.ep;
    const next = ep ? nextEpisodeAfter(ep) : null;
    if (!next) {
      if (!auto && $("playerStatus")) $("playerStatus").textContent = "Keine nächste Episode";
      hideNextPrompt();
      return;
    }
    if (!auto) {
      const video = $("video");
      const sid = seriesIdForSkip();
      if (video && sid && video.duration && video.currentTime > 0) {
        const lead = Math.max(15_000, Math.min(8 * 60_000, (video.duration - video.currentTime) * 1000));
        window.VfSkipMarks?.recordCreditsLead?.(sid, lead);
      }
    }
    state.advancingToNext = true;
    saveCurrentProgress(true);
    hideNextPrompt();
    hideSkipSegment();
    state.nextPromptDismissed = false;
    state.dismissedSkipTypes = new Set();
    state.activeSkip = null;
    try {
      await playEpisode(next);
    } finally {
      state.advancingToNext = false;
    }
  }

  function askResumeChoice(ep) {
    return new Promise((resolve) => {
      const prog = window.VfProfiles.getProgress(ep.id);
      if (!prog || prog.completed || !(prog.positionMs > 5000) || !(prog.durationMs > 0)) {
        resolve("start");
        return;
      }
      const modal = $("resumeModal");
      const msg = $("resumeMessage");
      const t = formatTime(prog.positionMs / 1000);
      if (msg) {
        msg.textContent = `Fortschritt bei ${t}. Weiterschauen oder von vorn starten?`;
      }
      modal?.classList.remove("hidden");
      const cleanup = (choice) => {
        modal?.classList.add("hidden");
        $("btnResumeContinue")?.removeEventListener("click", onContinue);
        $("btnResumeRestart")?.removeEventListener("click", onRestart);
        resolve(choice);
      };
      const onContinue = () => cleanup("continue");
      const onRestart = () => cleanup("start");
      $("btnResumeContinue")?.addEventListener("click", onContinue);
      $("btnResumeRestart")?.addEventListener("click", onRestart);
      $("btnResumeContinue")?.focus?.();
    });
  }

  async function resumeFromProgress(video, ep) {
    if (!ep || !video) return;
    const choice = await askResumeChoice(ep);
    const prog = window.VfProfiles.getProgress(ep.id);
    if (choice === "continue" && prog && prog.positionMs > 5000) {
      try {
        video.currentTime = prog.positionMs / 1000;
      } catch (_) {}
    } else {
      try {
        video.currentTime = 0;
      } catch (_) {}
    }
  }

  async function playMp4(url) {
    stopHls();
    const video = $("video");
    $("playerOverlay").classList.add("hidden");
    video.src = url;
    await video.play().catch(() => {});
    hideBrandGate();
    await resumeFromProgress(video, state.lastPlay?.ep);
    state.playerReady = true;
    startProgressTimer();
    showTvControls(true);
    updateSeekUi();
    $("playerStatus").textContent = "";
  }

  async function playHls(url, referer) {
    const video = $("video");
    stopHls();
    $("playerOverlay").classList.add("hidden");

    if (window.Hls && window.Hls.isSupported()) {
      state.hls = new Hls({ enableWorker: true });
      try {
        if (window.verflixed?.getText) {
          const probe = await window.verflixed.getText(url, {
            Referer: referer || "https://voe.sx/",
            Origin: (() => {
              try {
                return new URL(referer || "https://voe.sx/").origin;
              } catch {
                return "https://voe.sx";
              }
            })(),
            Accept: "*/*",
          });
          if (probe.status >= 400 || !/#EXTM3U/i.test(probe.text || "")) {
            throw new Error(`Playlist HTTP ${probe.status}`);
          }
        }
      } catch (e) {
        $("playerStatus").textContent = "Playlist prüfen…";
      }

      state.hls.loadSource(url);
      state.hls.attachMedia(video);
      state.hls.on(Hls.Events.MANIFEST_PARSED, async () => {
        $("playerStatus").textContent = "";
        hideBrandGate();
        await video.play().catch(() => {});
        await resumeFromProgress(video, state.lastPlay?.ep);
        state.playerReady = true;
        startProgressTimer();
        showTvControls(true);
        updateSeekUi();
      });
      state.hls.on(Hls.Events.ERROR, (_, data) => {
        if (data?.fatal) {
          hideBrandGate();
          $("playerOverlay").classList.remove("hidden");
          $("playerStatus").textContent = "Wiedergabefehler";
          showTvControls(false);
        }
      });
    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      await video.play().catch(() => {});
      hideBrandGate();
      await resumeFromProgress(video, state.lastPlay?.ep);
      state.playerReady = true;
      startProgressTimer();
      showTvControls(true);
      updateSeekUi();
    } else {
      throw new Error("HLS wird auf dieser Plattform nicht unterstützt");
    }
  }

  function stopHls() {
    if (state.hls) {
      try {
        state.hls.destroy();
      } catch (_) {}
      state.hls = null;
    }
    const video = $("video");
    if (video) {
      video.removeAttribute("src");
      video.load();
    }
  }

  async function loadGenreShelf(base, genre) {
    try {
      const { text, finalUrl } = await getText(`${base}/genre/${genre.id}`);
      const items = window.CatalogParser.parseCatalog(text, finalUrl || base).slice(0, 16);
      return { title: genre.label, items };
    } catch {
      return { title: genre.label, items: [] };
    }
  }

  async function loadSeriesCatalog() {
    if (state.seriesLoadPromise) return state.seriesLoadPromise;
    const base = state.seriesBaseUrl.trim().replace(/\/$/, "");
    if (!base) return;
    state.seriesLoading = true;
    showSkeleton("series", true);
    setStatus($("seriesStatus"), "Lade Serien…");
    state.seriesLoadPromise = (async () => {
    try {
      let series = [];
      for (const path of ["/catalog.json", "/api/catalog.json", "/api/catalog", ""]) {
        try {
          const url = path ? `${base}${path}` : base;
          const { text, finalUrl, status } = await getText(url);
          if (status >= 400 || !text) continue;
          if (path && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
            try {
              const json = JSON.parse(text);
              const list = json.series || json.items || json;
              if (Array.isArray(list) && list.length) {
                series = list
                  .map((s) => ({
                    id: s.id || window.CatalogParser.slugId(s.detailPath || s.url || s.href || "", s.title),
                    title: window.CatalogParser.cleanTitle(s.title || s.name || ""),
                    posterUrl: window.SiteImages.preferJpeg(
                      absUrl(finalUrl || base, s.posterUrl || s.poster || ""),
                    ),
                    backdropUrl: window.SiteImages.preferJpeg(
                      absUrl(finalUrl || base, s.backdropUrl || s.backdrop || ""),
                    ),
                    detailPath: absUrl(finalUrl || base, s.detailPath || s.url || s.href || ""),
                    mediaKind: "series",
                  }))
                  .filter((s) => s.title && s.detailPath);
                if (series.length) break;
              }
            } catch (_) {}
          }
          series = window.CatalogParser.parseCatalog(text, finalUrl || base).map((s) => ({
            ...s,
            mediaKind: s.mediaKind || "series",
          }));
          if (series.length) break;
        } catch (_) {}
      }

      state.series = series;
      const browse = series.slice(0, BROWSE_PAGE_SIZE);
      const genreRows = [{ title: "Browse", items: browse }];
      state.seriesRows = genreRows;
      state.rows = genreRows;
      state.seriesLoaded = true;
      showSkeleton("series", false);
      renderRowsInto($("seriesRows"), buildSeriesBrowseRows());
      setStatus($("seriesStatus"), `${series.length} Serien · lade Regale…`);

      const genres = window.CatalogParser.GENRES.slice(0, 6);
      const shelves = await Promise.all(genres.map((g) => loadGenreShelf(base, g)));
      for (const shelf of shelves) {
        if (shelf.items.length) {
          genreRows.push({
            ...shelf,
            items: shelf.items.map((s) => ({ ...s, mediaKind: s.mediaKind || "series" })),
          });
        }
      }
      if (series.length > BROWSE_PAGE_SIZE) {
        genreRows.push({
          title: "Mehr Serien",
          items: series.slice(BROWSE_PAGE_SIZE, BROWSE_PAGE_SIZE * 3),
        });
      }
      state.seriesRows = genreRows;
      state.rows = genreRows;
      if (state.homeMode === "series") {
        renderRowsInto($("seriesRows"), buildSeriesBrowseRows());
      }
      setStatus($("seriesStatus"), `${series.length} Serien`);
      refreshLibrary();
    } catch (e) {
      showSkeleton("series", false);
      setStatus($("seriesStatus"), `Fehler: ${e.message || e}`);
    } finally {
      state.seriesLoading = false;
      state.seriesLoadPromise = null;
    }
    })();
    return state.seriesLoadPromise;
  }

  async function loadMoviesCatalog() {
    if (state.moviesLoadPromise) return state.moviesLoadPromise;
    const base = state.moviesBaseUrl.trim().replace(/\/$/, "");
    if (!base) return;
    state.moviesLoading = true;
    showSkeleton("movie", true);
    setStatus($("moviesStatus"), "Lade Filme…");
    state.moviesLoadPromise = (async () => {
    try {
      const paths = window.FilmParser?.browsePaths?.() || ["/movies/new", "/movies/top", "/"];
      const byId = new Map();
      const shelfMap = new Map();

      for (const path of paths) {
        try {
          const url = path === "/" ? base : `${base}${path}`;
          const { text, finalUrl, status } = await getText(url);
          if (status >= 400 || !text) continue;
          const items = (window.FilmParser.parseMovieList(text, finalUrl || base, {
            moviesOnly: true,
          }) || []).map((m) => ({ ...m, mediaKind: "movie" }));
          if (!items.length) continue;

          for (const m of items) {
            if (!byId.has(m.id)) byId.set(m.id, m);
          }

          let title = null;
          if (/\/movies\/new/i.test(path) || /neu/i.test(path)) title = "Neu";
          else if (/\/movies\/top/i.test(path) || /top/i.test(path)) title = "Top";
          if (title && items.length) {
            shelfMap.set(title, items.slice(0, BROWSE_PAGE_SIZE));
          }
        } catch (_) {}
      }

      const movies = [...byId.values()];
      state.movies = movies;
      const genreRows = [];
      for (const title of ["Neu", "Top"]) {
        if (shelfMap.has(title)) {
          genreRows.push({ title, items: shelfMap.get(title) });
        }
      }
      if (!genreRows.length && movies.length) {
        genreRows.push({ title: "Browse", items: movies.slice(0, BROWSE_PAGE_SIZE) });
      } else if (movies.length > BROWSE_PAGE_SIZE) {
        genreRows.push({
          title: "Mehr Filme",
          items: movies.slice(BROWSE_PAGE_SIZE, BROWSE_PAGE_SIZE * 3),
        });
      }
      state.moviesRows = genreRows;
      state.moviesLoaded = true;
      showSkeleton("movie", false);
      if (state.homeMode === "movies") {
        renderRowsInto($("moviesRows"), buildMoviesBrowseRows());
      }
      setStatus($("moviesStatus"), `${movies.length} Filme`);
      computeFreshMovies();
      refreshLibrary();
    } catch (e) {
      showSkeleton("movie", false);
      setStatus($("moviesStatus"), `Fehler: ${e.message || e}`);
    } finally {
      state.moviesLoading = false;
      state.moviesLoadPromise = null;
    }
    })();
    return state.moviesLoadPromise;
  }

  async function ensureSeriesCatalog(force = false) {
    if (state.seriesLoaded && !force) {
      renderRowsInto($("seriesRows"), buildSeriesBrowseRows());
      setStatus($("seriesStatus"), `${state.series.length} Serien`);
      return;
    }
    if (force) {
      state.seriesLoaded = false;
      state.seriesLoadPromise = null;
    }
    await loadSeriesCatalog();
  }

  async function ensureMoviesCatalog(force = false) {
    if (state.moviesLoaded && !force) {
      renderRowsInto($("moviesRows"), buildMoviesBrowseRows());
      setStatus($("moviesStatus"), `${state.movies.length} Filme`);
      return;
    }
    if (force) {
      state.moviesLoaded = false;
      state.moviesLoadPromise = null;
    }
    await loadMoviesCatalog();
  }

  async function refreshCatalog() {
    persistBasesFromInputs();
    state.baseUrl = activeBase().trim().replace(/\/$/, "");
    syncBaseUrlInputs();
    if (state.homeMode === "movies" || state.mediaKind === "movie") {
      state.moviesLoaded = false;
      state.moviesLoadPromise = null;
      await loadMoviesCatalog();
    } else if (state.homeMode === "series") {
      state.seriesLoaded = false;
      state.seriesLoadPromise = null;
      await loadSeriesCatalog();
    } else {
      // Library / other: refresh both in background for search + shelves
      state.seriesLoaded = false;
      state.moviesLoaded = false;
      state.seriesLoadPromise = null;
      state.moviesLoadPromise = null;
      await Promise.all([loadSeriesCatalog(), loadMoviesCatalog()]);
      refreshLibrary();
    }
  }

  let searchTimer = null;
  let searchSeq = 0;

  function matchQuery(item, q) {
    const hay = `${item.title || ""} ${item.overview || ""} ${(item.genres || []).join(" ")} ${item.id}`.toLowerCase();
    return hay.includes(q);
  }

  function localLibraryHits(query) {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return (window.VfProfiles?.listFavorites?.() || []).filter((s) => s && matchQuery(s, q));
  }

  function localCatalogHits(query, kind) {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    const pool = kind === "movie" ? state.movies || [] : state.series || [];
    const seen = new Set();
    const hits = [];
    for (const s of pool) {
      if (!s?.id || seen.has(s.id)) continue;
      if (!matchQuery(s, q)) continue;
      seen.add(s.id);
      hits.push(s);
    }
    return hits;
  }

  function localSearchHits(query) {
    // Kept for compatibility — prefer sectioned global search.
    return [
      ...localLibraryHits(query),
      ...localCatalogHits(query, "series"),
      ...localCatalogHits(query, "movie"),
    ].filter((s, i, arr) => arr.findIndex((x) => x.id === s.id) === i);
  }

  function mergeSearchHits(localHits, siteHits) {
    const byId = new Map();
    for (const s of localHits) byId.set(s.id, s);
    for (const s of siteHits || []) {
      if (!s?.id) continue;
      const prev = byId.get(s.id);
      if (!prev) {
        byId.set(s.id, s);
      } else {
        byId.set(s.id, {
          ...s,
          ...prev,
          title: prev.title || s.title,
          overview: prev.overview || s.overview,
          detailPath: prev.detailPath || s.detailPath,
          posterUrl: prev.posterUrl || s.posterUrl,
          backdropUrl: prev.backdropUrl || s.backdropUrl,
          mediaKind: prev.mediaKind || s.mediaKind,
        });
      }
    }
    return [...byId.values()];
  }

  function renderSearchSections(sections, statusMsg) {
    const box = $("searchResults");
    if (!box) return;
    box.innerHTML = "";
    if (statusMsg) {
      const st = document.createElement("div");
      st.className = "status";
      st.textContent = statusMsg;
      box.appendChild(st);
    }
    let any = false;
    const blocked = window.VfProfiles?.blockedGenres?.() || [];
    for (let sec of sections) {
      if (!sec.items?.length) continue;
      // Blocked categories never surface — not even via search (TV parity).
      sec = { ...sec, items: window.ContentGate ? window.ContentGate.filterList(sec.items, blocked) : sec.items };
      if (!sec.items.length) continue;
      any = true;
      const wrap = document.createElement("section");
      wrap.className = "shelf search-section";
      wrap.innerHTML = `<div class="shelf-head"><h2 class="row-title">${escapeHtml(sec.title)}</h2><span class="shelf-count">${sec.items.length}</span></div>`;
      const scroller = document.createElement("div");
      scroller.className = "scroller";
      for (const s of sec.items) scroller.appendChild(posterCard(s));
      wrap.appendChild(scroller);
      box.appendChild(wrap);
    }
    if (!any && !statusMsg) {
      box.innerHTML = `<div class="status">Keine Treffer</div>`;
    }
  }

  function renderSearchHits(hits, statusMsg) {
    renderSearchSections([{ title: "Treffer", items: hits }], statusMsg);
  }

  function searchPriorityOrder() {
    const last = state.lastContentMode || "library";
    if (last === "series") return ["series", "movies", "library"];
    if (last === "movies") return ["movies", "series", "library"];
    return ["library", "series", "movies"];
  }

  function buildSearchSections(lib, series, movies) {
    const map = {
      library: { title: "Meine Bibliothek", items: lib },
      series: { title: "Serien", items: series },
      movies: { title: "Filme", items: movies },
    };
    return searchPriorityOrder().map((k) => map[k]).filter((s) => s.items?.length);
  }

  async function runSearch(q) {
    const query = String(q || "").trim();
    const box = $("searchResults");
    if (!box) return;
    if (!query) {
      box.innerHTML = "";
      return;
    }

    const libLocal = localLibraryHits(query);
    const seriesLocal = localCatalogHits(query, "series");
    const movieLocal = localCatalogHits(query, "movie");
    renderSearchSections(
      buildSearchSections(libLocal, seriesLocal, movieLocal),
      query.length >= 2 ? "Suche auf der Seite…" : null,
    );

    if (query.length < 2) {
      if (!libLocal.length && !seriesLocal.length && !movieLocal.length) {
        renderSearchSections([], null);
      }
      return;
    }

    const seq = ++searchSeq;
    try {
      const [siteSeries, siteMovies] = await Promise.all([
        window.SiteSearch?.searchSite
          ? window.SiteSearch.searchSite(
              { getText, postText },
              state.seriesBaseUrl.trim().replace(/\/$/, ""),
              query,
              { mediaKind: "series" },
            ).catch(() => [])
          : Promise.resolve([]),
        window.SiteSearch?.searchSite
          ? window.SiteSearch.searchSite(
              { getText, postText },
              state.moviesBaseUrl.trim().replace(/\/$/, ""),
              query,
              { mediaKind: "movie" },
            ).catch(() => [])
          : Promise.resolve([]),
      ]);
      if (seq !== searchSeq) return;
      const seriesMerged = mergeSearchHits(
        seriesLocal,
        (siteSeries || []).map((s) => ({ ...s, mediaKind: s.mediaKind || "series" })),
      );
      const moviesMerged = mergeSearchHits(
        movieLocal,
        (siteMovies || []).map((s) => ({ ...s, mediaKind: "movie" })),
      );
      renderSearchSections(buildSearchSections(libLocal, seriesMerged, moviesMerged), null);
    } catch (e) {
      if (seq !== searchSeq) return;
      renderSearchSections(
        buildSearchSections(libLocal, seriesLocal, movieLocal),
        `Live-Suche fehlgeschlagen (${e.message || e})`,
      );
    }
  }

  function scheduleSearch(q) {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => runSearch(q), 280);
  }

  function escapeHtml(s) {
    return String(s || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function hideCtxMenu() {
    $("ctxMenu")?.classList.add("hidden");
  }

  function showDetailContextMenu(x, y, focusedEp) {
    const menu = $("ctxMenu");
    const s = state.current;
    if (!menu || !s) return;
    const items = [];
    const fav = window.VfProfiles.isFavorite(s.id);
    items.push({
      label: fav ? "Aus Favoriten entfernen" : "Zu Favoriten hinzufügen",
      run: () => {
        const nowFav = window.VfProfiles.toggleFavorite(s);
        updateDetailFavButton();
        refreshBrowseFromState();
        if (nowFav) warmEpisodeStreams(s, { forceRefresh: false });
      },
    });
    if (focusedEp) {
      items.push({
        label: "Stream-Link Episode neu laden",
        run: () => {
          renderCacheStatus(0, 1, "caching", `S${focusedEp.seasonNumber}E${focusedEp.number}`);
          warmEpisodeStreams(s, { forceRefresh: true, episodeId: focusedEp.id });
        },
      });
    }
    items.push({
      label: "Stream-Links Staffel neu laden",
      run: () => {
        warmEpisodeStreams(s, { forceRefresh: true, seasonNumber: state.season });
      },
    });
    items.push({
      label: "Stream-Links Serie neu laden (HLS/MP4)",
      run: () => {
        warmEpisodeStreams(s, { forceRefresh: true });
      },
    });
    if (!isMovieItem(s)) {
      items.push({
        label: "Zufällige Folge abspielen",
        run: () => {
          const eps = allEpisodesOrdered().filter((ep) => !ep.upcoming);
          const pick = eps[Math.floor(Math.random() * eps.length)];
          if (pick) playEpisode(pick);
        },
      });
    }
    items.push({
      label: "Aus „Weiterschauen“ entfernen",
      run: () => {
        window.VfProfiles.removeFromContinueWatching(s.id);
        updatePlayContinueButton();
        refreshBrowseFromState();
      },
    });
    items.push({
      label: "Stream-Cache dieser Serie leeren",
      run: () => {
        window.VfProfiles.clearSeriesStreams(s.id);
        if ($("cacheStatus")) $("cacheStatus").textContent = "";
        renderEpisodes();
      },
    });
    menu.innerHTML = "";
    items.forEach((it) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = it.label;
      b.onclick = (e) => {
        e.stopPropagation();
        hideCtxMenu();
        it.run();
      };
      menu.appendChild(b);
    });
    menu.classList.remove("hidden");
    const pad = 8;
    const w = menu.offsetWidth || 220;
    const h = menu.offsetHeight || 160;
    menu.style.left = `${Math.min(window.innerWidth - w - pad, Math.max(pad, x))}px`;
    menu.style.top = `${Math.min(window.innerHeight - h - pad, Math.max(pad, y))}px`;
  }

  function wireEvents() {
    document.querySelectorAll("[data-nav]").forEach((el) => {
      el.addEventListener("click", () => {
        const nav = el.dataset.nav;
        if (nav === "update") {
          runUpdateCheck();
          return;
        }
        setHomeMode(nav);
      });
    });

    const saveBases = () => {
      persistBasesFromInputs();
      state.seriesLoaded = false;
      state.moviesLoaded = false;
      refreshCatalog();
    };
    if ($("btnSaveBase")) $("btnSaveBase").onclick = saveBases;
    if ($("btnSaveBase2")) $("btnSaveBase2").onclick = saveBases;
    if ($("btnBack")) {
      $("btnBack").onclick = () => setHomeMode(state.lastContentMode || "library");
    }
    if ($("searchInput")) $("searchInput").addEventListener("input", (e) => scheduleSearch(e.target.value));

    if ($("btnProfileChip")) {
      $("btnProfileChip").onclick = () => setHomeMode("profiles");
    }
    if ($("btnSideAvatar")) {
      $("btnSideAvatar").onclick = () => setHomeMode("profiles");
    }

    if ($("btnAddProfile")) {
      $("btnAddProfile").onclick = () => {
        const name = prompt("Profilname:", "Profil");
        if (!name) return;
        window.VfProfiles.createProfile(name);
        syncProfileChip();
        applyChromePrefs();
        paintSettingsToggles();
        renderProfileGrid();
        refreshBrowseFromState();
        if (state.current) {
          updateDetailFavButton();
          updatePlayContinueButton();
          renderEpisodes();
        }
      };
    }

    if ($("btnSeasonWatched")) {
      $("btnSeasonWatched").onclick = () => toggleSeasonWatched();
    }

    if ($("btnPlayContinue")) {
      $("btnPlayContinue").onclick = () => {
        if (!state.current) return;
        const ep = window.VfProfiles.continueForSeries(state.current);
        if (ep) playEpisode(ep);
      };
    }

    if ($("btnToggleFav")) {
      $("btnToggleFav").onclick = () => {
        if (!state.current) return;
        if (!state.current.mediaKind) {
          state.current.mediaKind = isMovieItem(state.current) ? "movie" : "series";
        }
        const nowFav = window.VfProfiles.toggleFavorite(state.current);
        updateDetailFavButton();
        refreshBrowseFromState();
        if (nowFav) {
          renderCacheStatus(0, (state.current.seasons || []).flatMap((s) => s.episodes || []).length, "caching");
          warmEpisodeStreams(state.current, { forceRefresh: false });
        } else if ($("cacheStatus")) {
          $("cacheStatus").textContent = "";
        }
      };
    }

    if ($("btnMore")) {
      $("btnMore").onclick = (e) => {
        e.preventDefault();
        e.stopPropagation();
        showDetailContextMenu(e.clientX || 80, e.clientY || 120);
      };
    }

    document.addEventListener("click", (e) => {
      if (e.target.closest?.("#ctxMenu") || e.target.closest?.("#btnMore")) return;
      hideCtxMenu();
    });
    document.addEventListener("contextmenu", (e) => {
      const row = e.target.closest?.(".episode");
      if (!row || !state.current) return;
      e.preventDefault();
      const epId = row.dataset.epid;
      const ep = allEpisodesOrdered().find((x) => x.id === epId);
      showDetailContextMenu(e.clientX, e.clientY, ep);
    });

    if ($("btnLanguage")) {
      $("btnLanguage").onclick = () => toggleStreamLanguage();
    }

    if ($("btnToggleLanguagePref")) {
      paintLanguagePrefButton();
      $("btnToggleLanguagePref").onclick = () => {
        const next = window.StreamLanguage.toggle(preferredLang());
        window.VfProfiles.setStreamLanguage(next);
        window.VfProfiles.clearAllCachedStreams();
        paintLanguagePrefButton();
        if (state.current) {
          openDetail({
            ...state.current,
            detailPath: state.current.detailPath,
          });
        }
      };
    }

    if ($("btnToggleNavLayout")) {
      $("btnToggleNavLayout").onclick = () => {
        const cur = window.VfProfiles.navLayout();
        const next = cur === "sidebar" ? "topbar" : "sidebar";
        window.VfProfiles.setNavLayout(next);
        applyChromePrefs();
        paintSettingsToggles();
      };
    }

    if ($("btnToggleLibraryView")) {
      $("btnToggleLibraryView").onclick = () => {
        const cur = window.VfProfiles.libraryView();
        const next = cur === "tiles" ? "cards" : "tiles";
        window.VfProfiles.setLibraryView(next);
        applyChromePrefs();
        paintSettingsToggles();
        refreshBrowseFromState();
      };
    }

    if ($("btnCheckUpdate")) {
      $("btnCheckUpdate").onclick = () => runUpdateCheck();
    }

    if ($("btnToggleZoom")) {
      $("btnToggleZoom").onclick = () => {
        window.VfProfiles.cycleUiScale();
        applyUiScale();
        paintSettingsToggles();
      };
    }

    bindPlayerUi();
  }

  function hideSplash() {
    const splash = $("splash");
    if (!splash) return;
    splash.classList.add("hide");
    splash.setAttribute("aria-hidden", "true");
    setTimeout(() => {
      try {
        splash.remove();
      } catch (_) {}
    }, 500);
  }

  async function runSplash() {
    const canvas = $("splashIntro");
    try {
      window.VfIntro?.sting?.();
      await window.VfIntro?.play?.(canvas, { compact: false });
      await new Promise((r) => setTimeout(r, window.VfIntro?.HOLD_AFTER_MS || 260));
    } catch (_) {}
    hideSplash();
  }

  localStorage.setItem("vf_app_version", "1.13.0");
  localStorage.setItem("vf_version_code", "45");
  applyChromePrefs();
  applyUiScale();
  syncBaseUrlInputs();
  updateSearchPlaceholder();
  syncProfileChip();
  renderProfileGrid();
  paintSettingsToggles();
  wireEvents();
  runSplash();

  (async () => {
    try {
      const v = (await window.verflixed?.getVersion?.()) || "1.13.0";
      const p = (await window.verflixed?.getPlatform?.()) || "browser";
      if ($("versionLabel")) $("versionLabel").textContent = `v${v} · ${p}`;
    } catch (_) {
      if ($("versionLabel")) $("versionLabel").textContent = "v1.13.0";
    }
    setHomeMode("library");
    // Prefetch catalogs in background so search/library resolve better
    Promise.all([
      loadSeriesCatalog().catch(() => {}),
      loadMoviesCatalog().catch(() => {}),
    ]).then(() => refreshLibrary());
  })();
})();
