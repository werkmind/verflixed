/**
 * Background VOE resolve/capture (no visible VOE iframe).
 *
 * Strategies:
 * 1) AniWorld-style /redirect/{id} → follow to any /e/{id} host
 * 2) Direct /e/ in episode HTML
 * 3) Hidden BrowserWindow: click VOE, observe ALL frames' URLs + documents
 *    for any /e/ proxy (hosts rotate). Persist session partition for cookies.
 */
const {
  extractVoeHls,
  extractVidaraHls,
  extractFirestream,
  extractSourceFromHtml,
  fetchText,
  request,
  isVoeEmbedUrl,
  findVoeEmbedInText,
  isPlayBlobUrl,
  isVoeGeoBlocked,
  findSoftRedirect,
  scoreHosterName,
} = require("./voe-core");

const PARTITION = "persist:verflixed-site";

function getElectron() {
  // Lazy – allows Node self-tests without launching Electron
  return require("electron");
}

function siteSession() {
  const { session } = getElectron();
  return session.fromPartition(PARTITION);
}

const BOOTSTRAP_JS = `
(function(){
  try {
    function scoreBtn(b){
      var p=((b.getAttribute('data-provider-name')||'')+' '+(b.textContent||'')).toLowerCase();
      var l=((b.getAttribute('data-language-label')||'')+' '+(b.getAttribute('data-lang-key')||'')).toLowerCase();
      var score=0;
      if(p.indexOf('voe')>=0) score+=50;
      if(l.indexOf('deutsch')>=0||l.indexOf('german')>=0||l==='de') score+=30;
      if(/\\/r\\?t=/i.test(b.getAttribute('data-play-url')||'')) score+=5;
      return score;
    }
    function clickVoe(){
      var buttons=[].slice.call(document.querySelectorAll('button.link-box,.link-box,[data-play-url],[data-provider-name]'));
      var scored=buttons.map(function(b){return {b:b,score:scoreBtn(b)};})
        .filter(function(x){return x.score>0;})
        .sort(function(a,b){return b.score-a.score;});
      if(scored[0]){ try{ scored[0].b.click(); }catch(e){} return true; }
      return false;
    }
    clickVoe();
    setTimeout(clickVoe,500);
    setTimeout(clickVoe,1400);
    setTimeout(clickVoe,2800);

    // Hide chrome / ads; keep captcha modal usable if window is shown briefly
    ['.navbar','.ads','.ad-banner','#cookie-consent'].forEach(function(s){
      document.querySelectorAll(s).forEach(function(n){ try{ n.style.display='none'; }catch(e){} });
    });
    // Never show the host player iframe visually (we only scrape its frame URL/document)
    var style=document.createElement('style');
    style.textContent='#player-iframe,.player-wrap iframe{opacity:0!important;pointer-events:none!important;position:fixed!important;left:-10000px!important;width:2px!important;height:2px!important;}';
    document.documentElement.appendChild(style);
  } catch(e) {}
})();
`;

const OBSERVER_JS = `
(function(){
  try {
    window.__vfEmbeds = window.__vfEmbeds || [];
    function push(u){
      if(!u||typeof u!=='string') return;
      u=u.trim();
      if(!u) return;
      if(/\\/e\\/[a-zA-Z0-9]+/i.test(u) || /voe\\.sx/i.test(u)) {
        if(window.__vfEmbeds.indexOf(u)<0) window.__vfEmbeds.push(u);
      }
    }
    function scan(){
      try {
        document.querySelectorAll('iframe[src]').forEach(function(f){ push(f.src); });
        var html=document.documentElement?document.documentElement.innerHTML:'';
        var re=/https?:\\/\\/[^\\s"'<>]+\\/e\\/[a-zA-Z0-9]+/gi;
        var m;
        while((m=re.exec(html))) push(m[0]);
      } catch(e) {}
    }
    scan();
    try {
      new MutationObserver(scan).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src']});
    } catch(e) {}
    setInterval(scan,700);
  } catch(e) {}
})();
`;

