/** Filmpalast-style movie catalog / detail parser (also used as movies-site template). */
window.FilmParser = (() => {
  const EP_RE = /\bS\d{1,2}E\d{1,3}\b/i;
  const STREAM_RE = /\/stream\/[^/?#]+/i;

  function abs(base, href) {
    if (!href) return "";
    try {
      if (href.startsWith("//")) return `https:${href}`;
      return new URL(href, base).toString();
    } catch {
      return href;
    }
  }

  function cleanTitle(raw) {
    return String(raw || "")
      .replace(/\s+/g, " ")
      .replace(/\*\d{4}\*/g, "")
      .trim();
  }

  function slugId(url, title) {
    let path = "";
    try {
      path = new URL(url).pathname;
    } catch {
      path = url;
    }
    const slug =
      (path.match(/\/stream\/([^/?#]+)/i) || [])[1] ||
      path.split("/").filter(Boolean).pop() ||
      title ||
      "movie";
    return String(slug)
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "");
  }

  function isEpisodeLike(title, url) {
    return EP_RE.test(title || "") || EP_RE.test(url || "");
  }

  function isMovieSite(baseUrl) {
    const h = String(baseUrl || "").toLowerCase();
    return /filmpalast|movie|film/.test(h);
  }

  /** Browse paths for movie catalogs on filmpalast-like sites. */
  function browsePaths() {
    return ["/movies/new", "/movies/top", "/"];
  }

  function parseMovieList(html, baseUrl, { moviesOnly = true } = {}) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const out = [];
    const seen = new Set();

    doc.querySelectorAll("article.liste, article.glowliste, article").forEach((art) => {
      const a =
        art.querySelector('h2 a[href*="/stream/"]') ||
        art.querySelector('a[href*="/stream/"]');
      if (!a) return;
      const href = abs(baseUrl, a.getAttribute("href") || "");
      if (!STREAM_RE.test(href) || seen.has(href)) return;
      const title = cleanTitle(a.getAttribute("title") || a.textContent);
      if (!title) return;
      if (moviesOnly && isEpisodeLike(title, href)) return;

      const img =
        art.querySelector("img.cover-opacity, img.cover2, a img[src*='/files/movies/']") ||
        art.querySelector("img[src*='/files/movies/']");
      const poster = img ? abs(baseUrl, img.getAttribute("src") || "") : null;
      const id = slugId(href, title);
      seen.add(href);
      out.push({
        id,
        title,
        posterUrl: poster,
        backdropUrl: poster,
        detailPath: href,
        mediaKind: "movie",
        overview: null,
        year: null,
        genres: [],
        seasons: [],
      });
    });

    // Fallback: naked stream anchors
    if (!out.length) {
      doc.querySelectorAll('a[href*="/stream/"]').forEach((a) => {
        const href = abs(baseUrl, a.getAttribute("href") || "");
        if (!STREAM_RE.test(href) || seen.has(href)) return;
        const title = cleanTitle(a.getAttribute("title") || a.textContent);
        if (!title || title.length < 2) return;
        if (moviesOnly && isEpisodeLike(title, href)) return;
        seen.add(href);
        out.push({
          id: slugId(href, title),
          title,
          posterUrl: null,
          backdropUrl: null,
          detailPath: href,
          mediaKind: "movie",
          overview: null,
          year: null,
          genres: [],
          seasons: [],
        });
      });
    }
    return out;
  }

  function parseMovieDetail(html, pageUrl, idHint) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const title =
      cleanTitle(doc.querySelector("article.detail h2, h2.bgDark, h2")?.textContent) ||
      cleanTitle(doc.querySelector('meta[itemprop="name"], .fn .value-title')?.getAttribute("title")) ||
      cleanTitle(doc.title);
    const overview =
      doc.querySelector('[itemprop="description"]')?.textContent?.trim() ||
      doc.querySelector("cite > span.hidden")?.textContent?.trim() ||
      null;
    const yearMatch = html.match(/Veröffentlicht:\s*(\d{4})/i);
    const year = yearMatch ? parseInt(yearMatch[1], 10) : null;
    const runtime =
      doc.querySelector("em") && /Spielzeit/i.test(html)
        ? (html.match(/Spielzeit:\s*<em>([^<]+)<\/em>/i) || [])[1]
        : null;

    const posterEl =
      doc.querySelector("img.cover2, img#img__" + (doc.querySelector("#viewID")?.getAttribute("data-id") || ""),) ||
      doc.querySelector('img[itemprop="image"], img[src*="/files/movies/"]');
    let poster = posterEl
      ? abs(pageUrl, posterEl.getAttribute("src") || posterEl.getAttribute("content") || "")
      : null;
    if (!poster) {
      const m = html.match(/\/files\/movies\/\d+\/[^"'\\\s>]+\.(?:jpg|jpeg|png|webp)/i);
      if (m) poster = abs(pageUrl, m[0]);
    }

    const genres = [];
    doc.querySelectorAll('#detail-content-list a[href*="/search/genre/"]').forEach((a) => {
      const g = cleanTitle(a.textContent);
      if (g && !genres.includes(g)) genres.push(g);
    });

    const hosters = parseHosters(html, pageUrl);
    const id = idHint || slugId(pageUrl, title);
    const movie = {
      id,
      title,
      overview,
      year,
      runtime,
      posterUrl: poster,
      backdropUrl: poster,
      detailPath: pageUrl,
      mediaKind: "movie",
      genres,
      hosters,
      seasons: [
        {
          number: 1,
          title: "Film",
          posterUrl: poster,
          backdropUrl: poster,
          episodes: [
            {
              id: `${id}-movie`,
              seriesId: id,
              seasonNumber: 1,
              number: 1,
              title: title || "Film",
              overview,
              stillUrl: poster,
              streamPageUrl: pageUrl,
              streamUrl: null,
            },
          ],
        },
      ],
    };
    return movie;
  }

  function parseHosters(html, pageUrl) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const hosters = [];
    doc.querySelectorAll("ul.currentStreamLinks").forEach((ul) => {
      const name = ul.querySelector(".hostName")?.textContent?.trim() || "Hoster";
      const a =
        ul.querySelector("a[data-player-url]") ||
        ul.querySelector("a.iconPlay[href], a.button[href]");
      if (!a) return;
      const raw = a.getAttribute("data-player-url") || a.getAttribute("href") || "";
      if (!raw || raw === "#") return;
      const url = abs(pageUrl, raw);
      const score =
        (window.SiteSearch?.scoreHosterName
          ? 0
          : 0) +
        (/voe/i.test(name) || /voe\.sx/i.test(url) ? 100 : 0) +
        (/vidara|vidnest/i.test(name) ? 70 : 0) +
        (/vidsonic/i.test(name) ? 40 : 0) +
        (/firestream/i.test(name) ? 30 : 0) +
        (/\bhd\b/i.test(name) ? 5 : 0);
      hosters.push({ provider: name, name, url, score, language: "" });
    });
    hosters.sort((a, b) => b.score - a.score);
    return hosters;
  }

  return {
    parseMovieList,
    parseMovieDetail,
    parseHosters,
    browsePaths,
    isMovieSite,
    isEpisodeLike,
    cleanTitle,
    slugId,
    abs,
  };
})();
