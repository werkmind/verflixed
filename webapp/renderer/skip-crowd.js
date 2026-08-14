/** AniSkip + TheIntroDB + SkipDB (buttons only, never auto-skip). */
window.VfCrowdSkip = (() => {
  const cache = new Map();

  async function getJson(url) {
    if (window.verflixed?.getText) {
      const res = await window.verflixed.getText(url, { Accept: "application/json" });
      if (res.status < 400 && res.text) {
        try {
          return JSON.parse(res.text);
        } catch (_) {}
      }
    }
    try {
      const r = await fetch(url, { headers: { Accept: "application/json" } });
      if (!r.ok) return null;
      return r.json();
    } catch {
      return null;
    }
  }

  function ms(obj, msKey, secKey, alt) {
    if (!obj) return null;
    if (obj[msKey] != null) return Number(obj[msKey]);
    if (obj[secKey] != null) return Number(obj[secKey]) * 1000;
    if (obj[alt] != null) {
      const n = Number(obj[alt]);
      return n > 10000 ? n : n * 1000;
    }
    return null;
  }

  function seg(type, start, end, source, durationMs) {
    if (end == null) end = durationMs;
    if (!(end > start)) return null;
    const labels = {
      INTRO: "Intro überspringen",
      RECAP: "Rückblick überspringen",
      CREDITS: "Abspann überspringen",
      PREVIEW: "Vorschau überspringen",
    };
    return { type, startMs: Math.max(0, start), endMs: end, source, label: labels[type] || "Überspringen" };
  }

  function looksAnime(series) {
    const genres = (series?.genres || []).join(" ").toLowerCase();
    const path = String(series?.detailPath || "").toLowerCase();
    const title = String(series?.title || "").toLowerCase();
    return (
      /anime|animation|zeichentrick|manga/.test(genres) ||
      /anime|aniworld|animes/.test(path) ||
      title.includes("anime")
    );
  }

  async function aniSkip(series, episodeNumber, durationMs, absoluteEpisodeNumber) {
    if (!looksAnime(series)) return [];
    const q = String(series.title || "").trim();
    if (!q) return [];
    const jikan = await getJson(
      `https://api.jikan.moe/v4/anime?q=${encodeURIComponent(q)}&limit=8`,
    );
    const best = (jikan?.data || [])[0];
    const mal = best?.mal_id;
    if (!mal) return [];
    const nums = [...new Set([absoluteEpisodeNumber, episodeNumber].filter((n) => n > 0))];
    for (const num of nums) {
      const key = `ani:${mal}:${num}:${Math.floor(durationMs / 1000)}`;
      if (cache.has(key)) return cache.get(key);
      const body = await getJson(
        `https://api.aniskip.com/v2/skip-times/${mal}/${num}?types=op&types=ed&types=recap&types=mixed-op&types=mixed-ed&episodeLength=${Math.floor(durationMs / 1000)}`,
      );
      if (!body?.found) {
        cache.set(key, []);
        continue;
      }
      const segs = (body.results || [])
        .map((row) => {
          const start = (row.interval?.startTime || 0) * 1000;
          const end = (row.interval?.endTime || 0) * 1000;
          const type =
            /op/.test(row.skipType || "")
              ? "INTRO"
              : /ed/.test(row.skipType || "")
                ? "CREDITS"
                : row.skipType === "recap"
                  ? "RECAP"
                  : null;
          return type ? seg(type, start, end, "aniskip", durationMs) : null;
        })
        .filter(Boolean);
      cache.set(key, segs);
      if (segs.length) return segs;
    }
    return [];
  }

  async function introDb(tmdbId, imdbId, season, episode, durationMs, movie) {
    const u = new URL("https://api.theintrodb.org/v3/media");
    if (tmdbId) u.searchParams.set("tmdb_id", String(tmdbId));
    else if (imdbId) u.searchParams.set("imdb_id", imdbId);
    else return [];
    if (!movie && season > 0 && episode > 0) {
      u.searchParams.set("season", String(season));
      u.searchParams.set("episode", String(episode));
    }
    if (durationMs > 5000) u.searchParams.set("duration_ms", String(durationMs));
    const json = await getJson(u.toString());
    if (!json) return [];
    const out = [];
    for (const [key, type] of [
      ["intro", "INTRO"],
      ["recap", "RECAP"],
      ["credits", "CREDITS"],
      ["preview", "PREVIEW"],
    ]) {
      for (const row of json[key] || []) {
        const s = seg(type, ms(row, "start_ms", "start_sec", "start") || 0, ms(row, "end_ms", "end_sec", "end"), "theintrodb", durationMs);
        if (s) out.push(s);
      }
    }
    return out;
  }

  async function skipDb(imdbId, season, episode, durationMs, movie) {
    if (!imdbId) return [];
    const u = new URL("https://skipdb.tv/api/segments");
    u.searchParams.set("imdb_id", imdbId);
    if (!movie && season > 0 && episode > 0) {
      u.searchParams.set("season", String(season));
      u.searchParams.set("episode", String(episode));
    }
    if (durationMs > 5000) u.searchParams.set("duration", String(Math.floor(durationMs / 1000)));
    const json = await getJson(u.toString());
    const segs = json?.segments || json || {};
    const out = [];
    const map = [
      ["intro", "INTRO"],
      ["recap", "RECAP"],
      ["outro", "CREDITS"],
      ["credits", "CREDITS"],
      ["preview", "PREVIEW"],
    ];
    for (const [key, type] of map) {
      const row = segs[key];
      if (!row) continue;
      const s = seg(type, ms(row, "start_ms", "start_sec", "start") || 0, ms(row, "end_ms", "end_sec", "end"), "skipdb", durationMs);
      if (s) out.push(s);
    }
    return out;
  }

  function merge(lists) {
    const byType = {};
    for (const list of lists) {
      for (const s of list || []) {
        if (!byType[s.type] || s.startMs < byType[s.type].startMs) byType[s.type] = s;
      }
    }
    return Object.values(byType).sort((a, b) => a.startMs - b.startMs);
  }

  async function skipSegments(series, seasonNumber, episodeNumber, durationMs) {
    if (!series) return [];
    const movie = series.mediaKind === "movie";
    const tmdb = series.tmdbId;
    const imdb = series.imdbId;
    const abs = (series.seasons || [])
      .filter((s) => (s.number || 0) < seasonNumber)
      .reduce((n, s) => n + (s.episodes || []).length, 0) + episodeNumber;
    const [ani, intro, skip] = await Promise.all([
      aniSkip(series, episodeNumber, durationMs, abs).catch(() => []),
      introDb(tmdb, imdb, seasonNumber, episodeNumber, durationMs, movie).catch(() => []),
      skipDb(imdb, seasonNumber, episodeNumber, durationMs, movie).catch(() => []),
    ]);
    return merge([ani, intro, skip]);
  }

  return { skipSegments };
})();
