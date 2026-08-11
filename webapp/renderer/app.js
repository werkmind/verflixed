(() => {
  const $ = (id) => document.getElementById(id);
  const BROWSE_PAGE_SIZE = 24;
  const PROGRESS_SAVE_MS = 15000;
  const SEEK_STEP_S = 10;

  const state = {
    mediaKind: localStorage.getItem("vf_media_kind") || "series",
    seriesBaseUrl:
      localStorage.getItem("vf_base_series") ||
      localStorage.getItem("vf_base") ||
      "https://aniworld.to",
    moviesBaseUrl: localStorage.getItem("vf_base_movies") || "https://filmpalast.to",
    baseUrl: "",
    series: [],
    movies: [],
    rows: [],
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

  function showTab(name) {
    document.querySelectorAll(".tab").forEach((t) => {
      t.classList.toggle("active", t.dataset.tab === name);
    });
    ["browse", "search", "profiles", "detail", "player"].forEach((v) => {
      const node = $(`view-${v}`);
      if (node) node.classList.toggle("active", v === name);
    });
    if (name !== "player") {
      state.controlsVisible = false;
      state.lastBackExitAt = 0;
    }
  }

  function preferredLang() {
    return window.StreamLanguage?.normalize?.(window.VfProfiles?.streamLanguage?.()) || "de";
  }

  function paintLanguagePrefButton() {
    const btn = $("btnToggleLanguagePref");
    if (!btn) return;
    btn.textContent = `Standard-Ton: ${window.StreamLanguage?.label?.(preferredLang()) || "Deutsch"}`;
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
    showTvControls(false);
    if (state.current) showTab("detail");
    else showTab("browse");
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
    document.querySelectorAll(".kind-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.kind === state.mediaKind);
    });
  }

  function updateSearchPlaceholder() {
    const input = $("searchInput");
    if (!input) return;
    input.placeholder =
      state.mediaKind === "movie" ? "Filme suchen…" : "Serien suchen…";
  }

  function setMediaKind(kind) {
    const k = kind === "movie" ? "movie" : "series";
    if (state.mediaKind === k && state.series.length) {
      updateKindButtons();
      updateSearchPlaceholder();
      syncBaseUrlInputs();
      return;
    }
    state.mediaKind = k;
    localStorage.setItem("vf_media_kind", k);
    state.baseUrl = activeBase();
    updateKindButtons();
    updateSearchPlaceholder();
    syncBaseUrlInputs();
    refreshCatalog();
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
    el.className = "card" + (opts.continuePct ? " card-continue" : "");
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

  function renderBrowseRows(rows) {
    const host = $("rows");
    host.innerHTML = "";
    for (const g of rows) {
      if (!g.items?.length) continue;
      const wrap = document.createElement("section");
      wrap.className = "shelf";
      wrap.innerHTML = `<div class="shelf-head"><h2 class="row-title">${escapeHtml(g.title)}</h2><span class="shelf-count">${g.items.length}</span></div>`;
      const scroller = document.createElement("div");
      scroller.className = "scroller";
      for (const item of g.items) {
        const s = item.series || item;
        const p = item._continue;
        let continuePct = 0;
        if (p?.durationMs > 0) continuePct = (p.positionMs / p.durationMs) * 100;
        scroller.appendChild(posterCard(s, { continuePct }));
      }
      wrap.appendChild(scroller);
      host.appendChild(wrap);
    }
  }

  function buildBrowseRows() {
    const continueItems = window.VfProfiles.continueRow(state.series)
      .filter((s) => {
        if (!s.mediaKind) return true;
        return s.mediaKind === state.mediaKind;
      })
      .map((s) => ({
        series: s,
        _continue: s._continue,
      }));
    const favItems = window.VfProfiles.listFavorites(state.mediaKind);
    const browse = state.series.slice(0, BROWSE_PAGE_SIZE);
    const rows = [];
    if (continueItems.length) rows.push({ title: "Weiterschauen", items: continueItems });
    if (favItems.length) rows.push({ title: "Meine Liste", items: favItems });
    if (browse.length) rows.push({ title: "Browse", items: browse });
    for (const shelf of state.rows.filter((r) => !["Weiterschauen", "Meine Liste", "Browse"].includes(r.title))) {
      rows.push(shelf);
    }
    return rows;
  }

  function refreshBrowseFromState() {
    const rows = buildBrowseRows();
    renderBrowseRows(rows);
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
      hero || state.current?.posterUrl || state.current?.backdropUrl,
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
    showTab("detail");
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
        applyDetailHero(state.season);
        updateDetailFavButton();
        updatePlayContinueButton();
        renderEpisodes();
        await refreshAvailableLanguages(detailed, workingHtml, workingUrl);
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
      applyDetailHero(state.season);
      updateDetailFavButton();
      updatePlayContinueButton();
      renderSeasons();
      renderEpisodes();
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

      const row = document.createElement("div");
      row.className = "episode" + (watched ? " watched" : "");
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
        refreshBrowseFromState();
      };
      list.appendChild(row);
    }
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
    video.addEventListener("timeupdate", updateSeekUi);
    video.addEventListener("loadedmetadata", updateSeekUi);
    video.addEventListener("play", () => showTvControls(true));
    video.addEventListener("pause", () => updateSeekUi());
    video.addEventListener("ended", () => {
      saveCurrentProgress(true);
      stopProgressTimer();
      const next = state.lastPlay?.ep ? nextEpisodeAfter(state.lastPlay.ep) : null;
      if (next) {
        playEpisode(next);
      } else {
        $("playerStatus").textContent = "Fertig";
        showTvControls(true);
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
          const next = state.lastPlay?.ep ? nextEpisodeAfter(state.lastPlay.ep) : null;
          if (next) playEpisode(next);
        }
        updateSeekUi();
      });
    }

    document.addEventListener("keydown", (e) => {
      if (!$("view-player")?.classList.contains("active")) return;
      const tag = (e.target?.tagName || "").toLowerCase();
      if (tag === "input" || tag === "textarea") return;
      if (e.key === "Escape" || e.key === "BrowserBack" || e.code === "BrowserBack") {
        e.preventDefault();
        handlePlaybackBack();
        return;
      }
      if (e.code === "Space") {
        e.preventDefault();
        if (video.paused) video.play().catch(() => {});
        else video.pause();
        showTvControls(true);
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
        const next = state.lastPlay?.ep ? nextEpisodeAfter(state.lastPlay.ep) : null;
        if (next) playEpisode(next);
      }
      updateSeekUi();
    });
  }

  async function playEpisode(ep) {
    showTab("player");
    stopHls();
    stopProgressTimer();
    state.playerReady = false;
    showTvControls(false);
    $("playerOverlay").classList.remove("hidden");
    const movieMode =
      state.mediaKind === "movie" ||
      state.current?.mediaKind === "movie" ||
      isMovieItem(state.current);
    $("playerTitle").textContent = movieMode
      ? state.current?.title || ep.title || ""
      : `${state.current?.title || ""} · S${ep.seasonNumber}E${ep.number}`;
    $("playerStatus").textContent = movieMode ? "Film laden…" : "Episode laden…";
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

  async function resumeFromProgress(video, ep) {
    const prog = window.VfProfiles.getProgress(ep.id);
    if (prog && !prog.completed && prog.positionMs > 5000 && prog.durationMs > 0) {
      const t = prog.positionMs / 1000;
      try {
        video.currentTime = t;
      } catch (_) {}
    }
  }

  async function playMp4(url) {
    stopHls();
    const video = $("video");
    $("playerOverlay").classList.add("hidden");
    video.src = url;
    await video.play().catch(() => {});
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
        await video.play().catch(() => {});
        await resumeFromProgress(video, state.lastPlay?.ep);
        state.playerReady = true;
        startProgressTimer();
        showTvControls(true);
        updateSeekUi();
      });
      state.hls.on(Hls.Events.ERROR, (_, data) => {
        if (data?.fatal) {
          $("playerOverlay").classList.remove("hidden");
          $("playerStatus").textContent = "Wiedergabefehler";
          showTvControls(false);
        }
      });
    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      await video.play().catch(() => {});
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

  async function refreshCatalog() {
    persistBasesFromInputs();
    const base = activeBase().trim().replace(/\/$/, "");
    if (!base) return;
    state.baseUrl = base;
    syncBaseUrlInputs();
    setStatus(
      $("browseStatus"),
      state.mediaKind === "movie" ? "Lade Filme…" : "Lade Katalog…",
    );
    try {
      if (state.mediaKind === "movie") {
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
        state.series = movies;
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
        state.rows = genreRows;
        refreshBrowseFromState();
        setStatus($("browseStatus"), `${movies.length} Filme`);
        showTab("browse");
        return;
      }

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

      setStatus($("browseStatus"), `${series.length} Serien · lade Regale…`);
      state.rows = genreRows;
      refreshBrowseFromState();

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
      state.rows = genreRows;
      refreshBrowseFromState();
      setStatus($("browseStatus"), `${series.length} Serien`);
      showTab("browse");
    } catch (e) {
      setStatus($("browseStatus"), `Fehler: ${e.message || e}`);
    }
  }

  let searchTimer = null;
  let searchSeq = 0;

  function localSearchHits(query) {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    const favs = (window.VfProfiles?.listFavorites?.(state.mediaKind) || []).filter(Boolean);
    const pool = [...favs, ...(state.series || [])];
    const seen = new Set();
    const hits = [];
    for (const s of pool) {
      if (!s?.id || seen.has(s.id)) continue;
      if (s.mediaKind && s.mediaKind !== state.mediaKind) continue;
      const hay = `${s.title || ""} ${s.overview || ""} ${(s.genres || []).join(" ")} ${s.id}`.toLowerCase();
      if (!hay.includes(q)) continue;
      seen.add(s.id);
      hits.push(s);
    }
    return hits;
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
        });
      }
    }
    return [...byId.values()];
  }

  function renderSearchHits(hits, statusMsg) {
    const box = $("searchResults");
    box.innerHTML = "";
    if (statusMsg) {
      const st = document.createElement("div");
      st.className = "status";
      st.textContent = statusMsg;
      box.appendChild(st);
    }
    for (const s of hits) box.appendChild(posterCard(s));
    if (!hits.length && !statusMsg) {
      box.innerHTML = `<div class="status">Keine Treffer</div>`;
    }
  }

  async function runSearch(q) {
    const query = String(q || "").trim();
    const box = $("searchResults");
    if (!box) return;
    if (!query) {
      box.innerHTML = "";
      return;
    }

    const localHits = localSearchHits(query);
    renderSearchHits(localHits, query.length >= 2 ? "Suche auf der Seite…" : null);

    if (query.length < 2) {
      if (!localHits.length) renderSearchHits([], null);
      return;
    }

    const seq = ++searchSeq;
    const base = activeBase().trim().replace(/\/$/, "");
    if (!base || !window.SiteSearch?.searchSite) {
      if (!localHits.length) renderSearchHits([], null);
      return;
    }

    try {
      const siteHits = await window.SiteSearch.searchSite(
        { getText, postText },
        base,
        query,
        { mediaKind: state.mediaKind },
      );
      if (seq !== searchSeq) return;
      const filteredSite = (siteHits || []).filter(
        (s) => !s.mediaKind || s.mediaKind === state.mediaKind,
      );
      const merged = mergeSearchHits(localHits, filteredSite);
      renderSearchHits(
        merged,
        filteredSite.length
          ? `${merged.length} Treffer (inkl. Live-Suche)`
          : merged.length
            ? null
            : null,
      );
      if (!merged.length) renderSearchHits([], null);
    } catch (e) {
      if (seq !== searchSeq) return;
      renderSearchHits(
        localHits,
        localHits.length
          ? `Live-Suche fehlgeschlagen · ${localHits.length} lokal`
          : `Keine Treffer (${e.message || e})`,
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

  function wireEvents() {
    document.querySelectorAll(".tab").forEach((t) => {
      t.addEventListener("click", () => showTab(t.dataset.tab));
    });

    document.querySelectorAll(".kind-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        const kind = btn.dataset.kind === "movie" ? "movie" : "series";
        setMediaKind(kind);
      });
    });

    const saveBases = () => {
      persistBasesFromInputs();
      refreshCatalog();
    };
    if ($("btnSaveBase")) $("btnSaveBase").onclick = saveBases;
    if ($("btnSaveBase2")) $("btnSaveBase2").onclick = saveBases;
    if ($("btnBack")) $("btnBack").onclick = () => showTab("browse");
    if ($("searchInput")) $("searchInput").addEventListener("input", (e) => scheduleSearch(e.target.value));

    if ($("btnProfileChip")) {
      $("btnProfileChip").onclick = () => {
        showTab("profiles");
        renderProfileGrid();
      };
    }

    if ($("btnAddProfile")) {
      $("btnAddProfile").onclick = () => {
        const name = prompt("Profilname:", "Profil");
        if (!name) return;
        window.VfProfiles.createProfile(name);
        syncProfileChip();
        renderProfileGrid();
        refreshBrowseFromState();
        if (state.current) {
          updateDetailFavButton();
          updatePlayContinueButton();
          renderEpisodes();
        }
      };
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
          state.current.mediaKind = state.mediaKind === "movie" ? "movie" : "series";
        }
        window.VfProfiles.toggleFavorite(state.current);
        updateDetailFavButton();
        refreshBrowseFromState();
      };
    }

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

    if ($("btnCheckUpdate")) {
      $("btnCheckUpdate").onclick = async () => {
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
      };
    }

    bindPlayerUi();
  }

  localStorage.setItem("vf_app_version", "1.6.9");
  localStorage.setItem("vf_version_code", "25");
  syncBaseUrlInputs();
  updateKindButtons();
  updateSearchPlaceholder();
  syncProfileChip();
  renderProfileGrid();
  paintLanguagePrefButton();
  wireEvents();

  (async () => {
    try {
      const v = (await window.verflixed?.getVersion?.()) || "1.6.9";
      const p = (await window.verflixed?.getPlatform?.()) || "browser";
      $("versionLabel").textContent = `v${v} · ${p}`;
    } catch (_) {
      if ($("versionLabel")) $("versionLabel").textContent = "v1.6.9";
    }
    refreshCatalog();
  })();
})();
