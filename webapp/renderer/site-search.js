/** Live site search – SerienStream suggest / AniWorld ajax / Filmpalast title search. */
window.SiteSearch = (() => {
  const SERIES_HREF =
    /\/(?:serie|series|anime\/stream)(?:\/stream)?\/[^/?#]+/i;
  const MOVIE_HREF = /\/stream\/[^/?#]+/i;
  const EP_RE = /\bS\d{1,2}E\d{1,3}\b/i;

  function stripTags(s) {
    return String(s || "")
      .replace(/<[^>]+>/g, "")
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"')
      .replace(/&#0?39;/g, "'")
      .replace(/&#8230;/g, "…")
      .replace(/\s+/g, " ")
      .trim();
  }

  function abs(base, href) {
    if (!href) return "";
    try {
      if (String(href).startsWith("//")) return `https:${href}`;
      return new URL(href, base).toString();
    } catch {
      return href;
    }
  }

  function isSeriesHref(href) {
    return SERIES_HREF.test(String(href || ""));
  }

  function isMovieHref(href) {
    const h = String(href || "");
    return MOVIE_HREF.test(h) && !SERIES_HREF.test(h);
  }

  function isEpisodeLike(title, href) {
    return EP_RE.test(title || "") || EP_RE.test(href || "");
  }

  function toItem(base, title, href, overview, mediaKind) {
    const detailPath = abs(base, href);
    if (!detailPath) return null;
    const kind = mediaKind === "movie" ? "movie" : "series";
    if (kind === "movie") {
      if (!isMovieHref(detailPath) && !MOVIE_HREF.test(detailPath)) return null;
      if (isEpisodeLike(title, detailPath)) return null;
    } else if (!isSeriesHref(detailPath)) {
      return null;
    }
    const clean =
      (window.CatalogParser?.cleanTitle?.(stripTags(title)) ||
        window.FilmParser?.cleanTitle?.(stripTags(title)) ||
        stripTags(title)).trim();
    if (!clean) return null;
    const id =
      (kind === "movie"
        ? window.FilmParser?.slugId?.(detailPath, clean)
        : window.CatalogParser?.slugId?.(detailPath, clean)) ||
      clean.toLowerCase().replace(/[^a-z0-9]+/g, "-");
    return {
      id,
      title: clean,
      overview: overview ? stripTags(overview) : null,
      detailPath,
      posterUrl: null,
      backdropUrl: null,
      genres: [],
      seasons: [],
      mediaKind: kind,
      fromSiteSearch: true,
    };
  }

  function parseSuggestJson(text, base, mediaKind = "series") {
    let data;
    try {
      data = JSON.parse(text);
    } catch {
      return [];
    }
    const out = [];
    const shows = Array.isArray(data?.shows)
      ? data.shows
      : Array.isArray(data)
        ? data
        : [];
    for (const item of shows) {
      if (!item || typeof item !== "object") continue;
      const title = item.name || item.title || "";
      const href = item.url || item.link || item.href || "";
      const s = toItem(base, title, href, item.description || item.overview, mediaKind);
      if (s) out.push(s);
    }
    return out;
  }

  /**
   * @param {{ getText: Function, postText?: Function }} http
   * @param {string} baseUrl
   * @param {string} query
   * @param {{ mediaKind?: 'series'|'movie' }} [opts]
   */
  async function searchSite(http, baseUrl, query, opts = {}) {
    const base = String(baseUrl || "").trim().replace(/\/$/, "");
    const q = String(query || "").trim();
    const mediaKind = opts.mediaKind === "movie" ? "movie" : "series";
    if (!base || q.length < 2) return [];

    const headersJson = {
      Accept: "application/json, text/javascript, */*; q=0.01",
      "X-Requested-With": "XMLHttpRequest",
      Referer: `${base}/`,
    };

    if (mediaKind === "movie") {
      // Filmpalast: /search/title/{query}
      for (const path of [
        `/search/title/${encodeURIComponent(q)}`,
        `/search/title/${encodeURIComponent(q.replace(/\s+/g, "+"))}`,
        `/suche?q=${encodeURIComponent(q)}`,
        `/search?q=${encodeURIComponent(q)}`,
      ]) {
        try {
          const res = await http.getText(`${base}${path}`, {
            Accept: "text/html,*/*",
            Referer: `${base}/`,
          });
          if (!(res?.status >= 200 && res.status < 300 && res.text)) continue;
          if (window.FilmParser?.parseMovieList) {
            const hits = window.FilmParser.parseMovieList(res.text, res.finalUrl || base, {
              moviesOnly: true,
            });
            if (hits.length) return hits.map((h) => ({ ...h, fromSiteSearch: true }));
          }
        } catch (_) {}
      }
      return [];
    }

    // series: SerienStream suggest
    try {
      const url = `${base}/api/search/suggest?term=${encodeURIComponent(q)}`;
      const res = await http.getText(url, headersJson);
      if (res?.status && res.status >= 200 && res.status < 300 && res.text) {
        const hits = parseSuggestJson(res.text, res.finalUrl || base, "series");
        if (hits.length) return hits;
      }
    } catch (_) {}

    // AniWorld ajax
    try {
      const body = `keyword=${encodeURIComponent(q)}`;
      let res;
      if (http.postText) {
        res = await http.postText(`${base}/ajax/search`, body, {
          ...headersJson,
          "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
          Origin: base,
        });
      } else {
        const r = await fetch(`${base}/ajax/search`, {
          method: "POST",
          headers: {
            ...headersJson,
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
          },
          body,
        });
        res = { status: r.status, finalUrl: r.url, text: await r.text() };
      }
      if (res?.status && res.status >= 200 && res.status < 300 && res.text) {
        const hits = parseSuggestJson(res.text, res.finalUrl || base, "series");
        if (hits.length) return hits;
      }
    } catch (_) {}

    for (const path of [
      `/suche?q=${encodeURIComponent(q)}`,
      `/search?q=${encodeURIComponent(q)}`,
    ]) {
      try {
        const res = await http.getText(`${base}${path}`, {
          Accept: "text/html,*/*",
          Referer: `${base}/`,
        });
        if (!(res?.status >= 200 && res.status < 300 && res.text)) continue;
        const doc = new DOMParser().parseFromString(res.text, "text/html");
        const seen = new Set();
        const hits = [];
        doc.querySelectorAll("a[href]").forEach((a) => {
          const href = a.getAttribute("href") || "";
          if (!isSeriesHref(href)) return;
          const detailPath = abs(res.finalUrl || base, href);
          if (seen.has(detailPath)) return;
          const title =
            a.querySelector("strong, .title, h3, h2")?.textContent ||
            a.getAttribute("title") ||
            a.textContent;
          const s = toItem(base, title, detailPath, null, "series");
          if (s) {
            seen.add(detailPath);
            hits.push(s);
          }
        });
        if (hits.length) return hits;
      } catch (_) {}
    }

    return [];
  }

  return {
    searchSite,
    parseSuggestJson,
    stripTags,
    isSeriesHref,
    isMovieHref,
    isEpisodeLike,
  };
})();
