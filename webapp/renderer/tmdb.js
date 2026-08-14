/** TMDb metadata — same baked-in Kodi scraper key as the Fire TV APK. */
window.Tmdb = (() => {
  const APP_KEY = "af3a53eb387d57fc935e9128468b1899";
  const POSTER = "https://image.tmdb.org/t/p/w500";
  const BACKDROP = "https://image.tmdb.org/t/p/w780";
  const AVATAR = "https://image.tmdb.org/t/p/w185";

  async function getJson(url) {
    const res = window.verflixed?.getText
      ? await window.verflixed.getText(url, { Accept: "application/json" })
      : { status: 0, text: "" };
    if (res.status >= 400 || !res.text) {
      try {
        const r = await fetch(url, { headers: { Accept: "application/json" } });
        if (!r.ok) return null;
        return r.json();
      } catch {
        return null;
      }
    }
    try {
      return JSON.parse(res.text);
    } catch {
      return null;
    }
  }

  function key() {
    return APP_KEY;
  }

  async function findId(imdbId, movie) {
    const imdb = String(imdbId || "").trim();
    if (!imdb.startsWith("tt")) return null;
    const json = await getJson(
      `https://api.themoviedb.org/3/find/${imdb}?api_key=${key()}&external_source=imdb_id`,
    );
    const arr = movie ? json?.movie_results : json?.tv_results;
    const first = arr?.[0] || json?.tv_results?.[0] || json?.movie_results?.[0];
    return first?.id > 0 ? first.id : null;
  }

  async function searchId(kind, title) {
    const q = String(title || "").trim();
    if (q.length < 2) return null;
    const json = await getJson(
      `https://api.themoviedb.org/3/search/${kind}?api_key=${key()}&language=de-DE&query=${encodeURIComponent(q)}`,
    );
    return json?.results?.[0]?.id || null;
  }

  async function details(kind, id) {
    return getJson(
      `https://api.themoviedb.org/3/${kind}/${id}?api_key=${key()}&language=de-DE&append_to_response=external_ids`,
    );
  }

  function apply(series, id, d) {
    const poster = d.poster_path ? POSTER + d.poster_path : null;
    const backdrop = d.backdrop_path ? BACKDROP + d.backdrop_path : null;
    const siteBackdrop = series.backdropUrl && series.backdropUrl !== series.posterUrl ? series.backdropUrl : null;
    const year =
      series.year ||
      parseInt(String(d.first_air_date || d.release_date || "").slice(0, 4), 10) ||
      null;
    return {
      ...series,
      tmdbId: id,
      imdbId: series.imdbId || d.external_ids?.imdb_id || null,
      overview: series.overview || d.overview || null,
      posterUrl: series.posterUrl || poster,
      backdropUrl: siteBackdrop || backdrop || series.backdropUrl || poster,
      year: Number.isFinite(year) && year > 1900 ? year : series.year || null,
    };
  }

  async function enrich(series) {
    if (!series) return series;
    const movie = series.mediaKind === "movie" || series.isMovie;
    const kind = movie ? "movie" : "tv";
    try {
      const id =
        series.tmdbId ||
        (await findId(series.imdbId, movie)) ||
        (await searchId(kind, series.title));
      if (!id) return series;
      const d = await details(kind, id);
      if (!d) return series;
      return apply(series, id, d);
    } catch {
      return series;
    }
  }

  async function movieReleaseYear(title) {
    const q = String(title || "").trim();
    if (q.length < 2) return null;
    try {
      const cache = JSON.parse(localStorage.getItem("vf_tmdb_years") || "{}");
      if (q.toLowerCase() in cache) return cache[q.toLowerCase()];
      const json = await getJson(
        `https://api.themoviedb.org/3/search/movie?api_key=${key()}&language=de-DE&query=${encodeURIComponent(q)}`,
      );
      let year = null;
      for (const hit of (json?.results || []).slice(0, 3)) {
        const y = parseInt(String(hit.release_date || "").slice(0, 4), 10);
        if (!Number.isFinite(y) || y < 1900) continue;
        const name = String(hit.title || hit.original_title || "").toLowerCase();
        if (name === q.toLowerCase()) {
          year = y;
          break;
        }
        if (year == null) year = y;
      }
      cache[q.toLowerCase()] = year;
      localStorage.setItem("vf_tmdb_years", JSON.stringify(cache));
      return year;
    } catch {
      return null;
    }
  }

  async function popularPersonAvatars(pages = 3) {
    const out = [];
    for (let page = 1; page <= Math.min(5, pages); page++) {
      const json = await getJson(
        `https://api.themoviedb.org/3/person/popular?api_key=${key()}&language=de-DE&page=${page}`,
      );
      for (const o of json?.results || []) {
        if (!o.profile_path || !o.name) continue;
        out.push({ name: o.name, url: AVATAR + o.profile_path, source: "person" });
      }
    }
    return out.filter((a, i, arr) => arr.findIndex((x) => x.url === a.url) === i);
  }

  return { APP_KEY, enrich, movieReleaseYear, popularPersonAvatars };
})();
