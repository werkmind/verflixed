/** Serienkalender — API + HTML fallback, same rows as Fire TV. */
window.VfCalendar = (() => {
  async function getText(url) {
    if (window.verflixed?.getText) return window.verflixed.getText(url);
    const r = await fetch(url);
    return { status: r.status, text: await r.text(), finalUrl: r.url };
  }

  function parseDay(day) {
    const d = new Date(day);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  function startOfDay(d) {
    const x = new Date(d);
    x.setHours(0, 0, 0, 0);
    return x;
  }

  function parseGermanReleaseDay(label) {
    const m = String(label || "").match(/(\d{1,2})\.(\d{1,2})\.(\d{4})/);
    if (!m) return null;
    return `${m[3]}-${m[2].padStart(2, "0")}-${m[1].padStart(2, "0")}`;
  }

  function matchesFavorite(ep, favIds, favTitles) {
    const id = String(ep.seriesId || "").toLowerCase();
    const title = String(ep.title || "").toLowerCase();
    if (favIds.has(id)) return true;
    if (favTitles.has(title)) return true;
    for (const t of favTitles) {
      if (t.length > 3 && (title.includes(t) || t.includes(title))) return true;
    }
    return false;
  }

  async function fetchSchedule(base) {
    const root = String(base || "").replace(/\/$/, "");
    if (!root) return {};
    for (const url of [`${root}/api/calendar`, `${root}/serienkalender/api`, `${root}/api/serienkalender`]) {
      try {
        const res = await getText(url, { Accept: "application/json" });
        if (res.status >= 400 || !res.text) continue;
        const json = JSON.parse(res.text);
        if (json && typeof json === "object") {
          const out = {};
          for (const [day, eps] of Object.entries(json)) {
            out[day] = (eps || []).map((e) => ({
              seriesId: e.seriesId || e.series_id || e.slug || "",
              title: e.title || e.seriesTitle || "",
              seasonNumber: e.seasonNumber || e.season || 1,
              episodeNumber: e.episodeNumber || e.episode || 0,
              date: day,
              time: e.time || "",
              detailPath: e.detailPath || e.url || e.link || "",
              coverUrl: e.coverUrl || e.cover || e.image || null,
              released: e.released !== false,
              episodeTitle: e.episodeTitle || e.episode_title || null,
              releaseLabel: e.releaseLabel || e.release || null,
            }));
          }
          if (Object.keys(out).length) return out;
        }
      } catch (_) {}
    }
    return scrapeHtml(root);
  }

  async function scrapeHtml(base) {
    const out = {};
    for (const url of [`${base}/serienkalender`, `${base}/kalender`]) {
      try {
        const res = await getText(url);
        if (!res.text || res.text.length < 400) continue;
        const doc = new DOMParser().parseFromString(res.text, "text/html");
        doc.querySelectorAll("tr.episode-row, .episode-row, .calendar-episode, a[href*='episode'], a[href*='folge']").forEach((el) => {
          const a = el.matches("a") ? el : el.querySelector("a[href*='episode'], a[href*='folge']");
          const link = a?.href;
          if (!link) return;
          const season = Number((link.match(/(?:staffel|season)[/-]?(\d+)/i) || [])[1] || 1);
          const episode = Number(
            (el.querySelector(".episode-number-cell")?.textContent || "").trim() ||
              (link.match(/(?:episode|folge|ep)[/-]?(\d+)/i) || [])[1] ||
              0,
          );
          if (!episode) return;
          const slug = ((link.match(/\/(?:serie|series)\/(?:stream\/)?([^/]+)/i) || [])[1] || "")
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-|-$/g, "");
          const seriesTitle =
            el.querySelector(".series-title, .serie-title, .calendar-series-title")?.textContent?.trim() ||
            slug.replace(/-/g, " ");
          const releaseLabel = el.querySelector(".badge-release")?.textContent?.replace(/\u00a0/g, " ")?.trim();
          const upcoming = el.classList.contains("upcoming") || /demnächst/i.test(el.textContent || "");
          const dayKey = parseGermanReleaseDay(releaseLabel) || el.getAttribute("data-date") || new Date().toISOString().slice(0, 10);
          const img = el.querySelector("img");
          const cover = img?.src || img?.getAttribute("data-src") || null;
          const entry = {
            seriesId: slug || seriesTitle.toLowerCase().replace(/[^a-z0-9]+/g, "-"),
            title: seriesTitle,
            seasonNumber: season,
            episodeNumber: episode,
            date: dayKey,
            time: (releaseLabel || "").match(/~?\d{1,2}:\d{2}/)?.[0] || "",
            detailPath: link,
            coverUrl: cover && !String(cover).startsWith("data:") ? cover : null,
            released: !upcoming,
            episodeTitle: el.querySelector(".episode-title-ger, .episode-title")?.textContent?.trim() || null,
            releaseLabel,
          };
          (out[dayKey] ||= []).push(entry);
        });
        if (Object.keys(out).length) return out;
      } catch (_) {}
    }
    return out;
  }

  function filterRange(schedule, from, to, favIds, favTitles, forceUnreleased) {
    const out = [];
    for (const [day, eps] of Object.entries(schedule || {})) {
      const date = parseDay(day);
      if (!date || date < from || date > to) continue;
      for (const ep of eps) {
        if (favIds && !matchesFavorite(ep, favIds, favTitles)) continue;
        out.push(forceUnreleased ? { ...ep, released: false } : ep);
      }
    }
    return out.sort((a, b) => String(a.date).localeCompare(b.date) || String(a.time).localeCompare(b.time));
  }

  async function favoritesUpcoming(base, favIds, favTitles, daysAhead = 14) {
    const schedule = await fetchSchedule(base);
    const today = startOfDay(new Date());
    const end = new Date(today.getTime() + daysAhead * 86400000);
    return filterRange(schedule, today, end, favIds, favTitles, true);
  }

  async function favoritesRecent(base, favIds, favTitles, daysBack = 3) {
    const schedule = await fetchSchedule(base);
    const today = startOfDay(new Date());
    const start = new Date(today.getTime() - daysBack * 86400000);
    return filterRange(schedule, start, today, favIds, favTitles, false);
  }

  async function weekAhead(base, daysAhead = 7) {
    const schedule = await fetchSchedule(base);
    const today = startOfDay(new Date());
    const end = new Date(today.getTime() + daysAhead * 86400000);
    return filterRange(schedule, today, end, null, null, false);
  }

  function asSeries(entries) {
    return (entries || []).slice(0, 24).map((e) => {
      const ep = e.episodeNumber > 0
        ? `S${String(e.seasonNumber).padStart(2, "0")}E${String(e.episodeNumber).padStart(2, "0")}`
        : `S${e.seasonNumber}`;
      const badge = !e.released
        ? e.releaseLabel
          ? `DEMNÄCHST · ${e.releaseLabel}`
          : "DEMNÄCHST"
        : e.releaseLabel || [e.date, e.time].filter(Boolean).join(" ");
      return {
        id: e.seriesId,
        title: e.title,
        posterUrl: e.coverUrl,
        backdropUrl: e.coverUrl,
        overview: [badge, e.episodeTitle ? `${ep} – ${e.episodeTitle}` : ep].filter(Boolean).join("\n"),
        detailPath: e.detailPath,
        mediaKind: "series",
        genres: badge ? [badge] : [],
      };
    });
  }

  return { favoritesUpcoming, favoritesRecent, weekAhead, asSeries };
})();
