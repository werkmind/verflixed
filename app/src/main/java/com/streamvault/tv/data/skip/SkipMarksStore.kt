package com.streamvault.tv.data.skip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Learns per-series skip windows from user actions and builds adaptive plans:
 * - Credits lead („Nächste Folge“ / „Weiter schauen“)
 * - Intro end (manual Intro-Skip)
 * - Heuristic fallbacks when AniSkip / metadata missing
 */
class SkipMarksStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("verflixed_skip_marks", Context.MODE_PRIVATE)

    fun creditsLeadMs(seriesId: String): Long? = median(samples(creditsKey(seriesId)))

    fun introEndMs(seriesId: String): Long? = median(samples(introKey(seriesId)))

    fun rememberedMalId(seriesId: String): Int? =
        sp.getInt(malKey(seriesId), 0).takeIf { it > 0 }

    fun rememberMalId(seriesId: String, malId: Int) {
        if (malId <= 0) return
        sp.edit().putInt(malKey(seriesId), malId).apply()
    }

    fun recordCreditsLead(seriesId: String, leadMs: Long) {
        appendSample(creditsKey(seriesId), leadMs.coerceIn(15_000L, 8 * 60_000L))
    }

    /** User stayed past our prompt → credits are at least this long. */
    fun recordCreditsLeadAtLeast(seriesId: String, remainingMs: Long) {
        val bumped = (remainingMs + 20_000L).coerceIn(30_000L, 10 * 60_000L)
        val current = creditsLeadMs(seriesId) ?: 0L
        if (bumped > current) recordCreditsLead(seriesId, bumped)
    }

    fun recordIntroEnd(seriesId: String, endMs: Long) {
        appendSample(introKey(seriesId), endMs.coerceIn(8_000L, 4 * 60_000L))
    }

    fun buildPlan(
        episodeId: String,
        seriesId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        durationMs: Long,
        crowd: List<SkipSegment>,
    ): EpisodeSkipPlan {
        val ed = crowd.filter { it.type == SkipSegment.Type.CREDITS }.minByOrNull { it.startMs }
        val hasIntroMeta = crowd.any { it.type == SkipSegment.Type.INTRO }
        val learnedCredits = creditsLeadMs(seriesId)
        val learnedIntro = introEndMs(seriesId)
        val heuristicCredits = heuristicLeadMs(durationMs)
        val lead = when {
            ed != null && durationMs > 0 ->
                (durationMs - ed.startMs).coerceIn(20_000L, 10 * 60_000L)
            learnedCredits != null -> learnedCredits
            else -> heuristicCredits
        }
        val segments = crowd.toMutableList()

        if (!hasIntroMeta) {
            val introEnd = learnedIntro ?: heuristicIntroEndMs(durationMs, episodeNumber)
            if (introEnd != null && introEnd in 8_000L..(durationMs / 3).coerceAtLeast(8_000L)) {
                segments += SkipSegment(
                    type = SkipSegment.Type.INTRO,
                    startMs = 0L,
                    endMs = introEnd,
                    source = if (learnedIntro != null) "learned" else "heuristic",
                )
            }
        }

        if (ed == null && durationMs > lead) {
            segments += SkipSegment(
                type = SkipSegment.Type.CREDITS,
                startMs = (durationMs - lead).coerceAtLeast(0L),
                endMs = durationMs,
                source = if (learnedCredits != null) "learned" else "heuristic",
            )
        }

        return EpisodeSkipPlan(
            episodeId = episodeId,
            segments = segments
                .groupBy { it.type }
                .flatMap { (_, list) -> list.sortedByDescending { quality(it.source) }.take(1) }
                .sortedBy { it.startMs },
            nextPromptLeadMs = lead,
        )
    }

    /** Duration-aware fallback: short eps ~45–75s, long eps up to ~3.5 min. */
    fun heuristicLeadMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 60_000L
        val mins = durationMs / 60_000.0
        return when {
            mins < 15 -> 45_000L
            mins < 30 -> (durationMs * 0.07).roundToLong().coerceIn(50_000L, 90_000L)
            mins < 50 -> (durationMs * 0.08).roundToLong().coerceIn(70_000L, 150_000L)
            else -> (durationMs * 0.09).roundToLong().coerceIn(90_000L, 210_000L)
        }
    }

    /**
     * Typical cold-open/intro window when no crowd-sourced markers exist.
     * Episode 1 of a season often has a longer cold open → slightly wider window.
     */
    fun heuristicIntroEndMs(durationMs: Long, episodeNumber: Int): Long? {
        if (durationMs < 12 * 60_000L) return null // shorts / specials: don't guess
        val mins = durationMs / 60_000.0
        val base = when {
            mins < 25 -> 75_000L
            mins < 45 -> 90_000L
            else -> 110_000L
        }
        val openerBoost = if (episodeNumber <= 1) 25_000L else 0L
        return (base + openerBoost).coerceAtMost((durationMs * 0.18).roundToLong())
    }

    fun exportDebug(seriesId: String): JSONObject =
        JSONObject()
            .put("seriesId", seriesId)
            .put("creditsSamples", JSONArray(samples(creditsKey(seriesId))))
            .put("creditsMedian", creditsLeadMs(seriesId))
            .put("introSamples", JSONArray(samples(introKey(seriesId))))
            .put("introMedian", introEndMs(seriesId))
            .put("malId", rememberedMalId(seriesId))

    private fun quality(source: String): Int = when (source) {
        "aniskip", "theintrodb", "skipdb" -> 3
        "learned" -> 2
        else -> 1
    }

    private fun appendSample(key: String, value: Long) {
        val next = (samples(key) + value).takeLast(8)
        sp.edit().putString(key, JSONArray(next).toString()).apply()
    }

    private fun median(arr: List<Long>): Long? {
        if (arr.isEmpty()) return null
        val sorted = arr.sorted()
        return sorted[sorted.size / 2]
    }

    private fun samples(key: String): List<Long> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getLong(i))
            }
        }.getOrDefault(emptyList())
    }

    private fun creditsKey(seriesId: String) = "credits:$seriesId"
    private fun introKey(seriesId: String) = "intro:$seriesId"
    private fun malKey(seriesId: String) = "mal:$seriesId"
}
