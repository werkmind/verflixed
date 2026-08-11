/** Free metadata enrichment via TVMaze (no API key) – mirrors Android TvMazeClient */
window.TvMaze = (() => {
  async function getJson(url) {
    if (window.verflixed?.getText) {
      const res = await window.verflixed.getText(url, { Accept: "application/json" });
      if (res.status >= 400 || !res.text) return null;
      try {
        return JSON.parse(res.text);
      } catch {
        return null;
      }
    }
    const r = await fetch(url, { headers: { Accept: "application/json" } });
    if (!r.ok) return null;
    return r.json();
  }

  function stripHtml(html) {
    if (!html) return null;
    const d = new DOMParser().parseFromString(html, "text/html");
    return d.body.textContent?.trim() || null;
  }

  function preferredStill(image) {
    return image?.original || image?.medium || null;
  }

  function seasonRemap(episodes) {
    const seasons = [...new Set(episodes.map((e) => e.season).filter((n) => n != null))].sort(
      (a, b) => a - b,
    );
    if (!seasons.length) return new Map();
    const looksLikeYears = seasons.some((s) => s >= 1900);
    if (looksLikeYears) {
      return new Map(seasons.map((year, idx) => [year, idx + 1]));
    }
    return new Map(seasons.map((s) => [s, s]));
  }

  function buildIndex(episodes) {
    const remap = seasonRemap(episodes);
    const map = new Map();
    for (const ep of episodes) {
      if (ep.season == null || ep.number == null) continue;
      map.set(`${ep.season}:${ep.number}`, ep);
      const remapped = remap.get(ep.season);
      if (remapped != null) map.set(`${remapped}:${ep.number}`, ep);
    }
    return map;
  }

  async function searchShow(title) {
    const q = window.CatalogParser.cleanTitle(title);
    if (!q) return null;
    return getJson(
      `https://api.tvmaze.com/singlesearch/shows?q=${encodeURIComponent(q)}`,
    );
  }

  async function fetchEpisodes(showId) {
    if (!showId) return [];
    return (await getJson(`https://api.tvmaze.com/shows/${showId}/episodes`)) || [];
  }

  async function enrichBasic(series) {
    try {
      const show = await searchShow(series.title);
      if (!show) return series;
      return applyShowMeta(series, show, series.seasons || []);
    } catch {
      return series;
    }
  }

  async function enrich(series) {
    try {
      const show = await searchShow(series.title);
      if (!show) return series;
      const mazeEpisodes = await fetchEpisodes(show.id);
      const seasonArts = await fetchSeasonArts(show.id);
      const byKey = buildIndex(mazeEpisodes);
      let seasons = series.seasons || [];

      if (!seasons.length) {
        const remap = seasonRemap(mazeEpisodes);
        const grouped = new Map();
        for (const me of mazeEpisodes) {
          const sn = remap.get(me.season ?? 1) ?? me.season ?? 1;
          if (sn <= 0) continue;
          if (!grouped.has(sn)) grouped.set(sn, []);
          grouped.get(sn).push(me);
        }
        seasons = [...grouped.entries()]
          .sort((a, b) => a[0] - b[0])
          .map(([seasonNo, eps]) => ({
            number: seasonNo,
            title: `Staffel ${seasonNo}`,
            posterUrl: seasonArts.get(seasonNo) || null,
            backdropUrl: seasonArts.get(seasonNo) || null,
            episodes: eps
              .sort((a, b) => (a.number || 0) - (b.number || 0))
              .map((me, idx) => ({
                id: `${series.id}-s${seasonNo}e${me.number || idx + 1}`,
                seriesId: series.id,
                seasonNumber: seasonNo,
                number: me.number || idx + 1,
                title: me.name || `Episode ${me.number || idx + 1}`,
                overview: stripHtml(me.summary),
                stillUrl: preferredStill(me.image),
                streamPageUrl: null,
              })),
          }));
      } else {
        const flat = [...mazeEpisodes].sort(
          (a, b) => (a.season || 0) - (b.season || 0) || (a.number || 0) - (b.number || 0),
        );
        const flatEps = seasons.flatMap((s) => s.episodes);
        const matched = flatEps.filter((e) => byKey.has(`${e.seasonNumber}:${e.number}`)).length;
        const useFlat = matched < flatEps.length * 0.3;
        let flatIdx = 0;
        seasons = seasons.map((season) => ({
          ...season,
          posterUrl: season.posterUrl || seasonArts.get(season.number) || null,
          backdropUrl: season.backdropUrl || seasonArts.get(season.number) || null,
          episodes: season.episodes.map((ep) => {
            let me = byKey.get(`${ep.seasonNumber}:${ep.number}`);
            if (!me && useFlat && flatIdx < flat.length) me = flat[flatIdx++];
            else if (useFlat) flatIdx++;
            const generic =
              /^Episode\b/i.test(ep.title) ||
              ep.title.toLowerCase() === `folge ${ep.number}` ||
              /^\d+$/.test(ep.title);
            return {
              ...ep,
              title: generic ? me?.name || ep.title : ep.title,
              overview: ep.overview || stripHtml(me?.summary),
              stillUrl: preferredStill(me?.image) || ep.stillUrl,
            };
          }),
        }));
      }

      return applyShowMeta(series, show, seasons);
    } catch {
      return series;
    }
  }

  async function fetchSeasonArts(showId) {
    const map = new Map();
    if (!showId) return map;
    const list = await getJson(`https://api.tvmaze.com/shows/${showId}/seasons`);
    if (!Array.isArray(list)) return map;
    for (const s of list) {
      const n = s.number;
      const url = s.image?.original || s.image?.medium;
      if (n > 0 && url) map.set(n, url);
    }
    return map;
  }

  function applyShowMeta(series, show, seasons) {
    const poster = show.image?.medium || show.image?.original || null;
    const backdrop = show.image?.original || poster;
    const overview = stripHtml(show.summary);
    const existing = (series.overview || "").trim();
    const preferExisting =
      existing.length > 60 && !existing.toLowerCase().startsWith("schaue ");
    return {
      ...series,
      title: show.name || series.title,
      overview: preferExisting ? existing : overview || existing,
      posterUrl: series.posterUrl || poster,
      backdropUrl: series.backdropUrl || backdrop,
      year: series.year || (show.premiered ? Number(show.premiered.slice(0, 4)) : null),
      seasons,
    };
  }

  return { enrich, enrichBasic, searchShow };
})();