/** Scrape a single frame document for embed candidates (any host). */
const FRAME_SCRAPE_JS = `
(function(){
  var out=[];
  function add(u){ if(u&&out.indexOf(u)<0) out.push(String(u)); }
  try { add(location.href); } catch(e) {}
  try {
    document.querySelectorAll('a[href],iframe[src],source[src],video[src]').forEach(function(el){
      add(el.href||el.src||el.getAttribute('href')||el.getAttribute('src'));
    });
    var html=document.documentElement?document.documentElement.outerHTML:'';
    var re=/https?:\\/\\/[^\\s"'<>]+\\/e\\/[a-zA-Z0-9]+/gi;
    var m; while((m=re.exec(html))) add(m[0]);
    var loc=html.match(/(?:location\\.href|window\\.location(?:\\.href)?)\\s*=\\s*['"]([^'"]+)['"]/);
    if(loc) add(loc[1]);
  } catch(e) {}
  return out;
})();
`;

function pickBestEmbed(candidates) {
  const list = [...new Set((candidates || []).filter(Boolean).map((u) => u.trim()))];
  const embeds = list.filter((u) => isVoeEmbedUrl(u) || /\/e\/[a-zA-Z0-9]+/i.test(u));
  if (!embeds.length) return null;
  // Prefer non-voe.sx mirrors if present (final proxy), else first /e/
  const mirror = embeds.find((u) => {
    try {
      const h = new URL(u).hostname.toLowerCase();
      return h !== "voe.sx" && !h.endsWith(".voe.sx");
    } catch {
      return false;
    }
  });
  return mirror || embeds[0];
}

