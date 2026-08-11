/**
 * Filmpalast / VOE-share resolve tests.
 *
 * Filmpalast VOE links are usually: https://voe.sx/{id}
 *   → JS "Redirecting…" → https://{mirror}/{id}
 *   → VOE JSON payload → .m3u8
 *
 * From some cloud/VPS countries Filmpalast VOE files are geo-blocked
 * ("Dateizugriff verweigert – Zugang zu Ihrem Land eingeschränkt").
 * Then we fall back to Vidara (/api/stream) etc.
 *
 * Usage:
 *   node scripts/test-filmpalast-voe-bg.js                  # HTTP + Vidara
 *   npx electron scripts/test-filmpalast-voe-bg.js --bg     # also hidden window
 */
const path = require("path");

const WANT_BG = process.argv.includes("--bg") || process.argv.includes("--electron");
const isElectron = !!(process.versions && process.versions.electron);

const {
  extractVoeHls,
  extractVidaraHls,
  fetchText,
  findRedirect,
  isVoeGeoBlocked,
  scoreHosterName,
} = require("../voe-core");

function parseHosts(html) {
  const hosts = [];
  for (const block of html.matchAll(/<ul class="currentStreamLinks">([\s\S]*?)<\/ul>/g)) {
    const name = (block[1].match(/class="hostName">([^<]+)/) || [])[1];
    const play =
      (block[1].match(/data-player-url="([^"]+)"/) ||
        block[1].match(/href="(https?:\/\/[^"]+)"/) ||
        [])[1];
    if (name && play) {
      hosts.push({ name: name.trim(), provider: name.trim(), url: play.trim() });
    }
  }
  return hosts.sort(
    (a, b) => scoreHosterName(b.name, b.url) - scoreHosterName(a.name, a.url),
  );
}

async function runHttpTests() {
  const fails = [];

  // A) Known-good VOE share (SerienStream Lioness id) – soft redirect must work
  const SHARE = "https://voe.sx/szmisny2youx";
  console.log("\n[A] soft-redirect claim", SHARE);
  const soft = await extractVoeHls(SHARE, "https://serienstream.cx/");
  if (!soft.ok || !soft.hls) {
    fails.push(`A soft-redirect failed: ${soft.error}`);
    console.log("[FAIL]", soft.error, soft.hops);
  } else {
    console.log("[ok] hops", soft.hops);
    console.log("[ok] hls", soft.hls.slice(0, 100) + "…");
  }

  // B) Filmpalast movie page – VOE often geo-blocked here; Vidara fallback
  const MOVIE = "https://filmpalast.to/stream/censor";
  console.log("\n[B] filmpalast hosts", MOVIE);
  const page = await fetchText(MOVIE);
  const hosts = parseHosts(page.text);
  console.log(
    "[ok] ordered",
    hosts.map((h) => `${h.name}(${scoreHosterName(h.name)})`).join(" > "),
  );

  const voe = hosts.find((h) => /voe/i.test(h.name));
  const vidara = hosts.find((h) => /vidara|vidnest/i.test(h.name));

  if (voe) {
    console.log("\n[B1] VOE share", voe.url);
    const r = await extractVoeHls(voe.url, MOVIE);
    console.log(
      r.ok ? "[ok]" : "[info]",
      r.ok ? r.hls.slice(0, 80) + "…" : r.error,
      "hops",
      r.hops,
    );
    if (r.geoBlocked) {
      console.log(
        "[note] VOE geo-blocked from this IP/country – expected on many cloud VMs",
      );
    }
  } else {
    fails.push("no VOE host on filmpalast page");
  }

  if (vidara) {
    console.log("\n[B2] Vidara fallback", vidara.url);
    const r = await extractVidaraHls(vidara.url, MOVIE);
    if (!r.ok || !r.hls) {
      fails.push(`Vidara failed: ${r.error}`);
      console.log("[FAIL]", r.error);
    } else {
      console.log("[ok] vidara hls", r.hls.slice(0, 100) + "…");
    }
  } else {
    console.log("[info] no Vidara host on this title");
  }

  // C) findRedirect unit
  const redirectHtml =
    "<html><title>Redirecting...</title><script>window.location = 'https://nicolehappyoutside.com/szmisny2youx';</script></html>";
  const next = findRedirect(redirectHtml);
  if (next !== "https://nicolehappyoutside.com/szmisny2youx") {
    fails.push(`findRedirect soft target wrong: ${next}`);
  } else {
    console.log("\n[C] findRedirect soft OK", next);
  }

  return fails;
}

async function runBgTest() {
  if (!isElectron) {
    console.log("\n[BG] skipped (run with: npx electron scripts/test-filmpalast-voe-bg.js --bg)");
    return [];
  }
  const { app } = require("electron");
  const { resolveVoeShareToHls } = require("../voe-capture");

  // Keep process alive while we destroy temporary capture windows
  app.on("window-all-closed", (e) => {
    /* no quit during tests */
  });

  await app.whenReady();
  const fails = [];

  // Force background path on working share
  console.log("\n[BG] forceBackground on working share");
  const r = await resolveVoeShareToHls("https://voe.sx/szmisny2youx", {
    referer: "https://serienstream.cx/",
    timeoutMs: 45000,
    forceBackground: true,
  });
  console.log("[BG]", r.ok, r.method, r.hlsUrl && r.hlsUrl.slice(0, 90));
  if (!r.ok) fails.push(`BG resolve failed: ${r.error}`);

  // Filmpalast VOE – expect geo fail or success depending on region
  console.log("\n[BG] filmpalast VOE share (HTTP then geo short-circuit / window)");
  const page = await fetchText("https://filmpalast.to/stream/censor");
  const voe = parseHosts(page.text).find((h) => /voe/i.test(h.name));
  if (voe) {
    const fr = await resolveVoeShareToHls(voe.url, {
      referer: "https://filmpalast.to/stream/censor",
      timeoutMs: 20000,
    });
    console.log(
      "[BG-fp]",
      fr.ok,
      fr.method,
      fr.geoBlocked ? "GEO-BLOCKED" : "",
      fr.error || (fr.hlsUrl && fr.hlsUrl.slice(0, 80)),
    );
  }

  return fails;
}

(async () => {
  const fails = await runHttpTests();
  if (WANT_BG || isElectron) {
    fails.push(...(await runBgTest()));
    if (isElectron) {
      const { app } = require("electron");
      console.log("\n==== RESULT ====");
      if (fails.length) {
        console.log("[FAIL]", fails.join("; "));
        app.exit(1);
      } else {
        console.log("[PASS] VOE share soft-redirect + fallbacks OK");
        app.exit(0);
      }
      return;
    }
  }

  console.log("\n==== RESULT ====");
  if (fails.length) {
    console.log("[FAIL]", fails.join("; "));
    process.exit(1);
  }
  console.log("[PASS] VOE share soft-redirect + Vidara fallback OK");
  console.log("[HINT] For hidden BrowserWindow: npx electron scripts/test-filmpalast-voe-bg.js --bg");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
