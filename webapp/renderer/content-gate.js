/**
 * Profile content gate (parity with Fire TV 1.11+):
 * blocked categories are never shown — browse, library rows, and search.
 * Default blocked: horror + anime.
 */
window.ContentGate = (() => {
  const GENRES = [
    { id: "action", label: "Action" },
    { id: "comedy", label: "Comedy" },
    { id: "drama", label: "Drama" },
    { id: "krimi", label: "Krimi" },
    { id: "thriller", label: "Thriller" },
    { id: "fantasy", label: "Fantasy" },
    { id: "science-fiction", label: "Sci-Fi" },
    { id: "horror", label: "Horror" },
    { id: "anime", label: "Anime" },
    { id: "animation", label: "Animation" },
    { id: "dokumentation", label: "Doku" },
    { id: "romantik", label: "Romantik" },
    { id: "mystery", label: "Mystery" },
    { id: "k-drama", label: "K-Drama" },
  ];

  const DEFAULT_BLOCKED = ["horror", "anime"];

  const ALIASES = {
    horror: ["horror", "splatter", "slasher"],
    anime: ["anime", "aniworld", "manga"],
    animation: ["animation", "zeichentrick", "cartoon", "animiert"],
    action: ["action"],
    comedy: ["comedy", "komödie", "komodie"],
    drama: ["drama"],
    krimi: ["krimi", "crime"],
    thriller: ["thriller"],
    fantasy: ["fantasy"],
    "science-fiction": ["science-fiction", "sci-fi", "scifi", "science fiction"],
    dokumentation: ["dokumentation", "doku", "documentary"],
    romantik: ["romantik", "romance"],
    mystery: ["mystery"],
    "k-drama": ["k-drama", "kdrama", "k drama"],
  };

  function keysFor(id) {
    const label = GENRES.find((g) => g.id === id)?.label?.toLowerCase();
    const all = [...(ALIASES[id] || []), id];
    if (label) all.push(label);
    return [...new Set(all.map((k) => k.toLowerCase()))];
  }

  function isBlocked(series, blocked) {
    if (!series || !blocked || !blocked.length) return false;
    const genres = (series.genres || []).map((g) => String(g).toLowerCase().trim());
    const blob = [series.title, series.id, series.detailPath]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
    const overview = String(series.overview || "").toLowerCase().slice(0, 240);
    for (const id of blocked) {
      const keys = keysFor(id);
      if (genres.some((g) => keys.some((k) => g === k || g.includes(k)))) return true;
      if (keys.some((k) => k.length >= 4 && (blob.includes(k) || overview.includes(k)))) {
        return true;
      }
    }
    return false;
  }

  function filterList(list, blocked) {
    const b = blocked || window.VfProfiles?.blockedGenres?.() || DEFAULT_BLOCKED;
    if (!b.length) return list || [];
    return (list || []).filter((s) => !isBlocked(s.series || s, b));
  }

  return { GENRES, DEFAULT_BLOCKED, isBlocked, filterList };
})();
