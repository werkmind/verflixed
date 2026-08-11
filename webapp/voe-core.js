/**
 * Shared VOE → HLS claim logic (mirrors Android VoeExtractor).
 * Used by Electron main process (no CORS) and by Node test scripts.
 */
const https = require("https");
const http = require("http");
const { URL } = require("url");

const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

const JUNK = ["@$", "^^", "~@", "%?", "*~", "!!", "#&"];

function rot13(input) {
  return input
    .split("")
    .map((c) => {
      const code = c.charCodeAt(0);
      if (code >= 65 && code <= 90) return String.fromCharCode(((code - 65 + 13) % 26) + 65);
      if (code >= 97 && code <= 122) return String.fromCharCode(((code - 97 + 13) % 26) + 97);
      return c;
    })
    .join("");
}

function decodeVoeString(encoded) {
  let s = rot13(encoded);
  for (const junk of JUNK) s = s.split(junk).join("_");
  s = s.replace(/_/g, "");
  s = Buffer.from(s, "base64").toString("utf8");
  s = s
    .split("")
    .map((c) => String.fromCharCode(c.charCodeAt(0) - 3))
    .join("");
  s = Buffer.from(s.split("").reverse().join(""), "base64").toString("utf8");
  return JSON.parse(s);
}

function pickSource(obj) {
  if (!obj || typeof obj !== "object") return null;
  const source = obj.source || obj.hls || null;
  if (source && /\.m3u8|mpegurl|\/hls\//i.test(source)) return source;
  const direct = obj.direct_access_url || null;
  if (direct && /\.m3u8|\.mp4/i.test(direct)) return direct;
  return source;
}

function extractSourceFromHtml(html) {
  const jsonBlocks = [
    ...html.matchAll(
      /<script[^>]*type=["']application\/json["'][^>]*>([\s\S]*?)<\/script>/gi,
    ),
  ];
  for (const m of jsonBlocks) {
    let raw = (m[1] || "").trim();
    const payloads = [];
    if (raw.startsWith("[") && raw.includes('"')) {
      payloads.push(raw.replace(/^\[\s*"/, "").replace(/"\s*\]$/, ""));
    } else if (raw.startsWith('"') && raw.endsWith('"')) {
      payloads.push(raw.slice(1, -1));
    } else {
      payloads.push(raw);
    }
    for (const p of payloads) {
      for (const cand of [p, tryUnescape(p)]) {
        try {
          const decoded = decodeVoeString(cand);
          const src = pickSource(decoded);
          if (src) return src;
        } catch (_) {
          /* try next */
        }
      }
    }
  }

  const a168 = html.match(/var\s+a168c\s*=\s*'([^']+)'/);
  if (a168) {
    try {
      const src = pickSource(decodeVoeString(a168[1]));
      if (src) return src;
    } catch (_) {}
  }

  const hlsPlain = html.match(/['"]hls['"]\s*:\s*['"]([^'"]+)['"]/);
  if (hlsPlain) return hlsPlain[1].replace(/\\\//g, "/");

  const raw = html.match(/https?:\/\/[^\s'"<>]+?\.m3u8[^\s'"<>]*/i);
  return raw ? raw[0].replace(/\\\//g, "/") : null;
}

function tryUnescape(s) {
  try {
    return JSON.parse(`"${s.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`);
  } catch (_) {
    try {
      return s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) =>
        String.fromCharCode(parseInt(h, 16)),
      );
    } catch {
      return s;
    }
  }
}

function isVoeEmbedUrl(url) {
  if (!url || typeof url !== "string") return false;
  try {
    const u = new URL(url.trim());
    // VOE-style embeds: /e/{id} on voe.sx or rotating mirrors
    if (/\/e\/[a-zA-Z0-9]+/i.test(u.pathname)) return true;
    const host = (u.hostname || "").toLowerCase();
    if (host === "voe.sx" || host.endsWith(".voe.sx") || host.includes("voe")) return true;
    // Filmpalast-style share links land on bare /{id} mirrors (no /e/)
    if (/^\/[A-Za-z0-9_-]{6,}\/?$/.test(u.pathname) && !host.includes("filmpalast")) {
      return true;
    }
  } catch {
    return /\/e\/[a-zA-Z0-9]+/i.test(url);
  }
  return false;
}

/** Soft redirect target from voe.sx "Redirecting…" pages (often bare /{id} on a mirror). */
function findSoftRedirect(html) {
  if (!html) return null;
  const loc =
    (html.match(
      /(?:window\.location(?:\.href)?|location\.href)\s*=\s*['"]([^'"]+)['"]/i,
    ) || [])[1] ||
    (html.match(/location\.replace\(\s*['"]([^'"]+)['"]\s*\)/i) || [])[1] ||
    (html.match(/content=['"]\d+;\s*url=([^'"]+)['"]/i) || [])[1] ||
    null;
  if (!loc) return null;
  const u = loc.trim();
  if (/^https?:\/\//i.test(u)) return u;
  return null;
}

function isVoeGeoBlocked(html) {
  if (!html) return false;
  return (
    /Dateizugriff verweigert/i.test(html) ||
    /Zugang zu Ihrem Land eingeschränkt/i.test(html) ||
    /access to your country/i.test(html)
  );
}

function findVoeEmbedInText(text) {
  if (!text) return null;
  const patterns = [
    /https?:\/\/[^\s"'<>]+\/e\/[a-zA-Z0-9]+[^\s"'<>]*/gi,
    /https?:\/\/[^\s"'<>]*(?:voe\.sx|donaldlineelse|charlestoughrace|nicolehappyoutside|tubelessceliolymph|simpulumlamerop|urochsunloath|nathanfromsubject|metagnathtuggers|reedunpack)[^\s"'<>]*/gi,
  ];
  for (const re of patterns) {
    const matches = text.match(re) || [];
    for (const raw of matches) {
      const cleaned = raw.replace(/[)\],.;]+$/g, "");
      if (isVoeEmbedUrl(cleaned)) return cleaned;
    }
  }
  return null;
}

function findRedirect(html) {
  const soft = findSoftRedirect(html);
  if (soft) {
    // Accept /e/ embeds AND bare mirror share URLs (Filmpalast → voe.sx/{id} → mirror/{id})
    if (isVoeEmbedUrl(soft) || /\/e\//i.test(soft) || /^https?:\/\//i.test(soft)) {
      return soft;
    }
  }
  const loc = html.match(
    /(?:location\.href|window\.location(?:\.href)?)\s*=\s*['"]([^'"]+)['"]/,
  );
  if (loc) {
    const u = loc[1].trim();
    if (isVoeEmbedUrl(u) || /\/e\//i.test(u) || /^https?:\/\//i.test(u)) return u;
  }
  const embed = html.match(/['"](\s*https?:\/\/[^'"<>\s]+\/e\/[^'"<>\s]+)['"]/);
  if (embed) return embed[1].trim();
  return findVoeEmbedInText(html);
}

function isPlayBlobUrl(url) {
  if (!url) return false;
  const u = String(url);
  if (/\/r\?t=/i.test(u) || /\/r\?t%/i.test(u)) return true;
  try {
    const parsed = new URL(u, "https://example.com");
    return parsed.pathname === "/r" && parsed.searchParams.has("t");
  } catch {
    return false;
  }
}

function request(url, { method = "GET", headers = {}, body = null, maxRedirects = 8 } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === "http:" ? http : https;
    const req = lib.request(
      {
        protocol: u.protocol,
        hostname: u.hostname,
        port: u.port || (u.protocol === "http:" ? 80 : 443),
        path: u.pathname + u.search,
        method,
        headers: {
          "User-Agent": USER_AGENT,
          Accept: "text/html,application/xhtml+xml,application/json,*/*;q=0.8",
          "Accept-Language": "de-DE,de;q=0.9,en;q=0.8",
          ...headers,
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const buffer = Buffer.concat(chunks);
          const status = res.statusCode || 0;
          const location = res.headers.location;
          if (status >= 300 && status < 400 && location && maxRedirects > 0) {
            const next = new URL(location, url).toString();
            request(next, { method: "GET", headers, maxRedirects: maxRedirects - 1 })
              .then(resolve)
              .catch(reject);
            return;
          }
          resolve({
            status,
            headers: res.headers,
            buffer,
            text: buffer.toString("utf8"),
            finalUrl: url,
          });
        });
      },
    );
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

async function fetchText(url, headers = {}) {
  const res = await request(url, { headers });
  return { status: res.status, finalUrl: res.finalUrl, text: res.text };
}

async function fetchTextPost(url, body, headers = {}) {
  const payload = body == null ? null : Buffer.isBuffer(body) ? body : Buffer.from(String(body));
  const res = await request(url, {
    method: "POST",
    headers: {
      "Content-Length": payload ? String(payload.length) : "0",
      ...headers,
    },
    body: payload,
  });
  return { status: res.status, finalUrl: res.finalUrl, text: res.text };
}

async function fetchBuffer(url, headers = {}) {
  const res = await request(url, { headers });
  return {
    status: res.status,
    finalUrl: res.finalUrl,
    contentType: res.headers["content-type"] || "",
    buffer: res.buffer,
  };
}

async function extractVoeHls(embedUrl, referer = null) {
  if (!embedUrl) return { ok: false, error: "empty url" };
  let url = embedUrl.trim();
  const visited = new Set();
  let lastGeoBlocked = false;
  for (let i = 0; i < 6; i++) {
    if (visited.has(url)) break;
    visited.add(url);
    const res = await request(url, {
      headers: {
        Referer: referer || "https://voe.sx/",
      },
    });
    const html = res.text || "";
    const pageUrl = res.finalUrl || url;
    if (isVoeGeoBlocked(html)) {
      lastGeoBlocked = true;
      // still try soft redirect /e/ normalize below
    }
    const source = extractSourceFromHtml(html);
    if (source) {
      const mp4Fallbacks = [];
      try {
        // Re-parse payload for mp4 fallbacks when present
        const blocks = [
          ...html.matchAll(
            /<script[^>]*type=["']application\/json["'][^>]*>([\s\S]*?)<\/script>/gi,
          ),
        ];
        for (const m of blocks) {
          let raw = (m[1] || "").trim();
          if (raw.startsWith("[")) {
            raw = raw.replace(/^\[\s*"/, "").replace(/"\s*\]$/, "");
          }
          try {
            const obj = decodeVoeString(raw);
            if (Array.isArray(obj.fallback)) {
              for (const f of obj.fallback) {
                if (f?.file) mp4Fallbacks.push(f.file);
              }
            }
            if (obj.direct_access_url) mp4Fallbacks.push(obj.direct_access_url);
          } catch (_) {}
        }
      } catch (_) {}
      return {
        ok: true,
        hls: source,
        pageUrl,
        referer: pageUrl,
        mp4Fallbacks,
        hops: [...visited],
      };
    }
    const next = findRedirect(html);
    if (next && !visited.has(next)) {
      referer = url;
      url = next.startsWith("http") ? next : new URL(next, pageUrl).toString();
      continue;
    }
    // bare /{id} → try /e/{id} on same host
    try {
      const u = new URL(pageUrl);
      if (!/\/e\//i.test(u.pathname) && /^\/[A-Za-z0-9_-]+\/?$/.test(u.pathname)) {
        const id = u.pathname.replace(/\//g, "");
        const eUrl = `${u.origin}/e/${id}`;
        if (!visited.has(eUrl)) {
          referer = url;
          url = eUrl;
          continue;
        }
      }
    } catch (_) {}
    break;
  }
  return {
    ok: false,
    error: lastGeoBlocked
      ? "VOE geo-blocked (Dateizugriff verweigert)"
      : "No VOE HLS source found",
    geoBlocked: lastGeoBlocked,
    hops: [...visited],
  };
}

/**
 * Vidara / Vidnest / similar: POST /api/stream { filecode, device } → streaming_url
 */
async function extractVidaraHls(embedUrl, referer = null) {
  if (!embedUrl) return { ok: false, error: "empty url" };
  let filecode = "";
  try {
    const u = new URL(embedUrl);
    filecode = u.pathname.split("/").filter(Boolean).pop() || "";
  } catch {
    filecode = String(embedUrl).split("/").pop() || "";
  }
  if (!filecode) return { ok: false, error: "no filecode" };

  let origin = "https://vidaraa.cc";
  try {
    origin = new URL(embedUrl).origin;
  } catch (_) {}

  const body = JSON.stringify({ filecode, device: "desktop" });
  const res = await request(`${origin}/api/stream`, {
    method: "POST",
    body,
    headers: {
      "Content-Type": "application/json",
      "Content-Length": String(Buffer.byteLength(body)),
      Referer: embedUrl,
      Origin: origin,
      Accept: "application/json",
    },
  });
  try {
    const data = JSON.parse(res.text || "{}");
    const hls = data.streaming_url || data.source || null;
    if (hls && /\.m3u8|mpegurl|\/hls\//i.test(hls)) {
      return {
        ok: true,
        hls,
        pageUrl: embedUrl,
        referer: referer || embedUrl,
        provider: "vidara",
        filecode,
      };
    }
    return { ok: false, error: data.error || "no streaming_url", raw: data };
  } catch (e) {
    return { ok: false, error: e.message || "vidara parse failed" };
  }
}

function scoreHosterName(name, url = "") {
  const n = `${name || ""} ${url || ""}`.toLowerCase();
  let s = 0;
  if (/\bvoe\b/.test(n)) s += 100;
  if (/vidara|vidnest/.test(n)) s += 70;
  if (/vidsonic/.test(n)) s += 40;
  if (/firestream/.test(n)) s += 30;
  if (/playmate/.test(n)) s += 20;
  if (/\bhd\b/.test(n)) s += 5;
  return s;
}

module.exports = {
  extractVoeHls,
  extractVidaraHls,
  extractSourceFromHtml,
  decodeVoeString,
  fetchText,
  fetchTextPost,
  fetchBuffer,
  request,
  isVoeEmbedUrl,
  findVoeEmbedInText,
  findRedirect,
  findSoftRedirect,
  isVoeGeoBlocked,
  isPlayBlobUrl,
  scoreHosterName,
};
