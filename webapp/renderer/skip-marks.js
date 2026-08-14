/** Adaptive skip / next-episode windows (parity with Android SkipMarksStore). */
(function (global) {
  const KEY = "vf_skip_marks_v1";

  function load() {
    try {
      return JSON.parse(localStorage.getItem(KEY) || "{}") || {};
    } catch (_) {
      return {};
    }
  }

  function save(db) {
    localStorage.setItem(KEY, JSON.stringify(db));
  }

  function samples(db, key) {
    const arr = db[key];
    return Array.isArray(arr) ? arr.map(Number).filter((n) => Number.isFinite(n) && n > 0) : [];
  }

  function median(arr) {
    if (!arr.length) return null;
    const s = [...arr].sort((a, b) => a - b);
    return s[Math.floor(s.length / 2)];
  }

  function append(db, key, value, clampMin, clampMax) {
    const v = Math.max(clampMin, Math.min(clampMax, Math.round(value)));
    const next = samples(db, key).concat(v).slice(-8);
    db[key] = next;
    save(db);
    return median(next);
  }

  function creditsKey(seriesId) {
    return `credits:${seriesId}`;
  }
  function introKey(seriesId) {
    return `intro:${seriesId}`;
  }

  function creditsLeadMs(seriesId) {
    return median(samples(load(), creditsKey(seriesId)));
  }

  function introEndMs(seriesId) {
    return median(samples(load(), introKey(seriesId)));
  }

  function recordCreditsLead(seriesId, leadMs) {
    return append(load(), creditsKey(seriesId), leadMs, 15_000, 8 * 60_000);
  }

  function recordCreditsLeadAtLeast(seriesId, remainingMs) {
    const bumped = Math.max(30_000, Math.min(10 * 60_000, remainingMs + 20_000));
    const cur = creditsLeadMs(seriesId) || 0;
    if (bumped > cur) recordCreditsLead(seriesId, bumped);
  }

  function recordIntroEnd(seriesId, endMs) {
    return append(load(), introKey(seriesId), endMs, 8_000, 4 * 60_000);
  }

  function heuristicLeadMs(durationMs) {
    if (!(durationMs > 0)) return 60_000;
    const mins = durationMs / 60_000;
    if (mins < 15) return 45_000;
    if (mins < 30) return Math.max(50_000, Math.min(90_000, Math.round(durationMs * 0.07)));
    if (mins < 50) return Math.max(70_000, Math.min(150_000, Math.round(durationMs * 0.08)));
    return Math.max(90_000, Math.min(210_000, Math.round(durationMs * 0.09)));
  }

  function heuristicIntroEndMs(durationMs, episodeNumber) {
    if (!(durationMs >= 12 * 60_000)) return null;
    const mins = durationMs / 60_000;
    let base = 110_000;
    if (mins < 25) base = 75_000;
    else if (mins < 45) base = 90_000;
    const openerBoost = episodeNumber <= 1 ? 25_000 : 0;
    return Math.min(base + openerBoost, Math.round(durationMs * 0.18));
  }

  function nextPromptLeadMs(seriesId, durationMs) {
    return creditsLeadMs(seriesId) || heuristicLeadMs(durationMs);
  }

  function introSegment(seriesId, durationMs, episodeNumber) {
    const learned = introEndMs(seriesId);
    const end = learned || heuristicIntroEndMs(durationMs, episodeNumber);
    if (!end || end < 8_000) return null;
    const max = Math.max(8_000, Math.floor(durationMs / 3));
    if (end > max) return null;
    return {
      type: "INTRO",
      startMs: 0,
      endMs: end,
      source: learned != null ? "learned" : "heuristic",
      label: "Intro überspringen",
    };
  }

  function creditsSegment(seriesId, durationMs) {
    const lead = nextPromptLeadMs(seriesId, durationMs);
    if (!(durationMs > lead)) return null;
    return {
      type: "CREDITS",
      startMs: Math.max(0, durationMs - lead),
      endMs: durationMs,
      source: creditsLeadMs(seriesId) != null ? "learned" : "heuristic",
      label: "Abspann überspringen",
    };
  }

  global.VfSkipMarks = {
    creditsLeadMs,
    introEndMs,
    recordCreditsLead,
    recordCreditsLeadAtLeast,
    recordIntroEnd,
    heuristicLeadMs,
    nextPromptLeadMs,
    introSegment,
    creditsSegment,
  };
})(window);
