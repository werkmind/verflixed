/** GitHub Releases update checker (persistent latest download URLs). */
window.VfUpdates = (() => {
  const REPO = "werkmind/verflixed";
  const LATEST_MANIFEST =
    `https://github.com/${REPO}/releases/latest/download/verflixed-update.json`;
  const LATEST_API = `https://api.github.com/repos/${REPO}/releases/latest`;
  const STORAGE_KEY = "vf_update_manifest_url";

  function currentVersion() {
    return (
      document.querySelector('meta[name="vf-version"]')?.content ||
      localStorage.getItem("vf_app_version") ||
      "1.16.0"
    );
  }

  function parseVersionCode(name) {
    const m = String(name || "").match(/(\d+)\.(\d+)\.(\d+)/);
    if (!m) return 0;
    return Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3]);
  }

  async function fetchJson(url) {
    const resp = await fetch(url, {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.json();
  }

  function pickAsset(assets, name) {
    const list = assets || [];
    return (
      list.find((a) => a.name === name)?.browser_download_url ||
      list.find((a) => (a.name || "").endsWith(name))?.browser_download_url ||
      null
    );
  }

  async function check() {
    const preferred =
      localStorage.getItem(STORAGE_KEY) ||
      LATEST_MANIFEST;
    const candidates = [preferred, LATEST_MANIFEST, LATEST_API];
    let lastErr = null;
    for (const url of candidates) {
      try {
        if (url.includes("api.github.com")) {
          const rel = await fetchJson(url);
          const manifestUrl = pickAsset(rel.assets, "verflixed-update.json");
          let manifest = null;
          if (manifestUrl) {
            manifest = await fetchJson(manifestUrl);
          } else {
            manifest = {
              versionCode: parseVersionCode(rel.tag_name || rel.name),
              versionName: String(rel.tag_name || rel.name || "").replace(/^v/, ""),
              apkUrl: pickAsset(rel.assets, "Verflixed-FireTV.apk"),
              webappUrl: pickAsset(rel.assets, "Verflixed-Webapp.zip"),
              changelog: rel.body || "",
            };
          }
          if (manifest?.versionCode) return normalize(manifest, rel);
          continue;
        }
        const manifest = await fetchJson(url);
        if (manifest?.versionCode) return normalize(manifest);
      } catch (e) {
        lastErr = e;
      }
    }
    if (lastErr) throw lastErr;
    return null;
  }

  function normalize(manifest, release) {
    const apkUrl =
      manifest.apkUrl ||
      pickAsset(release?.assets, "Verflixed-FireTV.apk") ||
      `https://github.com/${REPO}/releases/latest/download/Verflixed-FireTV.apk`;
    const webappUrl =
      manifest.webappUrl ||
      pickAsset(release?.assets, "Verflixed-Webapp.zip") ||
      `https://github.com/${REPO}/releases/latest/download/Verflixed-Webapp.zip`;
    return {
      versionCode: Number(manifest.versionCode) || 0,
      versionName: manifest.versionName || String(manifest.versionCode),
      apkUrl,
      webappUrl,
      changelog: manifest.changelog || "",
      htmlUrl: release?.html_url || `https://github.com/${REPO}/releases/latest`,
    };
  }

  function isNewer(manifest) {
    const localCode =
      Number(localStorage.getItem("vf_version_code") || "0") ||
      parseVersionCode(currentVersion());
    // Prefer explicit meta versionCode when present
    const metaCode = Number(
      document.querySelector('meta[name="vf-version-code"]')?.content || "0",
    );
    const code = metaCode || localCode;
    return Number(manifest?.versionCode || 0) > code;
  }

  return {
    REPO,
    LATEST_MANIFEST,
    check,
    isNewer,
    currentVersion,
    setManifestUrl(url) {
      if (url) localStorage.setItem(STORAGE_KEY, url);
      else localStorage.removeItem(STORAGE_KEY);
    },
  };
})();
