/** Filmpalast-style movie catalog / detail parser (also used as movies-site template). */
window.FilmParser = (() => {
  const EP_RE = /\bS\d{1,2}E\d{1,3}\b/i;
  const STREAM_RE = /\/stream\/[^/?#]+/i;
  const SL = () => window.StreamLanguage;

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

  function browsePaths() {
    return ["/movies/new", "/movies/top", "/"];
  }

  function detectPageLanguage(html, title = "", pageUrl = "") {
    const releaseMatch = String(html || "").match(
      /id=["']release_text["'][^>]*>([\s\S]*?)</i,
    );
    const release = (releaseMatch?.[1] || "").replace(/<[^>]+>/g, " ");
    const metaMatch = String(html || "").match(
      /itemprop=["']inLanguage["'][^>]*content=["']([^"']+)["']/i,
    );
    return (
      SL()?.detectFromText?.(pageUrl, title, release, metaMatch?.[1]) ||
      SL()?.DE ||
      "de"
    );
  }

  /** Suggest sibling Filmpalast URLs for the other language (DE↔EN). */
  function siblingLanguageUrls(pageUrl, currentLang, html = "") {
    let uri;
    try {
      uri = new URL(pageUrl);
    } catch {
      return [];
    }
    const path = uri.pathname || "";
    const m = path.match(/^(.*?\/stream\/)([^/?#]+)\/?$/i);
    if (!m) return [];
    const prefix = m[1];
    const slug = m[2];
    const candidates = new Set();
    const lang = SL()?.normalize?.(currentLang) || "de";
    const pageNorm = pageUrl.replace(/\/$/, "");

    if (html) {
      const wantEn = lang !== "en";
      const re = /(?:href|src)=["']?(?:\/\/filmpalast\.to)?(\/stream\/[a-z0-9\-]+)/gi;
      let hit;
      while ((hit = re.exec(html))) {
        const absUrl = abs(pageUrl, hit[1]).replace(/\/$/, "");
        if (!absUrl || absUrl === pageNorm) continue;
        const looksEn = /-english|-eng/i.test(absUrl);
        if (wantEn === looksEn) candidates.add(absUrl);
      }
    }

    const origin = `${uri.protocol}//${uri.host}`;
    const mk = (s) => `${origin}${prefix}${s}`.replace(/\/$/, "");
    if (lang === "en") {
      const stripped = slug
        .replace(/-english$/i, "")
        .replace(/-eng$/i, "")
        .replace(/-ovo$/i, "");
      if (stripped && stripped !== slug) candidates.add(mk(stripped));
      const singular = stripped.replace(/s$/, "");
      if (singular && singular !== stripped) candidates.add(mk(singular));
    } else {
      candidates.add(mk(`${slug}-english`));
      candidates.add(mk(`${slug}s-english`));
      candidates.add(mk(`${slug}-eng`));
      if (slug.endsWith("s")) candidates.add(mk(`${slug.slice(0, -1)}-english`));
    }
    return [...candidates].filter((u) => u !== pageNorm);
  }

  function languageFromMovieHit(title, url) {
    return detectPageLanguage("", title, url);
  }

  function scoreHoster(name, url = "", language = "", preferredLang = "de") {
    const n = `${name} ${url}`.toLowerCase();
    let s = 0;
    if (n.includes("firestream")) s += 120;
    if (/\bvoe\b/.test(n) || n.includes("voe.sx")) s += 100;
    if (/vidara|vidnest/.test(n)) s += 70;
    if (n.includes("vidsonic")) s += 40;
    if (n.includes("playmate")) s += 20;
    if (/\bhd\b/.test(n)) s += 5;
    if (language) {
      if (SL()?.matchesPreferred?.(language, preferredLang)) s += 80;
      else s -= 40;
    }
    return s;
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
        availableLanguages: [],
        languagePages: {},
      });
    });

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
          availableLanguages: [],
          languagePages: {},
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
      doc.querySelector("img.cover2") ||
      doc.querySelector('img[itemprop="image"], img[src*="/files/movies/"]');
    let poster = posterEl
      ? abs(pageUrl, posterEl.getAttribute("src") || posterEl.getAttribute("content") || "")
      : null;
    if (!poster) {
      const m = html.match(/\/files\/movies\/\d+\/[^"'\\\s>]+\.(?:jpg|jpeg|png|webp)/i);
      if (m) poster = abs(pageUrl, m[0]);
    }

    const pageLang = detectPageLanguage(html, title, pageUrl);
    const genres = [];
    doc.querySelectorAll('#detail-content-list a[href*="/search/genre/"]').forEach((a) => {
      const g = cleanTitle(a.textContent);
      if (g && !genres.includes(g)) genres.push(g);
    });
    const langLabel = SL()?.label?.(pageLang) || "Deutsch";
    if (!genres.some((g) => /deutsch|englisch/i.test(g))) {
      genres.unshift(langLabel);
    }

    const preferred = SL()?.normalize?.(
      window.VfProfiles?.streamLanguage?.() || "de",
    ) || "de";
    const hosters = parseHosters(html, pageUrl, preferred);
    const id = idHint || slugId(pageUrl, title);
    return {
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
      availableLanguages: [pageLang],
      languagePages: { [pageLang]: pageUrl },
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
  }

  function parseHosters(html, pageUrl, preferredLang = "de") {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const pageLang = detectPageLanguage(
      html,
      cleanTitle(doc.querySelector("article.detail h2, h2.bgDark, h2")?.textContent),
      pageUrl,
    );
    const hosters = [];
    doc.querySelectorAll("ul.currentStreamLinks").forEach((ul) => {
      const name = ul.querySelector(".hostName")?.textContent?.trim() || "Hoster";
      const a =
        [...ul.querySelectorAll("a[data-player-url]")].find((x) =>
          (x.getAttribute("data-player-url") || "").trim(),
        ) ||
        [...ul.querySelectorAll("a.iconPlay[href], a.button.iconPlay[href], a.button[href]")].find(
          (x) => {
            const h = x.getAttribute("href") || "";
            return h && h !== "#" && !/javascript:/i.test(h);
          },
        );
      if (!a) return;
      const raw = a.getAttribute("data-player-url") || a.getAttribute("href") || "";
      if (!raw || raw === "#") return;
      const url = abs(pageUrl, raw);
      const hosterLang =
        a.getAttribute("data-language-label") ||
        a.getAttribute("data-language") ||
        ul.getAttribute("data-language") ||
        pageLang;
      const score = scoreHoster(name, url, hosterLang, preferredLang);
      hosters.push({ provider: name, name, url, score, language: hosterLang });
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
    detectPageLanguage,
    siblingLanguageUrls,
    languageFromMovieHit,
    scoreHoster,
  };
})();
