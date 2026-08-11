/** SerienStream/AniWorld image helpers – mirrors Android SiteImages.kt */
window.SiteImages = (() => {
  function preferJpeg(url) {
    if (!url || !String(url).trim()) return null;
    let u = String(url).trim();
    if (u.startsWith("//")) u = `https:${u}`;
    if (u.startsWith("http://")) u = `https://${u.slice("http://".length)}`;
    if (u.startsWith("data:") || /\.svg(\?|$)/i.test(u)) return null;
    u = upgradeResolution(u);
    if (/\/media\/images\//i.test(u)) {
      u = u.replace(/([?&])format=(avif|webp)/gi, "$1format=jpg");
      if (!/[?&]format=/i.test(u)) {
        u += (u.includes("?") ? "&" : "?") + "format=jpg";
      }
    } else {
      u = u.replace(/\/webp\//gi, "/jpeg/").replace(/\.webp(\?|$)/gi, ".jpg$1");
    }
    return u;
  }

  function upgradeResolution(url) {
    let u = url;
    const kinds = ["channel", "backdrop"];
    for (const kind of kinds) {
      u = u
        .replace(
          new RegExp(`/media/images/${kind}/mobile/`, "gi"),
          `/media/images/${kind}/2x-desktop/`,
        )
        .replace(
          new RegExp(`/media/images/${kind}/tablet/`, "gi"),
          `/media/images/${kind}/2x-desktop/`,
        )
        .replace(
          new RegExp(`/media/images/${kind}/desktop/`, "gi"),
          `/media/images/${kind}/2x-desktop/`,
        )
        .replace(
          new RegExp(`/media/images/${kind}/tile/`, "gi"),
          `/media/images/${kind}/2x-tile/`,
        )
        .replace(
          new RegExp(`/media/images/${kind}/hero-mobile/`, "gi"),
          `/media/images/${kind}/hero-2x-desktop/`,
        )
        .replace(
          new RegExp(`/media/images/${kind}/hero-desktop/`, "gi"),
          `/media/images/${kind}/hero-2x-desktop/`,
        );
    }
    return u;
  }

  function isChannel(url) {
    return /\/media\/images\/channel\//i.test(url || "");
  }
  function isBackdrop(url) {
    return /\/media\/images\/backdrop\//i.test(url || "");
  }

  function resolutionScore(url) {
    if (!url) return -1;
    const u = url.toLowerCase();
    if (u.includes("/2x-desktop/") || u.includes("/2x-tile/") || u.includes("/hero-2x-desktop/"))
      return 40;
    if (u.includes("/orig/")) return 35;
    if (u.includes("/desktop/") || u.includes("/tile/") || u.includes("/hero-desktop/")) return 30;
    if (u.includes("/tablet/")) return 20;
    if (u.includes("/mobile/") || u.includes("/hero-mobile/")) return 10;
    return 5;
  }

  function bestUrl(candidates) {
    return (candidates || [])
      .map((c) => preferJpeg(c))
      .filter(Boolean)
      .sort((a, b) => resolutionScore(b) - resolutionScore(a))[0] || null;
  }

  function pickPoster(...candidates) {
    const list = candidates.flat().filter(Boolean);
    return preferJpeg(list.find(isChannel)) || bestUrl(list) || preferJpeg(list[0]);
  }

  function pickBackdrop(...candidates) {
    const list = candidates.flat().filter(Boolean);
    return preferJpeg(list.find(isBackdrop)) || bestUrl(list) || preferJpeg(list[0]);
  }

  function fromSrcset(srcset) {
    if (!srcset) return null;
    const urls = String(srcset)
      .split(",")
      .map((part) => part.trim().split(/\s+/)[0])
      .filter((u) => u && !u.startsWith("data:"));
    return bestUrl(urls);
  }

  return {
    preferJpeg,
    upgradeResolution,
    pickPoster,
    pickBackdrop,
    fromSrcset,
    isChannel,
    isBackdrop,
    resolutionScore,
    bestUrl,
  };
})();
