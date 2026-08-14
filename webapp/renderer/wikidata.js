/** Keyless IMDb/TMDB resolver via Wikidata (parity with Android WikidataClient). */
window.Wikidata = (() => {
  const cache = new Map();

  async function getJson(url) {
    if (window.verflixed?.getText) {
      const res = await window.verflixed.getText(url, { Accept: "application/json" });
      if (res.status < 400 && res.text) {
        try {
          return JSON.parse(res.text);
        } catch (_) {}
      }
    }
    try {
      const r = await fetch(url, { headers: { Accept: "application/json" } });
      if (!r.ok) return null;
      return r.json();
    } catch {
      return null;
    }
  }

  function clean(title) {
    return String(title || "")
      .replace(/\s*[-|–]\s*stream.*$/i, "")
      .replace(/\s+staffel\s*\d+.*$/i, "")
      .replace(/\s+season\s*\d+.*$/i, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  function firstString(claims, prop) {
    const dv = claims?.[prop]?.[0]?.mainsnak?.datavalue;
    if (!dv) return null;
    const v = dv.value;
    if (typeof v === "string") return v;
    if (v && typeof v === "object") return v.id || v.text || null;
    return dv.value || null;
  }

  function firstYear(claims, prop) {
    const time = claims?.[prop]?.[0]?.mainsnak?.datavalue?.value?.time || "";
    const m = String(time).match(/([12]\d{3})/);
    return m ? Number(m[1]) : null;
  }

  function score(query, label, year, claims) {
    const q = query.toLowerCase();
    const n = String(label || "").toLowerCase();
    let s = n === q ? 100 : n.includes(q) || q.includes(n) ? 70 : 0;
    if (!s) s = n.split(" ").filter((w) => w.length > 2 && q.includes(w)).length * 12;
    if (year) {
      const date = firstYear(claims, "P577") || firstYear(claims, "P580");
      if (date && Math.abs(date - year) <= 1) s += 25;
    }
    return s;
  }

  async function resolve(title, year, movie) {
    const q = clean(title);
    if (q.length < 2) return null;
    const key = `${q.toLowerCase()}|${year || 0}|${!!movie}`;
    if (cache.has(key)) return cache.get(key);
    const search = await getJson(
      "https://www.wikidata.org/w/api.php?action=wbsearchentities&language=de&uselang=de&type=item&limit=6&format=json&search=" +
        encodeURIComponent(q),
    );
    const ids = (search?.search || []).map((x) => x.id).filter((id) => /^Q/.test(id));
    if (!ids.length) return null;
    const ents = await getJson(
      "https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims|labels&languages=de|en&format=json&ids=" +
        ids.join("|"),
    );
    let best = null;
    for (const qid of ids) {
      const ent = ents?.entities?.[qid];
      const claims = ent?.claims;
      if (!claims) continue;
      const imdb = firstString(claims, "P345");
      const tmdbRaw =
        firstString(claims, movie ? "P4947" : "P4983") ||
        firstString(claims, movie ? "P4983" : "P4947");
      const tmdb = tmdbRaw ? parseInt(tmdbRaw, 10) : null;
      if ((!imdb || !imdb.startsWith("tt")) && !tmdb) continue;
      const label = ent.labels?.de?.value || ent.labels?.en?.value || "";
      const sc = score(q, label, year, claims);
      const cand = { imdbId: imdb?.startsWith("tt") ? imdb : null, tmdbId: tmdb || null };
      if (!best || sc > best.score) best = { score: sc, ...cand };
    }
    const hit = best ? { imdbId: best.imdbId, tmdbId: best.tmdbId } : null;
    if (hit) cache.set(key, hit);
    return hit;
  }

  async function enrich(series) {
    if (!series) return series;
    if (series.imdbId && series.tmdbId) return series;
    const ids = await resolve(series.title, series.year, series.mediaKind === "movie");
    if (!ids) return series;
    return {
      ...series,
      imdbId: series.imdbId || ids.imdbId,
      tmdbId: series.tmdbId || ids.tmdbId,
    };
  }

  return { resolve, enrich };
})();
