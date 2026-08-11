#!/usr/bin/env node
/**
 * Movie search / catalog against filmpalast.to (movies only, no SxxExx).
 */
const { fetchText } = require("../voe-core");
const path = require("path");
const fs = require("fs");
const { JSDOM } = require("jsdom");

function loadParsers() {
  const dom = new JSDOM("<!doctype html><html><body></body></html>", {
    url: "https://filmpalast.to/",
  });
  const { window } = dom;
  window.CatalogParser = {
    cleanTitle: (t) => String(t || "").replace(/\s+/g, " ").trim(),
    slugId: (url, title) => {
      const m = String(url).match(/\/stream\/([^/?#]+)/i);
      return (m && m[1]) || String(title || "x").toLowerCase().replace(/[^a-z0-9]+/g, "-");
    },
  };
  for (const f of ["film-parser.js", "site-search.js"]) {
    const src = fs.readFileSync(path.join(__dirname, "../renderer", f), "utf8");
    // Bind window explicitly (jsdom eval may not expose `window` identifier)
    Function("window", "document", "DOMParser", src)(
      window,
      window.document,
      window.DOMParser,
    );
  }
  return window;
}

(async () => {
  const w = loadParsers();
  const http = { getText: fetchText };

  const home = await fetchText("https://filmpalast.to/movies/new");
  const list = w.FilmParser.parseMovieList(home.text, home.finalUrl || "https://filmpalast.to/", {
    moviesOnly: true,
  });
  console.log("movies/new", list.length, list.slice(0, 3).map((m) => m.title));
  if (list.length < 5) throw new Error("expected movie list");
  if (list.some((m) => w.FilmParser.isEpisodeLike(m.title, m.detailPath))) {
    throw new Error("episode-like entries in movie list");
  }

  const detailUrl = list[0].detailPath;
  const page = await fetchText(detailUrl);
  const detail = w.FilmParser.parseMovieDetail(page.text, page.finalUrl || detailUrl, list[0].id);
  console.log("detail", detail.title, "hosts", detail.hosters.map((h) => h.name || h.provider));
  if (!detail.hosters.length) throw new Error("no hosters");
  if (detail.mediaKind !== "movie") throw new Error("mediaKind missing");

  const hits = await w.SiteSearch.searchSite(http, "https://filmpalast.to", "mission", {
    mediaKind: "movie",
  });
  console.log(
    "search mission",
    hits.length,
    hits.slice(0, 4).map((h) => h.title),
  );
  if (!hits.length) throw new Error("expected movie search hits");
  if (hits.some((h) => w.SiteSearch.isEpisodeLike(h.title, h.detailPath))) {
    throw new Error("search returned series episodes");
  }

  // series search must not use movie path accidentally
  const seriesHits = await w.SiteSearch.searchSite(http, "https://serienstream.cx", "spongebob", {
    mediaKind: "series",
  });
  console.log("series spongebob", seriesHits.length);
  if (!seriesHits.length) throw new Error("series search broken");

  console.log("OK movie-search");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
