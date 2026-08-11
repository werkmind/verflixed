/** Stream audio language helpers (Deutsch / Englisch). Default: de */
window.StreamLanguage = (() => {
  const DE = "de";
  const EN = "en";

  function normalize(raw) {
    const l = String(raw || "").trim().toLowerCase();
    if (!l) return DE;
    if (
      l === DE ||
      l === "ger" ||
      l === "deu" ||
      l === "german" ||
      l === "deutsch" ||
      (l.startsWith("de") && !l.includes("desub"))
    ) {
      return DE;
    }
    if (
      l === EN ||
      l === "eng" ||
      l === "english" ||
      l === "englisch" ||
      l.startsWith("en")
    ) {
      return EN;
    }
    if (
      l.includes("deutsch") ||
      l.includes("german") ||
      l.includes("ger dub") ||
      /\bgerman\b/.test(l)
    ) {
      return DE;
    }
    if (l.includes("englisch") || l.includes("english") || l.includes("eng dub")) {
      return EN;
    }
    if (l === "1") return DE;
    if (l === "2") return EN;
    return DE;
  }

  function label(code) {
    return normalize(code) === EN ? "Englisch" : "Deutsch";
  }

  function shortLabel(code) {
    return normalize(code) === EN ? "EN" : "DE";
  }

  function toggle(code) {
    return normalize(code) === DE ? EN : DE;
  }

  function matchesPreferred(candidateLang, preferred) {
    const cand = String(candidateLang || "").trim();
    if (!cand) return false;
    return normalize(cand) === normalize(preferred);
  }

  function detectFromText(...texts) {
    const blob = texts.filter(Boolean).join(" ").toLowerCase();
    if (!blob) return null;
    const hasEn =
      blob.includes("*english*") ||
      blob.includes(" english") ||
      blob.includes("english*") ||
      blob.includes("englisch") ||
      blob.includes("-english") ||
      blob.includes("/english") ||
      /\benglish\b/.test(blob) ||
      /\beng\b/.test(blob) ||
      blob.includes(".eng.");
    const hasDe =
      blob.includes("german") ||
      blob.includes("deutsch") ||
      /\bger\b/.test(blob) ||
      blob.includes("german.dubbed") ||
      blob.includes(".ger.") ||
      blob.includes("german.dl");
    if (hasEn && !hasDe) return EN;
    if (hasDe && !hasEn) return DE;
    if (hasEn && hasDe) {
      if (blob.includes("*english*") || blob.includes("-english")) return EN;
      return DE;
    }
    if (hasDe) return DE;
    if (hasEn) return EN;
    return null;
  }

  function cleanTitleForSearch(title) {
    return String(title || "")
      .replace(/\*+\s*ENGLISH\s*\*+/gi, "")
      .replace(/\*+\s*GERMAN\s*\*+/gi, "")
      .replace(/\bENGLISH\b/gi, "")
      .replace(/\bGERMAN\b/gi, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  return {
    DE,
    EN,
    normalize,
    label,
    shortLabel,
    toggle,
    matchesPreferred,
    detectFromText,
    cleanTitleForSearch,
  };
})();
