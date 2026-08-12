/** Per-profile favorites, watch progress, and preferences (localStorage). */
window.VfProfiles = (() => {
  const KEY = "vf_multi_v1";

  function uid() {
    return "p_" + Math.random().toString(36).slice(2, 10);
  }

  function load() {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY) || "null");
      if (raw?.profiles?.length) return raw;
    } catch (_) {}
    const id = uid();
    const state = {
      activeProfileId: id,
      profiles: [{ id, name: "Hauptprofil", avatar: null, createdAt: Date.now() }],
      favorites: {}, // profileId -> { seriesId: seriesLight }
      progress: {}, // profileId -> { episodeId: {…} }
      artCache: {}, // seriesId -> { poster, backdrop, at }
    };
    save(state);
    return state;
  }

  function save(state) {
    localStorage.setItem(KEY, JSON.stringify(state));
  }

  function state() {
    return load();
  }

  function activeProfile() {
    const s = load();
    return s.profiles.find((p) => p.id === s.activeProfileId) || s.profiles[0];
  }

  function listProfiles() {
    return load().profiles;
  }

  function switchProfile(id) {
    const s = load();
    if (!s.profiles.some((p) => p.id === id)) return activeProfile();
    s.activeProfileId = id;
    save(s);
    return activeProfile();
  }

  function createProfile(name) {
    const s = load();
    const id = uid();
    const p = {
      id,
      name: (name || "Profil").trim() || "Profil",
      avatar: null,
      createdAt: Date.now(),
    };
    s.profiles.push(p);
    s.favorites[id] = {};
    s.progress[id] = {};
    s.activeProfileId = id;
    save(s);
    return p;
  }

  function renameProfile(id, name) {
    const s = load();
    const p = s.profiles.find((x) => x.id === id);
    if (!p) return;
    p.name = (name || p.name).trim() || p.name;
    save(s);
    return p;
  }

  function deleteProfile(id) {
    const s = load();
    if (s.profiles.length <= 1) throw new Error("Letztes Profil kann nicht gelöscht werden");
    s.profiles = s.profiles.filter((p) => p.id !== id);
    delete s.favorites[id];
    delete s.progress[id];
    if (s.activeProfileId === id) s.activeProfileId = s.profiles[0].id;
    save(s);
    return activeProfile();
  }

  function favoritesMap() {
    const s = load();
    const id = s.activeProfileId;
    if (!s.favorites[id]) s.favorites[id] = {};
    return s.favorites[id];
  }

  function listFavorites(mediaKind = null) {
    const all = Object.values(favoritesMap());
    if (!mediaKind) return all;
    return all.filter((f) => (f.mediaKind || "series") === mediaKind);
  }

  function isFavorite(seriesId) {
    return !!favoritesMap()[seriesId];
  }

  function toggleFavorite(series) {
    const s = load();
    const id = s.activeProfileId;
    if (!s.favorites[id]) s.favorites[id] = {};
    if (s.favorites[id][series.id]) {
      delete s.favorites[id][series.id];
      save(s);
      return false;
    }
    s.favorites[id][series.id] = {
      id: series.id,
      title: series.title,
      posterUrl: series.posterUrl || null,
      backdropUrl: series.backdropUrl || null,
      detailPath: series.detailPath,
      mediaKind: series.mediaKind === "movie" ? "movie" : "series",
      addedAt: Date.now(),
    };
    save(s);
    return true;
  }

  function progressMap() {
    const s = load();
    const id = s.activeProfileId;
    if (!s.progress[id]) s.progress[id] = {};
    return s.progress[id];
  }

  function getProgress(episodeId) {
    return progressMap()[episodeId] || null;
  }

  function saveProgress(ep, positionMs, durationMs, completed) {
    const s = load();
    const id = s.activeProfileId;
    if (!s.progress[id]) s.progress[id] = {};
    const done =
      completed ||
      (durationMs > 0 && positionMs >= durationMs * 0.9);
    s.progress[id][ep.id] = {
      episodeId: ep.id,
      seriesId: ep.seriesId,
      seasonNumber: ep.seasonNumber,
      episodeNumber: ep.number,
      positionMs: Math.max(0, positionMs | 0),
      durationMs: Math.max(0, durationMs | 0),
      completed: !!done,
      updatedAt: Date.now(),
      title: ep.title,
    };
    save(s);
  }

  function setEpisodeWatched(ep, watched) {
    if (watched) saveProgress(ep, 1, 1, true);
    else {
      const s = load();
      const id = s.activeProfileId;
      if (s.progress[id]) delete s.progress[id][ep.id];
      save(s);
    }
  }

  function continueForSeries(series) {
    const map = progressMap();
    const eps = (series.seasons || []).flatMap((s) => s.episodes || []);
    const unfinished = eps
      .map((ep) => ({ ep, p: map[ep.id] }))
      .filter((x) => x.p && !x.p.completed && x.p.positionMs > 5000)
      .sort((a, b) => b.p.updatedAt - a.p.updatedAt);
    if (unfinished[0]) return unfinished[0].ep;
    const next = eps.find((ep) => !map[ep.id]?.completed);
    return next || eps[0] || null;
  }

  function continueRow(seriesIndex) {
    const map = progressMap();
    const bySeries = {};
    for (const p of Object.values(map)) {
      if (p.completed) continue;
      if (!p.positionMs || p.positionMs < 5000) continue;
      const prev = bySeries[p.seriesId];
      if (!prev || p.updatedAt > prev.updatedAt) bySeries[p.seriesId] = p;
    }
    return Object.values(bySeries)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, 24)
      .map((p) => {
        const s = seriesIndex.find((x) => x.id === p.seriesId);
        return s
          ? {
              ...s,
              _continue: p,
            }
          : {
              id: p.seriesId,
              title: p.seriesId,
              detailPath: null,
              posterUrl: null,
              _continue: p,
            };
      })
      .filter((s) => s.detailPath);
  }

  function cacheArt(seriesId, poster, backdrop) {
    const s = load();
    s.artCache[seriesId] = {
      posterUrl: poster || null,
      backdropUrl: backdrop || null,
      at: Date.now(),
    };
    // keep last 200
    const keys = Object.keys(s.artCache);
    if (keys.length > 200) {
      keys
        .sort((a, b) => (s.artCache[a].at || 0) - (s.artCache[b].at || 0))
        .slice(0, keys.length - 200)
        .forEach((k) => delete s.artCache[k]);
    }
    save(s);
  }

  function cachedArt(seriesId) {
    return load().artCache[seriesId] || null;
  }

  function streamLanguage(profileId) {
    const s = load();
    const id = profileId || s.activeProfileId;
    const p = s.profiles.find((x) => x.id === id);
    const fromProfile = p?.streamLanguage;
    const fromMap = s.streamLanguages?.[id];
    return window.StreamLanguage?.normalize?.(fromProfile || fromMap || "de") || "de";
  }

  function setStreamLanguage(code, profileId) {
    const s = load();
    const id = profileId || s.activeProfileId;
    const norm = window.StreamLanguage?.normalize?.(code) || "de";
    const p = s.profiles.find((x) => x.id === id);
    if (p) p.streamLanguage = norm;
    if (!s.streamLanguages) s.streamLanguages = {};
    s.streamLanguages[id] = norm;
    save(s);
    return norm;
  }

  function normalizeNavLayout(v) {
    return String(v || "").toLowerCase() === "topbar" ? "topbar" : "sidebar";
  }

  function normalizeLibraryView(v) {
    return String(v || "").toLowerCase() === "cards" ? "cards" : "tiles";
  }

  function navLayout(profileId) {
    const s = load();
    const id = profileId || s.activeProfileId;
    const p = s.profiles.find((x) => x.id === id);
    const fromProfile = p?.navLayout;
    const fromMap = s.navLayouts?.[id];
    return normalizeNavLayout(fromProfile || fromMap || s.navLayout || "sidebar");
  }

  function setNavLayout(layout, profileId) {
    const s = load();
    const id = profileId || s.activeProfileId;
    const norm = normalizeNavLayout(layout);
    const p = s.profiles.find((x) => x.id === id);
    if (p) p.navLayout = norm;
    if (!s.navLayouts) s.navLayouts = {};
    s.navLayouts[id] = norm;
    s.navLayout = norm;
    save(s);
    return norm;
  }

  function libraryView(profileId) {
    const s = load();
    const id = profileId || s.activeProfileId;
    const p = s.profiles.find((x) => x.id === id);
    const fromProfile = p?.libraryView;
    const fromMap = s.libraryViews?.[id];
    return normalizeLibraryView(fromProfile || fromMap || s.libraryView || "tiles");
  }

  function setLibraryView(view, profileId) {
    const s = load();
    const id = profileId || s.activeProfileId;
    const norm = normalizeLibraryView(view);
    const p = s.profiles.find((x) => x.id === id);
    if (p) p.libraryView = norm;
    if (!s.libraryViews) s.libraryViews = {};
    s.libraryViews[id] = norm;
    s.libraryView = norm;
    save(s);
    return norm;
  }

  function streamCacheMap() {
    const s = load();
    if (!s.streamCache) s.streamCache = {};
    const id = s.activeProfileId;
    if (!s.streamCache[id]) s.streamCache[id] = {};
    return { state: s, map: s.streamCache[id] };
  }

  function cacheStream(episodeId, seriesId, streamUrl, language) {
    const { state: s, map } = streamCacheMap();
    const lang = window.StreamLanguage?.normalize?.(language || streamLanguage()) || "de";
    const kindBase = /\.m3u8/i.test(streamUrl) ? "m3u8" : /\.mp4/i.test(streamUrl) ? "mp4" : "media";
    map[episodeId] = {
      episodeId,
      seriesId,
      streamUrl,
      kind: `${kindBase}|${lang}`,
      updatedAt: Date.now(),
    };
    save(s);
  }

  function getCachedStream(episodeId, preferredLang) {
    const { map } = streamCacheMap();
    const hit = map[episodeId];
    if (!hit?.streamUrl) return null;
    const pref = window.StreamLanguage?.normalize?.(preferredLang || streamLanguage()) || "de";
    const sep = String(hit.kind || "").lastIndexOf("|");
    if (sep < 0) return null; // legacy without lang — ignore
    const lang = window.StreamLanguage?.normalize?.(hit.kind.slice(sep + 1)) || "de";
    if (lang !== pref) return null;
    return hit.streamUrl;
  }

  function clearCachedStream(episodeId) {
    const { state: s, map } = streamCacheMap();
    delete map[episodeId];
    save(s);
  }

  function clearSeriesStreams(seriesId) {
    const { state: s, map } = streamCacheMap();
    Object.keys(map).forEach((epId) => {
      if (map[epId]?.seriesId === seriesId) delete map[epId];
    });
    save(s);
  }

  function clearSeasonStreams(seriesId, seasonNumber) {
    // Episode ids are opaque; callers should pass episode ids when needed.
    // Kept for API symmetry — clear by episode list via clearCachedStream.
    void seriesId;
    void seasonNumber;
  }

  function clearAllCachedStreams() {
    const s = load();
    if (s.streamCache) delete s.streamCache[s.activeProfileId];
    save(s);
  }

  function cachedEpisodeIds(seriesId, preferredLang) {
    const { map } = streamCacheMap();
    const pref = window.StreamLanguage?.normalize?.(preferredLang || streamLanguage()) || "de";
    return Object.keys(map).filter((epId) => {
      const hit = map[epId];
      if (!hit?.streamUrl) return false;
      if (seriesId && hit.seriesId !== seriesId) return false;
      const sep = String(hit.kind || "").lastIndexOf("|");
      if (sep < 0) return false;
      const lang = window.StreamLanguage?.normalize?.(hit.kind.slice(sep + 1)) || "de";
      return lang === pref;
    });
  }

  return {
    activeProfile,
    listProfiles,
    switchProfile,
    createProfile,
    renameProfile,
    deleteProfile,
    listFavorites,
    isFavorite,
    toggleFavorite,
    getProgress,
    saveProgress,
    setEpisodeWatched,
    continueForSeries,
    continueRow,
    progressMap,
    cacheArt,
    cachedArt,
    streamLanguage,
    setStreamLanguage,
    navLayout,
    setNavLayout,
    libraryView,
    setLibraryView,
    cacheStream,
    getCachedStream,
    clearCachedStream,
    clearSeriesStreams,
    clearSeasonStreams,
    clearAllCachedStreams,
    cachedEpisodeIds,
  };
})();
