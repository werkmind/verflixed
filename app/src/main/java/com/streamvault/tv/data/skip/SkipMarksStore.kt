package com.streamvault.tv.data.skip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Learns per-series credits lead time from manual „Nächste Folge“ presses
 * and builds adaptive next-prompt windows + heuristic skip plans.
 */
class SkipMarksStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("verflixed_skip_marks", Context.MODE_PRIVATE)

    fun creditsLeadMs(seriesId: String): Long? {
        val arr = samples(seriesId)
        if (arr.isEmpty()) return null
        val sorted = arr.sorted()
        return sorted[sorted.size / 2]
    }

    fun recordCreditsLead(seriesId: String, leadMs: Long) {
        val clamped = leadMs.coerceIn(15_000L, 8 * 60_000L)
        val next = (samples(seriesId) + clamped).takeLast(8)
        sp.edit().putString(key(seriesId), JSONArray(next).toString()).apply()
    }

    fun buildPlan(
        episodeId: String,
        seriesId: String,
        durationMs: Long,
        aniskip: List<SkipSegment>,
    ): EpisodeSkipPlan {
        val ed = aniskip.filter { it.type == SkipSegment.Type.CREDITS }.minByOrNull { it.startMs }
        val learned = creditsLeadMs(seriesId)
        val heuristic = heuristicLeadMs(durationMs)
        val lead = when {
            ed != null && durationMs > 0 -> (durationMs - ed.startMs).coerceIn(20_000L, 10 * 60_000L)
            learned != null -> learned
            else -> heuristic
        }
        val segments = aniskip.toMutableList()
        if (ed == null && durationMs > lead) {
            // Synthetic credits window from adaptive lead (non-anime / no AniSkip).
            segments += SkipSegment(
                type = SkipSegment.Type.CREDITS,
                startMs = (durationMs - lead).coerceAtLeast(0L),
                endMs = durationMs,
                source = if (learned != null) "learned" else "heuristic",
            )
        }
        return EpisodeSkipPlan(
            episodeId = episodeId,
            segments = segments.sortedBy { it.startMs },
            nextPromptLeadMs = lead,
        )
    }

    /** Duration-aware fallback: short eps ~45–75s, long eps up to ~3.5 min. */
    fun heuristicLeadMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 60_000L
        val mins = durationMs / 60_000.0
        val lead = when {
            mins < 15 -> 45_000L
            mins < 30 -> (durationMs * 0.07).roundToLong().coerceIn(50_000L, 90_000L)
            mins < 50 -> (durationMs * 0.08).roundToLong().coerceIn(70_000L, 150_000L)
            else -> (durationMs * 0.09).roundToLong().coerceIn(90_000L, 210_000L)
        }
        return lead
    }

    private fun samples(seriesId: String): List<Long> {
        val raw = sp.getString(key(seriesId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getLong(i))
            }
        }.getOrDefault(emptyList())
    }

    private fun key(seriesId: String) = "credits:$seriesId"

    fun exportDebug(seriesId: String): JSONObject =
        JSONObject()
            .put("seriesId", seriesId)
            .put("samples", JSONArray(samples(seriesId)))
            .put("median", creditsLeadMs(seriesId))
}
