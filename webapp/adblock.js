/**
 * Host-based blocker compiled from EasyList + EasyPrivacy + uBlock Origin.
 */
const fs = require("fs");
const path = require("path");

const ALLOW = [
  "cloudflare.com",
  "challenges.cloudflare.com",
  "cloudflareinsights.com",
  "recaptcha.net",
  "google.com",
  "gstatic.com",
  "hcaptcha.com",
  "serienstream.cx",
  "serienstream.to",
  "s.to",
  "aniworld.to",
  "voe.sx",
  "filmpalast.to",
  "themoviedb.org",
  "tmdb.org",
  "wikidata.org",
  "wikipedia.org",
  "tvmaze.com",
];

let hosts = null;

function load() {
  if (hosts) return hosts;
  hosts = new Set();
  try {
    const text = fs.readFileSync(path.join(__dirname, "adblock", "hosts.txt"), "utf8");
    for (const line of text.split(/\r?\n/)) {
      const h = line.trim().toLowerCase();
      if (!h || h.startsWith("#")) continue;
      hosts.add(h);
    }
  } catch (_) {}
  return hosts;
}

function hostOf(url) {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch (_) {
    return "";
  }
}

function allowed(host) {
  return ALLOW.some((a) => host === a || host.endsWith(`.${a}`));
}

function shouldBlock(url) {
  const host = hostOf(url);
  if (!host || allowed(host)) return false;
  const set = load();
  let h = host;
  while (h.includes(".")) {
    if (set.has(h)) return true;
    h = h.slice(h.indexOf(".") + 1);
    if (!h.includes(".")) break;
  }
  return false;
}

function attach(ses) {
  if (!ses) return;
  ses.__vfAdblock = true;
  ses.webRequest.onBeforeRequest({ urls: ["*://*/*"] }, (details, cb) => {
    if (shouldBlock(details.url)) cb({ cancel: true });
    else cb({});
  });
}

module.exports = { shouldBlock, attach, load };
