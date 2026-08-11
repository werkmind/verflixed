/** Catalog HTML parser – mirrors Android CatalogParser for SerienStream/AniWorld */
window.CatalogParser = (() => {
  const SI = () => window.SiteImages;
  const SERIES_ROOT =
    /(\/(?:serie|series|anime\/stream)\/(?:stream\/)?[^/]+)/i;

  function abs(base, href) {
    if (!href) return "";
    try {
      return new URL(href, base).toString();
    } catch {
      return href;
    }
  }

  function cleanTitle(raw) {
    return String(raw || "")
      .replace(/\s*[-|–]\s*stream.*$/i, "")
      .replace(/\s+staffel\s*\d+.*$/i, "")
      .replace(/\s+season\s*\d+.*$/i, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  function slugId(url, title) {
    let path = "";
    try {
      path = new URL(url).pathname;
    } catch {
      path = url;
    }
    const root = (path.match(SERIES_ROOT) || [])[1] || path;
    const slug =
      root.replace(/\/$/, "").split("/").pop() ||
      title ||
      "series";
    return String(slug)
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "");
  }

  function prettySlugTitle(slug) {
    return String(slug || "")
      .replace(/-/g, " ")
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  function imageAbs(el, base) {
    if (!el) return null;
    const srcset =
      el.getAttribute("data-srcset") ||
      el.getAttribute("srcset") ||
      "";
    const fromSet = SI().fromSrcset(srcset);
    if (fromSet) return abs(base, fromSet);

    const raw =
      el.getAttribute("data-src") ||
      el.getAttribute("src") ||
      "";
    if (!raw || raw.startsWith("data:") || /\.svg(\?|$)/i.test(raw)) return null;
    return SI().preferJpeg(abs(base, raw));
  }

  function collectImgs(root, base) {
    const urls = [];
    root.querySelectorAll("img[data-src], img[src], img[srcset], img[data-srcset], picture source").forEach((el) => {
      const u = imageAbs(el, base);
      if (u) urls.push(u);
    });
    return urls;
  }

  function scoreOverview(text) {
    let score = 0;
    const lower = String(text || "").toLowerCase();
    if (lower.startsWith("schaue ")) score += 80;
    if (lower.includes("staffel") && lower.includes("stream")) score += 40;
    if (lower.includes("alle episoden")) score += 30;
    if (text.length < 80) score += 20;
    return score;
  }

  function isNoiseTitle(t) {
    const s = String(t || "").trim();
    if (s.length < 2) return true;
    if (/^(stream|serie|series|home|mehr|alle)$/i.test(s)) return true;
    if (/empfohlen|bewertung|brandneue|episode\s*\★|★/i.test(s)) return true;
    if (/^\d{1,2}:\d{2}\b/.test(s)) return true; // "07:41 Ted…"
    if (/^[SE]\d+$/i.test(s)) return true;
    return false;
  }

  function parseCatalog(html, baseUrl) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const seen = new Map();

    function upsert(href, titleRaw, poster, backdrop) {
      let path = "";
      try {
        path = new URL(href, baseUrl).pathname;
      } catch {
        return;
      }
      const rootMatch = path.match(SERIES_ROOT);
      if (!rootMatch) return;
      const rootPath = rootMatch[1];
      if (/^\/(?:serie|series)\/stream\/?$/i.test(rootPath)) return;
      const origin = (() => {
        try {
          return new URL(href, baseUrl).origin;
        } catch {
          return baseUrl.replace(/\/$/, "");
        }
      })();
      const rootHref = `${origin}${rootPath}`;
      let title = cleanTitle(titleRaw);
      if (!title || isNoiseTitle(title)) {
        title = prettySlugTitle(rootPath.split("/").pop());
      }
      if (title.length < 2 || /^stream$/i.test(title)) return;
      const id = slugId(rootHref, title);
      if (!seen.has(id)) {
        seen.set(id, {
          id,
          title,
          posterUrl: poster || null,
          backdropUrl: backdrop || null,
          detailPath: rootHref,
        });
      } else {
        const prev = seen.get(id);
        seen.set(id, {
          ...prev,
          title: isNoiseTitle(prev.title) ? title : prev.title,
          posterUrl: prev.posterUrl || poster || null,
          backdropUrl: prev.backdropUrl || backdrop || null,
        });
      }
    }

    const selectors = [
      ".card-mini",
      ".card-mini-tile",
      "[data-series]",
      ".series-item",
      ".series",
      "article.series",
      ".filmList .coverListItem",
      ".coverListItem",
      ".home-hero-thumb",
      ".cover",
      "a[href*='/serie/']",
      "a[href*='/series/']",
      "a[href*='/anime/stream/']",
    ].join(", ");

    doc.querySelectorAll(selectors).forEach((el) => {
      const anchor =
        el.tagName === "A"
          ? el
          : el.querySelector("a[href*='/serie/'], a[href*='/series/'], a[href*='/anime/stream/']");
      if (!anchor) return;
      const href = abs(baseUrl, anchor.getAttribute("href") || "");
      if (!href) return;
      if (!/\/(serie|series|anime\/stream)\b/i.test(href)) return;
      // Skip season/episode deep links as primary cards unless we can root them
      const path = new URL(href).pathname;
      if (/(episode|folge|staffel|season)/i.test(path) && !SERIES_ROOT.test(path)) return;

      let title =
        el.getAttribute("data-title") ||
        el.querySelector("h1,h2,h3,h4,.title,.name,.ep-title")?.getAttribute("title") ||
        el.querySelector("h1 span, h2 span, h3 span, h4 span, .title, .name")?.textContent ||
        anchor.getAttribute("title") ||
        anchor.querySelector("img[alt]")?.getAttribute("alt") ||
        "";
      title = cleanTitle(title);
      if (!title || isNoiseTitle(title)) {
        title = cleanTitle(
          anchor.querySelector("h3,h4,span")?.getAttribute("title") ||
            anchor.querySelector("h3 span, h4 span")?.textContent ||
            "",
        );
      }
      // Never use full anchor text (includes badges / ratings / timestamps)
      if (!title || isNoiseTitle(title)) {
        const slug = (path.match(SERIES_ROOT) || [])[1]?.split("/").pop();
        title = prettySlugTitle(slug);
      }

      const scope = el.closest(".card-mini, .card-mini-tile, .home-hero-thumb, .cover, article, .col-6, .col-md-3") || el;
      const urls = collectImgs(scope, baseUrl);
      // also parent picture for card-mini where anchor is sibling of picture
      const parent = el.parentElement;
      if (parent) urls.push(...collectImgs(parent, baseUrl));

      upsert(href, title, SI().pickPoster(...urls), SI().pickBackdrop(...urls));
    });

    return [...seen.values()].filter((s) => s.title && s.title.length > 1);
  }

  function parseSeriesDetail(html, pageUrl, seriesId) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const title = cleanTitle(
      doc.querySelector("h1, .series-title, .title")?.textContent || seriesId,
    );

    const channelEls = [
      ...doc.querySelectorAll(
        "img[data-src*='/channel/'], img[src*='/channel/'], source[srcset*='/channel/'], source[data-srcset*='/channel/'], img.poster, .seriesCoverImg img, .poster img, picture source",
      ),
    ];
    const channelUrls = channelEls.map((el) => imageAbs(el, pageUrl)).filter(Boolean);
    const metaImage = SI().preferJpeg(
      abs(
        pageUrl,
        doc.querySelector("meta[property='og:image']")?.getAttribute("content") || "",
      ),
    );
    const backdropEls = [
      ...doc.querySelectorAll(
        "img[data-src*='/backdrop/'], img[src*='/backdrop/'], source[srcset*='/backdrop/'], source[data-srcset*='/backdrop/']",
      ),
    ];
    const backdropUrls = backdropEls.map((el) => imageAbs(el, pageUrl)).filter(Boolean);

    const poster = SI().pickPoster(...channelUrls, metaImage);
    const backdrop = SI().pickBackdrop(...backdropUrls, metaImage, ...channelUrls);

    const bodyDescription =
      doc.querySelector(
        ".description-text, .series-description, #description, .description, [itemprop=description], [id*=series-desc]",
      )?.textContent?.trim() || "";
    const ogDescription =
      doc.querySelector("meta[property='og:description']")?.getAttribute("content")?.trim() || "";
    const metaDescription =
      doc.querySelector("meta[name=description]")?.getAttribute("content")?.trim() || "";
    const overviewCandidates = [bodyDescription, ogDescription, metaDescription].filter(
      (t) => t && t.length > 40,
    );
    const overview =
      overviewCandidates.sort((a, b) => scoreOverview(a) - scoreOverview(b))[0] ||
      [bodyDescription, ogDescription, metaDescription].find((t) => t && t.length > 20) ||
      "";

    const seasons = new Map();

    function addEpisode(ep) {
      if (!ep.seasonNumber || ep.seasonNumber <= 0 || !ep.number) return;
      const list = seasons.get(ep.seasonNumber) || [];
      if (list.some((e) => e.number === ep.number)) return;
      list.push(ep);
      seasons.set(ep.seasonNumber, list);
    }

    // Modern SerienStream.cx episode rows
    doc.querySelectorAll("tr.episode-row, .episode-row").forEach((row) => {
      const onclick = row.getAttribute("onclick") || "";
      const link =
        row.querySelector("a[href*='episode'], a[href*='folge']")?.getAttribute("href") ||
        (onclick.match(/['"]([^'"]+(?:episode|folge)[^'"]+)['"]/) || [])[1] ||
        "";
      const href = abs(pageUrl, link);
      const seasonNo =
        Number(row.getAttribute("data-season")) ||
        Number((href.match(/(?:staffel|season)[/-]?(\d+)/i) || [])[1]) ||
        guessSeason(row.textContent + " " + href, doc);
      const epNo =
        Number(row.getAttribute("data-episode")) ||
        Number(row.querySelector(".episode-number-cell")?.textContent) ||
        Number((href.match(/(?:episode|folge|ep)[/-]?(\d+)/i) || [])[1]);
      if (!epNo) return;
      const ger =
        row.querySelector(".episode-title-ger")?.getAttribute("title") ||
        row.querySelector(".episode-title-ger")?.textContent ||
        "";
      const eng =
        row.querySelector(".episode-title-eng")?.getAttribute("title") ||
        row.querySelector(".episode-title-eng")?.textContent ||
        "";
      const epTitle =
        cleanTitle(ger) ||
        cleanTitle(eng) ||
        cleanTitle(row.querySelector(".episode-title-cell, .title, .episode-title")?.textContent) ||
        `Episode ${epNo}`;
      const still = imageAbs(row.querySelector("img[data-src], img[src], img[srcset]"), pageUrl);
      addEpisode({
        id: `${seriesId}-s${seasonNo}e${epNo}`,
        seriesId,
        seasonNumber: seasonNo,
        number: epNo,
        title: epTitle,
        stillUrl: still,
        streamPageUrl: href || null,
      });
    });

    // Generic episode nodes
    doc
      .querySelectorAll(
        "[data-episode], .episode, .episodeItem, tr.episode, .seasonEpisodesList tr, .episodes tr",
      )
      .forEach((epEl) => {
        if (epEl.classList?.contains("episode-row")) return;
        const seasonNo =
          Number(epEl.getAttribute("data-season")) ||
          Number(epEl.querySelector("[data-season]")?.getAttribute("data-season")) ||
          guessSeason(
            epEl.textContent + " " + (epEl.querySelector("a[href]")?.getAttribute("href") || ""),
            doc,
          );
        const href =
          epEl.querySelector("a[href*='episode'], a[href*='folge'], a[href]")?.getAttribute("href") ||
          "";
        const absHref = abs(pageUrl, href);
        const epNo =
          Number(epEl.getAttribute("data-episode")) ||
          Number((epEl.textContent.match(/(?:episode|folge|ep)[^\d]*(\d+)/i) || [])[1]) ||
          Number((absHref.match(/(?:episode|folge|ep)[/-]?(\d+)/i) || [])[1]);
        if (!epNo || seasonNo <= 0) return;
        const epTitle =
          cleanTitle(epEl.getAttribute("data-title")) ||
          cleanTitle(
            epEl.querySelector(".title, .episode-title, .episode-title-ger, td:nth-child(2)")
              ?.textContent,
          ) ||
          `Episode ${epNo}`;
        const still = imageAbs(
          epEl.querySelector("img[data-src], img[src], img[srcset]"),
          pageUrl,
        );
        addEpisode({
          id: `${seriesId}-s${seasonNo}e${epNo}`,
          seriesId,
          seasonNumber: seasonNo,
          number: epNo,
          title: epTitle,
          stillUrl: still,
          streamPageUrl: absHref || null,
        });
      });

    // Fallback: crawl episode links (skip alphabet pills that are just "1")
    if (seasons.size === 0) {
      doc.querySelectorAll("a[href*='episode'], a[href*='folge']").forEach((a) => {
        const href = abs(pageUrl, a.getAttribute("href") || "");
        const seasonNo = Number((href.match(/(?:staffel|season)[/-]?(\d+)/i) || [])[1]) || 1;
        const epNo = Number((href.match(/(?:episode|folge|ep)[/-]?(\d+)/i) || [])[1]);
        if (!epNo) return;
        let epTitle = cleanTitle(a.getAttribute("title") || a.textContent);
        if (!epTitle || /^\d+$/.test(epTitle) || isNoiseTitle(epTitle)) {
          epTitle = `Episode ${epNo}`;
        }
        addEpisode({
          id: `${seriesId}-s${seasonNo}e${epNo}`,
          seriesId,
          seasonNumber: seasonNo,
          number: epNo,
          title: epTitle,
          stillUrl: null,
          streamPageUrl: href,
        });
      });
    }

    const seasonList = [...seasons.entries()]
      .filter(([n, eps]) => n > 0 && eps.length)
      .sort((a, b) => a[0] - b[0])
      .map(([n, eps]) => ({
        number: n,
        title: `Staffel ${n}`,
        episodes: eps.sort((a, b) => a.number - b.number),
      }));

    // Strip stills that are just series art
    for (const season of seasonList) {
      for (const ep of season.episodes) {
        if (
          ep.stillUrl &&
          (ep.stillUrl === poster ||
            ep.stillUrl === backdrop ||
            /\/media\/images\/channel\//i.test(ep.stillUrl))
        ) {
          ep.stillUrl = null;
        }
      }
    }

    return {
      id: seriesId,
      title,
      overview,
      posterUrl: poster,
      backdropUrl: backdrop || poster,
      detailPath: pageUrl,
      seasons: seasonList,
    };
  }

  function guessSeason(text, doc) {
    const m =
      String(text).match(/(?:staffel|season)[/-]?(\d+)/i) ||
      String(text).match(/(?:staffel|season)\s*(\d+)/i) ||
      (doc.title || "").match(/(?:staffel|season)\s*(\d+)/i);
    return m ? Number(m[1]) : 1;
  }

  function discoverSeasonUrls(html, pageUrl) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const map = new Map();
    doc.querySelectorAll("a[href*='staffel'], a[href*='season']").forEach((a) => {
      const href = abs(pageUrl, a.getAttribute("href") || "");
      const n =
        Number((href.match(/(?:staffel|season)[/-]?(\d+)/i) || [])[1]) ||
        Number((a.textContent.match(/(?:staffel|season)\s*(\d+)/i) || [])[1]);
      if (!n || n <= 0 || !href) return;
      const isEpisode = /episode|folge/i.test(href);
      if (!isEpisode) map.set(n, href);
      else if (!map.has(n)) {
        map.set(
          n,
          href.replace(/\/(?:episode|folge)\/?.*$/i, "") || href,
        );
      }
    });
    doc.querySelectorAll("[data-season-url], [data-season]").forEach((el) => {
      const n = Number(el.getAttribute("data-season"));
      const u = el.getAttribute("data-season-url");
      if (n > 0 && u) map.set(n, abs(pageUrl, u));
    });
    return [...map.entries()].sort((a, b) => a[0] - b[0]);
  }

  const GENRES = [
    { id: "action", label: "Action" },
    { id: "comedy", label: "Comedy" },
    { id: "drama", label: "Drama" },
    { id: "krimi", label: "Krimi" },
    { id: "thriller", label: "Thriller" },
    { id: "fantasy", label: "Fantasy" },
    { id: "science-fiction", label: "Sci-Fi" },
    { id: "horror", label: "Horror" },
    { id: "anime", label: "Anime" },
    { id: "animation", label: "Animation" },
    { id: "dokumentation", label: "Doku" },
    { id: "romantik", label: "Romantik" },
    { id: "mystery", label: "Mystery" },
    { id: "k-drama", label: "K-Drama" },
  ];

  return {
    parseCatalog,
    parseSeriesDetail,
    discoverSeasonUrls,
    cleanTitle,
    slugId,
    GENRES,
    SERIES_ROOT,
  };
})();
