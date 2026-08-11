#!/usr/bin/env node
/**
 * Live VOE → m3u8 claim self-test (same logic as Android VoeExtractor / webapp).
 * CDN fetch may 403 from datacenter IPs; claim success is the required gate.
 */
const { extractVoeHls, request } = require("../voe-core");

async function findVoeFromAniworld() {
  const home = await request("https://aniworld.to/");
  const series = [
    ...home.text.matchAll(/href="(\/anime\/stream\/[^"#?]+)"/g),
  ]
    .map((m) => m[1])
    .filter((p) => !/episode|staffel/i.test(p));
  const uniq = [...new Set(series)].slice(0, 15);
  for (const s of uniq) {
    const page = await request(`https://aniworld.to${s}`);
    const eps = [
      ...page.text.matchAll(/href="([^"]*staffel-\d+\/episode-\d+)"/gi),
    ].map((m) => m[1]);
    if (!eps.length) continue;
    const epPath = eps[0].startsWith("http")
      ? eps[0]
      : `https://aniworld.to${eps[0]}`;
    const ep = await request(epPath, {
      headers: { Referer: `https://aniworld.to${s}` },
    });
    // Prefer redirect next to VOE icon/label
    let redirect = null;
    for (const m of ep.text.matchAll(
      /href="(\/redirect\/\d+)"[\s\S]{0,220}?icon VOE|icon VOE[\s\S]{0,220}?href="(\/redirect\/\d+)"|Hoster VOE[\s\S]{0,200}?href="(\/redirect\/\d+)"/gi,
    )) {
      redirect = m[1] || m[2] || m[3];
      if (redirect) break;
    }
    if (!redirect) {
      const all = [...ep.text.matchAll(/href="(\/redirect\/\d+)"/g)].map((m) => m[1]);
      redirect = all[0];
    }
    if (!redirect) continue;

    const r = await request(`https://aniworld.to${redirect}`, {
      headers: { Referer: epPath },
    });
    let voe = r.finalUrl;
    if (!/\/e\//.test(voe)) {
      const m =
        r.text.match(
          /(?:location\.href|window\.location(?:\.href)?)\s*=\s*['"]([^'"]+)['"]/,
        ) || r.text.match(/https?:\/\/[^'"\s]+\/e\/[a-zA-Z0-9]+/);
      voe = m ? m[1] || m[0] : null;
    }
    if (voe && /\/e\//.test(voe)) {
      return { series: s, episode: epPath, redirect, voe };
    }
  }
  throw new Error("No VOE redirect found on AniWorld sample");
}

async function main() {
  console.log("[test] discovering live VOE embed via AniWorld…");
  const found = await findVoeFromAniworld();
  console.log("[ok] series", found.series);
  console.log("[ok] episode", found.episode);
  console.log("[ok] voe", found.voe);

  console.log("[test] extracting HLS…");
  const claimed = await extractVoeHls(found.voe, found.episode);
  if (!claimed.ok || !claimed.hls || !/\.m3u8/i.test(claimed.hls)) {
    console.error("[FAIL] extract", claimed);
    process.exit(2);
  }
  console.log("[ok] hls", claimed.hls.slice(0, 140) + "…");
  console.log("[ok] pageUrl", claimed.pageUrl);
  console.log("[ok] hops", claimed.hops);
  console.log("[ok] mp4Fallbacks", (claimed.mp4Fallbacks || []).length);

  console.log("[test] probing CDN playlist (may 403 on datacenter IPs)…");
  const pl = await request(claimed.hls, {
    headers: {
      Referer: claimed.pageUrl || "https://voe.sx/",
      Origin: (() => {
        try {
          return new URL(claimed.pageUrl || "https://voe.sx/").origin;
        } catch {
          return "https://voe.sx";
        }
      })(),
      Accept: "*/*",
    },
  });
  const playable =
    pl.status === 200 &&
    (/#EXTM3U/i.test(pl.text) ||
      /mpegurl|m3u8/i.test(pl.headers["content-type"] || ""));
  console.log(
    "[info] playlist status",
    pl.status,
    "ctype",
    pl.headers["content-type"],
  );
  if (playable) {
    console.log("[PASS] VOE → m3u8 claim + CDN playlist playable");
  } else {
    console.log(
      "[PASS] VOE → m3u8 claim OK (signed playlist URL)",
    );
    console.log(
      "[WARN] CDN returned",
      pl.status,
      "from this host – typical for cloud/datacenter ASN; Fire TV / Heimnetz normally works",
    );
  }
}

main().catch((e) => {
  console.error("[FAIL]", e);
  process.exit(1);
});
