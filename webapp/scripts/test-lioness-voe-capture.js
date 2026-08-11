/**
 * Lioness S01E01 – SerienStream /r?t= VOE blob + known embed claim.
 */
const {
  extractVoeHls,
  fetchText,
  isVoeEmbedUrl,
  findVoeEmbedInText,
  isPlayBlobUrl,
} = require("../voe-core");

const EPISODE =
  process.argv[2] ||
  "https://serienstream.cx/serie/special-ops-lioness/staffel-1/episode-1";
const KNOWN_EMBED =
  process.argv[3] || "https://nicolehappyoutside.com/e/szmisny2youx";

(async () => {
  const fails = [];
  console.log("[test] episode", EPISODE);
  const page = await fetchText(EPISODE);
  console.log("[ok] status", page.status, "final", page.finalUrl);

  const plays = [...page.text.matchAll(/data-play-url="([^"]+)"/g)].map((m) => m[1]);
  const providers = [
    ...page.text.matchAll(/data-provider-name="([^"]+)"/g),
  ].map((m) => m[1]);
  console.log("[ok] play-urls", plays.length, "providers", providers);

  const voeBlobs = plays.filter((u) => isPlayBlobUrl(u));
  if (!voeBlobs.length) fails.push("no /r?t= VOE blobs on episode page");
  else console.log("[ok] VOE play-blob", voeBlobs[0].slice(0, 64) + "…");

  // Initial HTML must NOT be required to contain /e/ – that's post-captcha
  const early = findVoeEmbedInText(page.text);
  console.log("[info] /e/ in initial HTML:", early || "(none – expected before gate)");

  if (!isVoeEmbedUrl(KNOWN_EMBED)) fails.push("isVoeEmbedUrl failed for known mirror");
  else console.log("[ok] isVoeEmbedUrl", KNOWN_EMBED);

  console.log("[test] claim known VOE embed → m3u8…");
  const claimed = await extractVoeHls(KNOWN_EMBED, page.finalUrl || EPISODE);
  if (!claimed.ok || !claimed.hls) {
    fails.push(`claim failed: ${claimed.error || "no hls"}`);
  } else {
    console.log("[ok] hls", claimed.hls.slice(0, 100) + "…");
    console.log("[ok] pageUrl", claimed.pageUrl);
    console.log("[ok] hops", claimed.hops);
  }

  console.log("\n==== RESULT ====");
  if (fails.length) {
    console.log("[FAIL]", fails.join("; "));
    process.exit(1);
  }
  console.log(
    "[PASS] Lioness VOE blob detected; known nicolehappyoutside /e/ claims to m3u8",
  );
  console.log(
    "[NOTE] Live /r?t=→/e/ needs Electron capture window (Turnstile) – use Play in app",
  );
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
