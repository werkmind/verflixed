#!/usr/bin/env node
/**
 * Self-test: live site search hijack (SerienStream suggest + AniWorld ajax).
 * Expects SpongeBob on serienstream.cx and One Piece on aniworld.to.
 */
const { fetchText, fetchTextPost } = require("../voe-core");
const path = require("path");
const fs = require("fs");
const { JSDOM } = require("jsdom");

function loadSiteSearch() {
  const html = `<!doctype html><html><body></body></html>`;
  const dom = new JSDOM(html, { runScripts: "outside-only", url: "https://example.com/" });
  const { window } = dom;
  // Minimal CatalogParser stubs used by SiteSearch
  window.CatalogParser = {
    cleanTitle: (raw) =>
      String(raw || "")
        .replace(/\s*[-|–]\s*stream.*$/i, "")
        .replace(/\s+/g, " ")
        .trim(),
    slugId: (url, title) => {
      let pathName = "";
      try {
        pathName = new URL(url).pathname;
      } catch {
        pathName = url;
      }
      const m = pathName.match(/(\/(?:serie|series|anime\/stream)(?:\/stream)?\/[^/]+)/i);
      const root = (m && m[1]) || pathName;
      const slug = root.replace(/\/$/, "").split("/").pop() || title || "series";
      return String(slug)
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "");
    },
  };
  const src = fs.readFileSync(path.join(__dirname, "../renderer/site-search.js"), "utf8");
  window.eval(src);
  return window.SiteSearch;
}

async function main() {
  const SiteSearch = loadSiteSearch();
  const http = { getText: fetchText, postText: fetchTextPost };

  const ss = await SiteSearch.searchSite(http, "https://serienstream.cx", "spongebob");
  console.log(
    "serienstream spongebob:",
    ss.length,
    ss.map((s) => `${s.title} -> ${s.detailPath}`).slice(0, 5),
  );
  if (!ss.some((s) => /spongebob/i.test(s.id) || /spongebob/i.test(s.title))) {
    throw new Error("Expected SpongeBob hit on serienstream.cx");
  }

  const aw = await SiteSearch.searchSite(http, "https://aniworld.to", "one piece");
  console.log(
    "aniworld one piece:",
    aw.length,
    aw.map((s) => `${s.title} -> ${s.detailPath}`).slice(0, 3),
  );
  if (!aw.some((s) => /one-piece/i.test(s.id) || /one piece/i.test(s.title))) {
    throw new Error("Expected One Piece hit on aniworld.to");
  }

  // Suggest parser unit check
  const parsed = SiteSearch.parseSuggestJson(
    JSON.stringify({
      shows: [{ name: "SpongeBob Schwammkopf", url: "/serie/spongebob" }],
      people: [],
      genres: [],
    }),
    "https://serienstream.cx",
  );
  if (parsed[0]?.id !== "spongebob") throw new Error("parseSuggestJson failed");

  console.log("OK site-search");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
