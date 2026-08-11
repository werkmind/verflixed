/**
 * Live parity checks for catalog titles/covers vs SerienStream.cx markup
 * and TVMaze episode enrichment (no API key).
 */
const https = require("https");
const http = require("http");
const { URL } = require("url");
const fs = require("fs");
const path = require("path");
const { JSDOM } = (() => {
  try {
    return require("jsdom");
  } catch {
    return { JSDOM: null };
  }
})();

function get(url, redirects = 8) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === "http:" ? http : https;
    lib
      .get(
        url,
        {
          headers: {
            "User-Agent":
              "Mozilla/5.0 (Linux; Android 12; SHIELD Android TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            Accept: "*/*",
            Referer: `${u.origin}/`,
          },
        },
        (r) => {
          const chunks = [];
          r.on("data", (c) => chunks.push(c));
          r.on("end", async () => {
            const buf = Buffer.concat(chunks);
            if (
              [301, 302, 303, 307, 308].includes(r.statusCode) &&
              r.headers.location &&
              redirects > 0
            ) {
              return resolve(await get(new URL(r.headers.location, url).toString(), redirects - 1));
            }
            resolve({
              status: r.statusCode,
              finalUrl: url,
              buf,
              text: buf.toString("utf8"),
              headers: r.headers,
            });
          });
        },
      )
      .on("error", reject);
  });
}

function loadBrowserScripts() {
  if (!JSDOM) throw new Error("jsdom fehlt – npm i jsdom im webapp Ordner");
  const dom = new JSDOM("<!doctype html><html><body></body></html>", {
    url: "https://serienstream.cx/",
    runScripts: "outside-only",
  });
  const { window } = dom;
  global.window = window;
  global.document = window.document;
  global.DOMParser = window.DOMParser;
  const files = ["site-images.js", "catalog-parser.js", "tvmaze.js"];
  for (const f of files) {
    const code = fs.readFileSync(path.join(__dirname, "..", "renderer", f), "utf8");
    window.eval(code);
  }
  return window;
}

function preferJpegLike(url) {
  if (!url) return null;
  let u = String(url).trim();
  if (u.startsWith("//")) u = `https:${u}`;
  u = u
    .replace(/\/media\/images\/(channel|backdrop)\/(mobile|tablet|desktop)\//gi, "/media/images/$1/2x-desktop/")
    .replace(/\/media\/images\/(channel|backdrop)\/tile\//gi, "/media/images/$1/2x-tile/");
  if (/\/media\/images\//i.test(u)) {
    u = u.replace(/([?&])format=(avif|webp)/gi, "$1format=jpg");
    if (!/[?&]format=/i.test(u)) u += (u.includes("?") ? "&" : "?") + "format=jpg";
  }
  return u;
}

(async () => {
  const fails = [];
  const warn = [];
  console.log("[test] loading parser scripts…");
  let window;
  try {
    window = loadBrowserScripts();
  } catch (e) {
    console.error(e.message || e);
    process.exit(1);
  }

  console.log("[test] home catalog https://serienstream.cx …");
  const home = await get("https://serienstream.cx");
  const catalog = window.CatalogParser.parseCatalog(home.text, home.finalUrl);
  console.log(`[ok] parsed ${catalog.length} series`);
  if (catalog.length < 10) fails.push("catalog too small");

  const noisy = catalog.filter((s) =>
    /empfohlen|bewertung|brandneue|★|^\d{1,2}:\d{2}\b/i.test(s.title),
  );
  if (noisy.length) {
    fails.push(`noise titles: ${noisy.slice(0, 3).map((s) => s.title).join(" | ")}`);
  } else {
    console.log("[ok] no rating/timestamp noise titles");
  }
  console.log(
    "[ok] sample titles",
    catalog.slice(0, 6).map((s) => s.title),
  );

  const withArt = catalog.filter((s) => s.posterUrl || s.backdropUrl);
  console.log(`[ok] tiles with site art ${withArt.length}/${catalog.length}`);
  if (withArt.length < Math.min(8, catalog.length)) {
    warn.push("few covers on home – lazy detail resolve expected");
  }

  if (withArt[0]) {
    const art = withArt[0].backdropUrl || withArt[0].posterUrl;
    const upgraded = preferJpegLike(art);
    console.log("[test] probing cover", upgraded);
    const img = await get(upgraded);
    const ctype = img.headers["content-type"] || "";
    if (img.status !== 200 || !/^image\//i.test(ctype)) {
      fails.push(`cover probe HTTP ${img.status} ${ctype}`);
    } else {
      console.log(`[ok] cover ${img.status} ${ctype} ${img.buf.length}b`);
    }
  }

  console.log("[test] detail Silo…");
  const silo = await get("https://serienstream.cx/serie/stream/silo");
  const detail = window.CatalogParser.parseSeriesDetail(silo.text, silo.finalUrl, "silo");
  console.log("[ok] title", detail.title);
  console.log("[ok] poster", (detail.posterUrl || "").slice(0, 90));
  const s1 = detail.seasons.find((s) => s.number === 1) || detail.seasons[0];
  const titles = (s1?.episodes || []).slice(0, 4).map((e) => e.title);
  console.log("[ok] episode titles", titles);
  if (!detail.title || /stream/i.test(detail.title) && detail.title.length < 5) {
    fails.push("bad series title");
  }
  if (!titles.length) fails.push("no episodes");
  if (titles.every((t) => /^\d+$/.test(t) || /^Episode\s*\d+$/i.test(t))) {
    // may still be alphabet-only page before season hydrate – try staffel-1
    const st = await get(`${silo.finalUrl.replace(/\/$/, "")}/staffel-1`.replace("http:", "https:").replace("https://serienstream.cxhttp", "https"));
    // normalize
    const staffelUrl = new URL("staffel-1", silo.finalUrl.endsWith("/") ? silo.finalUrl : silo.finalUrl + "/").toString().replace(/^http:/, "https:");
    const stPage = await get(staffelUrl);
    const stDetail = window.CatalogParser.parseSeriesDetail(stPage.text, stPage.finalUrl, "silo");
    const eps = stDetail.seasons.find((s) => s.number === 1)?.episodes || [];
    const t2 = eps.slice(0, 4).map((e) => e.title);
    console.log("[ok] staffel-1 titles", t2);
    if (!t2.length || t2.every((t) => /^\d+$/.test(t))) {
      fails.push("episode titles still numeric after staffel-1");
    } else {
      Object.assign(detail, stDetail);
    }
  }

  if (detail.posterUrl) {
    const p = preferJpegLike(detail.posterUrl);
    const img = await get(p);
    console.log(`[ok] detail poster ${img.status} ${img.headers["content-type"]} ${img.buf.length}b`);
    if (img.status !== 200) fails.push("detail poster not 200");
  } else {
    fails.push("missing detail poster from site");
  }

  console.log("[test] TVMaze enrich…");
  // Polyfill fetch via getText for TvMaze in node
  window.verflixed = {
    getText: async (url) => {
      const r = await get(url);
      return { status: r.status, finalUrl: r.finalUrl, text: r.text };
    },
  };
  const enriched = await window.TvMaze.enrich(detail);
  const e1 = enriched.seasons.find((s) => s.number === 1)?.episodes?.[0];
  console.log("[ok] enriched ep1", e1?.title, (e1?.stillUrl || "").slice(0, 70));
  if (!e1?.title || /^\d+$/.test(e1.title)) fails.push("tvmaze title missing");
  if (!e1?.stillUrl) warn.push("tvmaze still missing");

  console.log("\n==== RESULT ====");
  if (fails.length) {
    console.log("[FAIL]", fails.join("; "));
    process.exit(1);
  }
  if (warn.length) console.log("[WARN]", warn.join("; "));
  console.log("[PASS] catalog/detail/cover/title parity checks OK");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
