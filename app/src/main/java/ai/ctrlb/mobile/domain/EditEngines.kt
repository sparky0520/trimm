package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.evidence.EditorialProfile
import kotlin.math.max
import kotlin.math.min

object FumbleDetector {
    private val fillers = setOf("um", "uh", "erm", "hmm", "ah")
    fun detect(words: List<TranscriptWord>, durationMs: Long, pace: Pace, profile: EditorialProfile? = null): List<FumbleCandidate> {
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<FumbleCandidate>()
        val fallbackPauseLimit = when (pace) { Pace.TIGHT -> 1100L; Pace.NATURAL -> 1700L; Pace.RELAXED -> 2400L }
        val pauseLimit = profile?.distributions?.get("pauseMs")?.let { distribution ->
            when {
                distribution.count < 3 -> fallbackPauseLimit
                profile.policy.pauseCutPercentile <= .65f -> distribution.p75.toLong()
                profile.policy.pauseCutPercentile <= .8f -> ((distribution.p75 + distribution.p90) / 2).toLong()
                else -> distribution.p90.toLong()
            }.coerceIn(700L, 4_000L)
        } ?: fallbackPauseLimit
        words.zipWithNext().forEach { (a, b) ->
            val gap = b.range.startMs - a.range.endMs
            if (gap >= pauseLimit) {
                out += hint(
                    words, listOf(words.indexOf(a), words.indexOf(b)),
                    TimeRange(a.range.endMs + 180, b.range.startMs - 180),
                    min(.98f, .82f + gap / 20_000f), "pause-candidate"
                )
            }
        }
        words.forEachIndexed { index, word ->
            if (word.text.lowercase().trim('.', ',', ' ') in fillers) {
                val start = max(0, word.range.startMs - 70); val end = min(durationMs, word.range.endMs + 90)
                if (end > start) out += hint(
                    words, listOf(index), TimeRange(start, end), word.confidence,
                    if (word.confidence < .75f) "possible-filler-transcript-uncertain" else "possible-filler"
                )
            }
            if (word.confidence < .75f) {
                out += hint(words, listOf(index), word.range, word.confidence, "transcript-uncertainty")
            }
            if (index >= 2) {
                val left = words.subList(index - 2, index)
                val right = words.drop(index).take(2)
                val phrase = left.joinToString(" ") { it.text.lowercase() }
                val next = right.joinToString(" ") { it.text.lowercase() }
                val speakers = (left + right).mapNotNull { it.speakerId }.distinct()
                if (phrase == next && next.isNotBlank() && speakers.size <= 1) {
                    out += hint(
                        words, (index - 2 until min(index + 2, words.size)).toList(),
                        TimeRange(words[index - 2].range.startMs, words[index].range.startMs),
                        (left + right).minOf(TranscriptWord::confidence), "repeated-phrase-candidate"
                    )
                }
            }
        }
        return out.distinctBy { Triple(it.range, it.reason, it.context) }.sortedWith(
            compareBy<FumbleCandidate> { it.range.startMs }.thenBy { it.reason }
        )
    }

    private fun hint(
        words: List<TranscriptWord>,
        indexes: List<Int>,
        range: TimeRange,
        confidence: Float,
        reason: String
    ): FumbleCandidate {
        val first = indexes.minOrNull() ?: 0
        val last = indexes.maxOrNull() ?: first
        val selected = indexes.mapNotNull(words::getOrNull)
        return FumbleCandidate(
            range = range,
            confidence = confidence.coerceIn(0f, 1f),
            reason = reason,
            contextBefore = words.subList(max(0, first - 4), first).joinToString(" ", transform = TranscriptWord::text),
            context = selected.joinToString(" ", transform = TranscriptWord::text),
            contextAfter = words.subList(min(words.size, last + 1), min(words.size, last + 5))
                .joinToString(" ", transform = TranscriptWord::text),
            transcriptConfidence = selected.minOfOrNull(TranscriptWord::confidence),
            speakerIds = selected.mapNotNull(TranscriptWord::speakerId).toSet()
        )
    }
}

class TimelineMapper(removals: List<TimeRange>) {
    val removals = removals.sortedBy { it.startMs }.fold(mutableListOf<TimeRange>()) { acc, range ->
        val last = acc.lastOrNull()
        if (last != null && range.startMs <= last.endMs) acc[acc.lastIndex] = TimeRange(last.startMs, max(last.endMs, range.endMs)) else acc += range
        acc
    }
    fun sourceToOutput(sourceMs: Long): Long = sourceMs - removals.sumOf { min(max(sourceMs - it.startMs, 0), it.durationMs) }
    fun outputToSource(outputMs: Long): Long {
        var source = outputMs
        removals.forEach { if (it.startMs <= source) source += it.durationMs }
        return source
    }
}

object GradeEngine {
    data class FrameStats(val luma: Float, val warmth: Float, val contrast: Float, val skinConfidence: Float)
    fun natural(stats: List<FrameStats>, style: VisualStyle): ColorGrade {
        if (stats.isEmpty()) return ColorGrade()
        val luma = stats.map { it.luma }.average().toFloat(); val warmth = stats.map { it.warmth }.average().toFloat(); val contrast = stats.map { it.contrast }.average().toFloat()
        val styleWarmth = when (style) { VisualStyle.WARM -> .16f; VisualStyle.CLEAN -> -.04f; else -> 0f }
        val punch = if (style == VisualStyle.PUNCHY) .12f else 0f
        return ColorGrade(
            exposure = ((.52f - luma) * .9f).coerceIn(-.45f, .45f),
            temperature = (styleWarmth - warmth * .35f).coerceIn(-.3f, .3f),
            contrast = (1.08f - contrast * .12f + punch).coerceIn(.9f, 1.25f),
            saturation = (1.04f + punch * .5f).coerceIn(.9f, 1.2f), highlights = -.08f, shadows = .08f
        )
    }
}

object CaptionSegmenter {
    private const val MAX_CHARACTERS = 42
    private const val MAX_DURATION_MS = 2_800L
    private const val GAP_BREAK_MS = 650L

    fun segment(words: List<TranscriptWord>): List<CaptionCue> {
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<CaptionCue>()
        var group = mutableListOf<TranscriptWord>()
        fun flush() {
            if (group.isEmpty()) return
            result += CaptionCue(
                group.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+([,.!?])"), "$1"),
                TimeRange(group.first().range.startMs, group.last().range.endMs)
            )
            group = mutableListOf()
        }
        words.sortedBy { it.range.startMs }.forEach { word ->
            val proposedLength = group.sumOf { it.text.length + 1 } + word.text.length
            val proposedDuration = if (group.isEmpty()) 0 else word.range.endMs - group.first().range.startMs
            val gap = group.lastOrNull()?.let { word.range.startMs - it.range.endMs } ?: 0
            if (group.isNotEmpty() && (proposedLength > MAX_CHARACTERS || proposedDuration > MAX_DURATION_MS || gap > GAP_BREAK_MS)) flush()
            group += word
            if (word.text.trim().lastOrNull() in setOf('.', '!', '?') && group.size >= 3) flush()
        }
        flush()
        return result
    }
}
