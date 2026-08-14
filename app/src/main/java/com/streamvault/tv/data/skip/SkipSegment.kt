package com.streamvault.tv.data.skip

/**
 * Timed skip window inside an episode (AniSkip / learned credits / heuristics).
 */
data class SkipSegment(
    val type: Type,
    val startMs: Long,
    val endMs: Long,
    val source: String,
) {
    enum class Type { INTRO, RECAP, CREDITS, PREVIEW }

    fun contains(positionMs: Long): Boolean =
        positionMs in startMs until endMs.coerceAtLeast(startMs + 1)

    val label: String
        get() = when (type) {
            Type.INTRO -> "Intro überspringen"
            Type.RECAP -> "Rückblick überspringen"
            Type.CREDITS -> "Abspann überspringen"
            Type.PREVIEW -> "Vorschau überspringen"
        }
}

data class EpisodeSkipPlan(
    val episodeId: String,
    val segments: List<SkipSegment>,
    /** How early before absolute end to show „Nächste Folge“ (adaptive). */
    val nextPromptLeadMs: Long,
)