function parseHosters(html, pageUrl) {
  const hosters = [];
  const re = /<button[^>]*class="[^"]*link-box[^"]*"[^>]*>/gi;
  let m;
  while ((m = re.exec(html))) {
    const tag = m[0];
    const play = (tag.match(/data-play-url="([^"]*)"/) || [])[1];
    const provider = (tag.match(/data-provider-name="([^"]*)"/) || [])[1] || "";
    const lang = (tag.match(/data-language-label="([^"]*)"/) || [])[1] || "";
    if (!play) continue;
    let url = play;
    try {
      url = new URL(play, pageUrl).toString();
    } catch (_) {}
    hosters.push({
      provider,
      language: lang,
      url,
      score:
        (/voe/i.test(provider) ? 50 : 0) +
        (/deutsch|german|^de$/i.test(lang) ? 30 : 0) +
        (isPlayBlobUrl(url) ? 5 : 0) +
        (/\/e\//i.test(url) ? 40 : 0) +
        (/\/redirect\//i.test(url) ? 20 : 0),
    });
  }
  // anchors /redirect/
  const re2 = /href="(\/redirect\/\d+)"/gi;
  while ((m = re2.exec(html))) {
    try {
      hosters.push({
        provider: "redirect",
        language: "",
        url: new URL(m[1], pageUrl).toString(),
        score: 25,
      });
    } catch (_) {}
  }
  hosters.sort((a, b) => b.score - a.score);
  return hosters;
}

async function followRedirectToEmbed(redirectUrl, referer) {
  const res = await request(redirectUrl, {
    headers: { Referer: referer || redirectUrl },
    maxRedirects: 0,
  });
  const loc = res.headers.location;
  if (loc) {
    const abs = new URL(loc, redirectUrl).toString();
    if (isVoeEmbedUrl(abs) || /\/e\//i.test(abs)) return abs;
  }
  const fromBody = findVoeEmbedInText(res.text || "");
  if (fromBody) return fromBody;
  // followed automatically?
  if (isVoeEmbedUrl(res.finalUrl)) return res.finalUrl;
  const followed = await request(redirectUrl, {
    headers: { Referer: referer || redirectUrl },
    maxRedirects: 5,
  });
  if (isVoeEmbedUrl(followed.finalUrl)) return followed.finalUrl;
  return findVoeEmbedInText(followed.text || "") || null;
}

/**
 * Load a VOE /e/ page and read document for rotating proxy location.href (any host).
 */
async function resolveProxyFromVoeDocument(embedUrl) {
  const res = await request(embedUrl, {
    headers: { Referer: "https://voe.sx/" },
  });
  const html = res.text || "";
  const redir =
    (html.match(
      /(?:location\.href|window\.location(?:\.href)?)\s*=\s*['"](https?:\/\/[^'"]+\/e\/[^'"]+)['"]/i,
    ) || [])[1] || findVoeEmbedInText(html);
  if (redir && redir !== embedUrl) {
    return { embedUrl: redir, via: "document-redirect", htmlHint: true };
  }
  return { embedUrl: res.finalUrl || embedUrl, via: "direct", htmlHint: /application\/json/i.test(html) };
}

async function collectFrameEmbeds(webContents) {
  const found = [];
  const consider = (u) => {
    if (!u) return;
    if (isVoeEmbedUrl(u) || /\/e\/[a-zA-Z0-9]+/i.test(u)) found.push(u);
    const nested = findVoeEmbedInText(u);
    if (nested) found.push(nested);
  };

  try {
    consider(webContents.getURL());
  } catch (_) {}

  // Parent observer list
  try {
    const embeds = await webContents.executeJavaScript(
      `Array.isArray(window.__vfEmbeds)?window.__vfEmbeds:[]`,
      true,
    );
    (embeds || []).forEach(consider);
  } catch (_) {}

  // Walk Electron frame tree – can read cross-origin frame URLs + documents
  try {
    const root = webContents.mainFrame;
    const frames = root?.framesInSubtree || [];
    for (const frame of frames) {
      try {
        consider(frame.url);
      } catch (_) {}
      try {
        const urls = await frame.executeJavaScript(FRAME_SCRAPE_JS, true);
        (urls || []).forEach(consider);
      } catch (_) {
        /* cross-origin execute may fail on some builds – URL still available */
      }
    }
  } catch (_) {}

  return found;
}

/**
 * Hidden background capture: load episode, hide iframe visually, scrape frame documents.
 * Briefly shows window only if Turnstile modal is required and still unresolved.
 */
function captureEmbedFromEpisodeBackground(episodeUrl, { timeoutMs = 120000, allowShowForCaptcha = true } = {}) {
  return new Promise((resolve, reject) => {
    let settled = false;
    /** @type {BrowserWindow | null} */
    let win = null;
    let captchaShown = false;

    const finish = (embed, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearInterval(poll);
      try {
        if (win && !win.isDestroyed()) win.destroy();
      } catch (_) {}
      win = null;
      if (err) reject(err);
      else resolve({ ok: true, voeUrl: embed, method: "background-frame-dom" });
    };

    const timer = setTimeout(() => {
      finish(
        null,
        new Error(
          "Timeout: kein /e/-Embed im iframe-Document (Captcha ggf. nötig oder Gate blockiert)",
        ),
      );
    }, timeoutMs);

    const ses = siteSession();
    const { BrowserWindow } = getElectron();
    win = new BrowserWindow({
      width: 920,
      height: 700,
      show: false,
      paintWhenInitiallyHidden: true,
      backgroundColor: "#0b0d12",
      title: "Verflixed VOE capture",
      webPreferences: {
        session: ses,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false,
        backgroundThrottling: false,
      },
    });

    const wc = win.webContents;
    const considerUrl = (url) => {
      const best = pickBestEmbed([url, findVoeEmbedInText(url || "")]);
      if (best) finish(best);
    };

    wc.on("did-frame-navigate", (_e, url) => considerUrl(url));
    wc.on("did-navigate", (_e, url) => considerUrl(url));
    wc.on("will-redirect", (_e, url) => considerUrl(url));
    wc.on("did-redirect-navigation", (_e, url) => considerUrl(url));

    const onBefore = (details, cb) => {
      considerUrl(details.url);
      cb({});
    };
    ses.webRequest.onBeforeRequest({ urls: ["*://*/*"] }, onBefore);

    win.on("closed", () => {
      try {
        ses.webRequest.onBeforeRequest(null);
      } catch (_) {}
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        clearInterval(poll);
        reject(new Error("Capture-Window geschlossen"));
      }
    });

    const inject = async () => {
      try {
        await wc.executeJavaScript(BOOTSTRAP_JS + OBSERVER_JS, true);
      } catch (_) {}
    };

    wc.on("did-finish-load", () => {
      inject();
      setTimeout(inject, 1200);
      setTimeout(inject, 2600);
    });

    const poll = setInterval(async () => {
      if (settled || !win || win.isDestroyed()) return;
      try {
        const found = await collectFrameEmbeds(wc);
        const best = pickBestEmbed(found);
        if (best) {
          finish(best);
          return;
        }

        // If turnstile modal visible and still no embed, briefly show window for user solve
        if (allowShowForCaptcha && !captchaShown) {
          const need =
            await wc.executeJavaScript(
              `(function(){
                var m=document.getElementById('playerPrepareModal');
                var ts=document.querySelector('.cf-turnstile, #player-prepare-turnstile iframe, [name="cf-turnstile-response"]');
                var shown=m && (m.classList.contains('show') || m.style.display==='block');
                return !!(shown || ts);
              })()`,
              true,
            );
          if (need) {
            captchaShown = true;
            win.setTitle("Verflixed – kurz Captcha lösen (iframe bleibt versteckt)");
            win.show();
            win.focus();
          }
        }
      } catch (_) {}
    }, 800);

    wc.setUserAgent(
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    );
    win.loadURL(episodeUrl, { httpReferrer: episodeUrl });
  });
}

/**
 * Full resolve pipeline for an episode watch URL → VOE embed (any proxy host).
 */
async function resolveEpisodeVoeEmbed(episodeUrl, opts = {}) {
  const page = await fetchText(episodeUrl);
  const finalPage = page.finalUrl || episodeUrl;
  const html = page.text || "";
  const hosters = parseHosters(html, finalPage);

  // A) already an /e/ link
  let embed = pickBestEmbed([
    ...hosters.map((h) => h.url),
    findVoeEmbedInText(html),
  ]);
  if (embed) {
    const proxied = await resolveProxyFromVoeDocument(embed);
    return {
      ok: true,
      voeUrl: proxied.embedUrl,
      episodeUrl: finalPage,
      method: "direct-html",
      hosters,
    };
  }

  // B) /redirect/ (AniWorld etc.) – fully automatic
  let redirectUrl =
    hosters.find((h) => /\/redirect\//i.test(h.url) && /voe/i.test(h.provider))?.url ||
    hosters.find((h) => /\/redirect\//i.test(h.url))?.url ||
    null;
  if (!redirectUrl) {
    const m = html.match(
      /icon VOE[\s\S]{0,400}?href="(\/redirect\/\d+)"|href="(\/redirect\/\d+)"[\s\S]{0,400}?icon VOE/i,
    );
    const rid = m && (m[1] || m[2]);
    if (rid) redirectUrl = new URL(rid, finalPage).toString();
  }
  if (redirectUrl) {
    embed = await followRedirectToEmbed(redirectUrl, finalPage);
    if (embed) {
      const proxied = await resolveProxyFromVoeDocument(embed);
      return {
        ok: true,
        voeUrl: proxied.embedUrl,
        episodeUrl: finalPage,
        method: "redirect-follow",
        hosters,
      };
    }
  }

  // C) SerienStream /r?t= → background iframe-document capture (any proxy)
  const hasBlob = hosters.some((h) => /voe/i.test(h.provider) && isPlayBlobUrl(h.url));
  if (hasBlob || hosters.some((h) => isPlayBlobUrl(h.url))) {
    if (opts.skipBackground) {
      return {
        ok: false,
        error: "play-blob requires background frame capture",
        episodeUrl: finalPage,
        hosters,
        method: "blob-pending",
      };
    }
    const captured = await captureEmbedFromEpisodeBackground(finalPage, opts);
    const proxied = await resolveProxyFromVoeDocument(captured.voeUrl);
    return {
      ok: true,
      voeUrl: proxied.embedUrl,
      episodeUrl: finalPage,
      method: captured.method,
      hosters,
    };
  }

  return {
    ok: false,
    error: "Kein VOE-/Redirect-/Play-Blob gefunden",
    episodeUrl: finalPage,
    hosters,
    method: "none",
  };
}

async function resolveEpisodeToHls(episodeUrl, opts = {}) {
  const resolved = await resolveEpisodeVoeEmbed(episodeUrl, opts);
  if (!resolved.ok || !resolved.voeUrl) return resolved;
  const hls = await extractVoeHls(resolved.voeUrl, resolved.episodeUrl);
  return {
    ...resolved,
    hls: hls.ok ? hls : null,
    hlsUrl: hls.ok ? hls.hls : null,
    hops: hls.hops,
  };
}

/**
 * Filmpalast-style VOE share: https://voe.sx/{id} → JS redirect → mirror/{id}
 * 1) Plain HTTP follow + decode (works when not geo-blocked)
 * 2) Hidden BrowserWindow (no UI) if HTTP fails / geo-block needs real browser
 */
function resolveVoeShareInBackground(shareUrl, { timeoutMs = 90000, referer } = {}) {
  return new Promise((resolve, reject) => {
    let settled = false;
    /** @type {import('electron').BrowserWindow | null} */
    let win = null;
    const hops = [];

    const finish = (result, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearInterval(poll);
      try {
        if (win && !win.isDestroyed()) win.destroy();
      } catch (_) {}
      if (err) reject(err);
      else resolve(result);
    };

    const timer = setTimeout(
      () => finish(null, new Error("Timeout: VOE share background resolve")),
      timeoutMs,
    );

    const ses = siteSession();
    const { BrowserWindow } = getElectron();
    win = new BrowserWindow({
      width: 1024,
      height: 720,
      show: false,
      paintWhenInitiallyHidden: true,
      backgroundColor: "#0b0d12",
      title: "Verflixed VOE share",
      webPreferences: {
        session: ses,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false,
        backgroundThrottling: false,
      },
    });

    const wc = win.webContents;
    const note = (url) => {
      if (url && !hops.includes(url)) hops.push(url);
    };

    // Intercept m3u8 / playlist requests as soon as the player loads
    const onBefore = (details, cb) => {
      note(details.url);
      if (/\.m3u8|mpegurl|\/hls\//i.test(details.url)) {
        finish({
          ok: true,
          hls: details.url,
          pageUrl: wc.getURL(),
          hops,
          method: "bg-window-network",
        });
      }
      cb({});
    };
    ses.webRequest.onBeforeRequest({ urls: ["*://*/*"] }, onBefore);

    wc.on("did-navigate", (_e, url) => note(url));
    wc.on("will-redirect", (_e, url) => note(url));
    wc.on("did-redirect-navigation", (_e, url) => note(url));
    wc.on("did-frame-navigate", (_e, url) => note(url));

    const tryParseHtml = async () => {
      if (settled || !win || win.isDestroyed()) return;
      try {
        const html = await wc.executeJavaScript(
          "document.documentElement.outerHTML",
          true,
        );
        const title = await wc.executeJavaScript("document.title", true);
        const url = wc.getURL();
        note(url);

        if (isVoeGeoBlocked(html)) {
          // Keep polling briefly – some regions unlock after JS; else fail with geo flag
          return;
        }

        const src = extractSourceFromHtml(html || "");
        if (src) {
          finish({
            ok: true,
            hls: src,
            pageUrl: url,
            title,
            hops,
            method: "bg-window-html",
          });
          return;
        }

        const soft = findSoftRedirect(html);
        if (soft) {
          const next = new URL(soft, url).toString();
          note(next);
          if (wc.getURL() !== next) {
            await wc.loadURL(next, { httpReferrer: url });
          }
        }
      } catch (_) {}
    };

    const poll = setInterval(() => {
      tryParseHtml();
    }, 800);

    wc.on("did-finish-load", () => {
      tryParseHtml();
      setTimeout(tryParseHtml, 1200);
      setTimeout(tryParseHtml, 3000);
    });

    win.on("closed", () => {
      try {
        ses.webRequest.onBeforeRequest(null);
      } catch (_) {}
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        clearInterval(poll);
        reject(new Error("VOE-Share-Window geschlossen"));
      }
    });

    win.loadURL(shareUrl, {
      httpReferrer: referer || "https://filmpalast.to/",
    });
  });
}

/**
 * Resolve a VOE share/embed URL to HLS, using HTTP first then a hidden window.
 */
async function resolveVoeShareToHls(shareUrl, opts = {}) {
  const referer = opts.referer || "https://filmpalast.to/";
  let httpMeta = { hops: [], geoBlocked: false, error: null };

  if (!opts.forceBackground) {
    const http = await extractVoeHls(shareUrl, referer);
    httpMeta = http;
    if (http.ok && http.hls) {
      return {
        ok: true,
        hlsUrl: http.hls,
        hls: http,
        pageUrl: http.pageUrl,
        hops: http.hops,
        method: "http-follow",
        geoBlocked: false,
      };
    }

    if (opts.skipBackground) {
      return {
        ok: false,
        error: http.error || "VOE HTTP claim failed",
        hops: http.hops,
        method: "http-failed",
        geoBlocked: !!http.geoBlocked,
      };
    }

    // Geo-block won't be fixed by a background window on the same IP/country
    if (http.geoBlocked && opts.skipBackgroundOnGeo !== false) {
      return {
        ok: false,
        error: http.error || "VOE geo-blocked",
        hops: http.hops,
        method: "geo-blocked",
        geoBlocked: true,
      };
    }
  }

  try {
    const bg = await resolveVoeShareInBackground(shareUrl, {
      timeoutMs: opts.timeoutMs || 60000,
      referer,
    });
    return {
      ok: true,
      hlsUrl: bg.hls,
      pageUrl: bg.pageUrl,
      hops: bg.hops,
      method: bg.method,
      geoBlocked: false,
    };
  } catch (e) {
    return {
      ok: false,
      error: e.message || String(e),
      hops: httpMeta.hops || [],
      method: "bg-failed",
      geoBlocked: !!httpMeta.geoBlocked,
    };
  }
}

/**
 * Ordered hoster claim: VOE first, then Vidara-like /api/stream, then others later.
 */
async function resolveHostersToHls(hosters, referer) {
  const ordered = [...(hosters || [])].sort(
    (a, b) =>
      scoreHosterName(b.provider || b.name, b.url) -
      scoreHosterName(a.provider || a.name, a.url),
  );
  const attempts = [];
  for (const h of ordered) {
    const name = h.provider || h.name || "";
    const url = h.url;
    if (!url) continue;

    if (/voe/i.test(name) || /voe\.sx/i.test(url) || isVoeEmbedUrl(url)) {
      const r = await resolveVoeShareToHls(url, {
        referer,
        skipBackground: false,
        timeoutMs: 45000,
      });
      attempts.push({ host: name, url, ...r });
      if (r.ok && r.hlsUrl) {
        return { ok: true, hlsUrl: r.hlsUrl, provider: name, method: r.method, attempts };
      }
      continue;
    }

    if (/vidara|vidnest/i.test(name) || /vidaraa?\.cc/i.test(url)) {
      const r = await extractVidaraHls(url, referer);
      attempts.push({ host: name, url, ok: r.ok, error: r.error, method: "vidara-api" });
      if (r.ok && r.hls) {
        return {
          ok: true,
          hlsUrl: r.hls,
          provider: name,
          method: "vidara-api",
          attempts,
        };
      }
    }

    if (/firestream/i.test(name) || /firestream/i.test(url)) {
      const r = await extractFirestream(url, referer);
      attempts.push({ host: name, url, ok: r.ok, error: r.error, method: "firestream" });
      if (r.ok && r.hls) {
        return {
          ok: true,
          hlsUrl: r.hls,
          provider: name,
          method: "firestream",
          attempts,
        };
      }
    }
  }
  return {
    ok: false,
    error: "Kein Hoster lieferte HLS",
    attempts,
  };
}

module.exports = {
  PARTITION,
  siteSession,
  resolveEpisodeVoeEmbed,
  resolveEpisodeToHls,
  resolveVoeShareToHls,
  resolveVoeShareInBackground,
  resolveHostersToHls,
  resolveProxyFromVoeDocument,
  followRedirectToEmbed,
  parseHosters,
  pickBestEmbed,
  captureEmbedFromEpisodeBackground,
};
