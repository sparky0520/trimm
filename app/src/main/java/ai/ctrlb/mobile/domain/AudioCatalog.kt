package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.evidence.StoryGraph
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

@Serializable enum class AudioKind { MUSIC, SOUND_EFFECT }
@Serializable data class AudioSection(
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 0f
)
@Serializable data class AudioEnergyPoint(val atMs: Long, val energy: Float)
@Serializable data class AudioAnalysisDescriptor(
    val analyzerVersion: String,
    val durationMs: Long,
    val integratedLufs: Float? = null,
    val truePeakDbtp: Float? = null,
    val bpm: Float? = null,
    val beatConfidence: Float = 0f,
    val sections: List<AudioSection> = emptyList(),
    val energyCurve: List<AudioEnergyPoint> = emptyList(),
    val tags: Set<String> = emptySet()
)
@Serializable data class AudioCatalogItem(
    val id: String,
    val kind: AudioKind,
    val assetPath: String = "",
    val durationMs: Long,
    val bpm: Float? = null,
    val energy: Float = .5f,
    val moods: Set<String> = emptySet(),
    val licenseId: String,
    val checksum: String,
    val downloadUrl: String? = null,
    val attribution: String? = null,
    val userImported: Boolean = false,
    val title: String = id,
    val artist: String = "",
    val previewUrl: String? = null,
    val sourceUrl: String? = null,
    val attributionRequired: Boolean = false,
    val provider: String = "",
    val providerAssetId: String = "",
    val acquiredAt: String = "",
    val licenseText: String? = null,
    val allowedUse: Set<String> = emptySet(),
    val status: String = "quarantined",
    val quarantineReasons: Set<String> = emptySet(),
    val redistributionAllowed: Boolean = false,
    val integratedLufs: Float? = null,
    val truePeakDbtp: Float? = null,
    val beatConfidence: Float = 0f,
    val sections: List<AudioSection> = emptyList(),
    val energyCurve: List<AudioEnergyPoint> = emptyList(),
    val tags: Set<String> = emptySet(),
    val analysisVersion: String = "",
    val analysis: AudioAnalysisDescriptor? = null
)
data class AudioMatch(val item: AudioCatalogItem, val score: Float, val reasons: List<String>)

data class StoryAudioContext(
    val editDurationMs: Long,
    val speechRanges: List<TimeRange>,
    val storyEvents: List<StoryAudioEvent>,
    val toneTags: Set<String>,
    val energyArc: List<AudioEnergyPoint>,
    val editFingerprint: String
) {
    init { require(editDurationMs > 0 && editFingerprint.isNotBlank()) }

    companion object {
        fun from(story: StoryGraph?, editDurationMs: Long): StoryAudioContext {
            val beats = story?.beats.orEmpty().filter { it.status != ai.ctrlb.mobile.evidence.StoryMaterialStatus.REPETITIVE }
            val ranges = beats.mapNotNull { it.candidateRanges.firstOrNull()?.range }.sortedBy { it.startMs }
            val events = beats.mapNotNull { beat ->
                val range = beat.candidateRanges.firstOrNull()?.range ?: return@mapNotNull null
                StoryAudioEvent(
                    id = beat.id, atMs = range.startMs, kind = beat.role,
                    evidenceIds = beat.evidenceIds, confidence = beat.confidence,
                    visuallyGrounded = beat.supportingVisualEvidenceIds.isNotEmpty()
                )
            }
            val tone = beats.flatMap { beat ->
                (beat.claims.flatMap { it.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) } + beat.role)
            }.filter { it.length > 2 }.toSet()
            val arc = beats.mapNotNull { beat ->
                beat.candidateRanges.firstOrNull()?.range?.let { AudioEnergyPoint(it.startMs, beat.importance) }
            }
            return StoryAudioContext(editDurationMs, ranges, events, tone, arc,
                story?.evidenceFingerprint ?: stableHash("empty-story|$editDurationMs"))
        }
    }
}

data class StoryAudioEvent(
    val id: String,
    val atMs: Long,
    val kind: String,
    val evidenceIds: Set<String>,
    val confidence: Float,
    val visuallyGrounded: Boolean
)

data class AudioScoreExplanation(
    val durationFit: Float,
    val speechCompatibility: Float,
    val toneFit: Float,
    val energyArcFit: Float,
    val sectionFit: Float,
    val beatConfidence: Float
) {
    fun reasons(): List<String> = listOf(
        "duration fit ${percent(durationFit)}",
        "speech compatibility ${percent(speechCompatibility)}",
        "tone ${percent(toneFit)}",
        "energy arc ${percent(energyArcFit)}",
        "section fit ${percent(sectionFit)}",
        "beat confidence ${percent(beatConfidence)}"
    )
}

data class StoryAudioMatch(
    val item: AudioCatalogItem,
    val score: Float,
    val explanation: AudioScoreExplanation
)

data class MusicComparisonChoice(
    val catalogId: String?,
    val label: String,
    val previewTimelineStartMs: Long,
    val previewSourceStartMs: Long,
    val previewGainDb: Float,
    val score: Float?,
    val reasons: List<String>
)

data class MusicCandidateSet(
    val rankingVersion: String,
    val resultFingerprint: String,
    val choices: List<MusicComparisonChoice>
)

data class SoundEffectRecommendation(
    val item: AudioCatalogItem,
    val atMs: Long,
    val eventId: String,
    val evidenceIds: Set<String>,
    val score: Float,
    val reason: String
)

object AudioMatcher {
    const val RANKING_VERSION = "story-audio-ranking-v1"
    private const val MUSIC_LIMIT = 3
    private const val SFX_MIN_SPACING_MS = 3_000L
    private const val SFX_BUDGET_WINDOW_MS = 10_000L

    fun rankMusic(
        items: List<AudioCatalogItem>,
        context: StoryAudioContext,
        catalogVersion: String,
        modelVersion: String = RANKING_VERSION
    ): List<StoryAudioMatch> = eligible(items, AudioKind.MUSIC).map { item ->
        val durationFit = durationFit(item.durationMs, context.editDurationMs)
        val speechCoverage = mergedDuration(context.speechRanges).toFloat() / context.editDurationMs
        val speechCompatibility = (1f - speechCoverage * (.35f + item.energy * .35f)).coerceIn(0f, 1f)
        val assetTone = (item.moods + item.effectiveTags()).map(String::lowercase).toSet()
        val overlap = assetTone.count(context.toneTags::contains)
        val toneFit = if (assetTone.isEmpty() || context.toneTags.isEmpty()) .5f
            else (.35f + .65f * overlap / max(1, assetTone.size).toFloat()).coerceIn(0f, 1f)
        val energyFit = curveFit(item.effectiveEnergyCurve().ifEmpty { listOf(AudioEnergyPoint(0, item.energy)) }, context.energyArc)
        val sectionFit = sectionFit(item, context)
        val beatConfidence = item.effectiveBeatConfidence().coerceIn(0f, 1f)
        val explanation = AudioScoreExplanation(durationFit, speechCompatibility, toneFit, energyFit, sectionFit, beatConfidence)
        val score = durationFit * .22f + speechCompatibility * .22f + toneFit * .16f +
            energyFit * .18f + sectionFit * .14f + beatConfidence * .08f
        StoryAudioMatch(item, rounded(score), explanation)
    }.sortedWith(compareByDescending<StoryAudioMatch> { it.score }.thenBy { it.item.id }).take(MUSIC_LIMIT)

    fun comparisonSet(
        matches: List<StoryAudioMatch>,
        context: StoryAudioContext,
        catalogVersion: String,
        modelVersion: String = RANKING_VERSION
    ): MusicCandidateSet {
        val choices = matches.map { match ->
            MusicComparisonChoice(
                match.item.id, match.item.title, 0, 0, previewGain(match.item),
                match.score, match.explanation.reasons()
            )
        } + MusicComparisonChoice(null, "No music", 0, 0, 0f, null,
            listOf("Keep the edit dialogue-only"))
        val identity = listOf(modelVersion, catalogVersion, context.editFingerprint,
            matches.joinToString(",") { "${it.item.id}:${it.score}" }).joinToString("|")
        return MusicCandidateSet(modelVersion, stableHash(identity), choices)
    }

    fun rankSoundEffects(
        items: List<AudioCatalogItem>,
        context: StoryAudioContext
    ): List<SoundEffectRecommendation> {
        val assets = eligible(items, AudioKind.SOUND_EFFECT)
        if (assets.isEmpty()) return emptyList()
        val candidates = context.storyEvents.asSequence()
            .filter { it.confidence >= .65f && it.evidenceIds.isNotEmpty() && (it.visuallyGrounded || it.kind.isNotBlank()) }
            .sortedWith(compareByDescending<StoryAudioEvent> { it.confidence }.thenBy { it.atMs }.thenBy { it.id })
            .mapNotNull { event ->
                val terms = event.kind.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank).toSet()
                val item = assets.maxWithOrNull(compareBy<AudioCatalogItem> {
                    ((it.effectiveTags() + it.moods).map(String::lowercase).toSet() intersect terms).size
                }.thenByDescending { it.id }) ?: return@mapNotNull null
                val tagMatch = ((item.effectiveTags() + item.moods).map(String::lowercase).toSet() intersect terms).size
                if (tagMatch == 0) return@mapNotNull null
                SoundEffectRecommendation(item, event.atMs, event.id, event.evidenceIds,
                    rounded(event.confidence * .8f + .2f), "Matches grounded ${event.kind} event")
            }.toList()
        val accepted = mutableListOf<SoundEffectRecommendation>()
        for (candidate in candidates) {
            if (accepted.any { abs(it.atMs - candidate.atMs) < SFX_MIN_SPACING_MS }) continue
            if (accepted.count { it.atMs / SFX_BUDGET_WINDOW_MS == candidate.atMs / SFX_BUDGET_WINDOW_MS } >= 1) continue
            accepted += candidate
        }
        return accepted.sortedWith(compareBy<SoundEffectRecommendation> { it.atMs }.thenBy { it.item.id })
    }

    @Deprecated("Use rankMusic with StoryAudioContext; visual style is not story evidence.")
    fun rank(items: List<AudioCatalogItem>, targetDurationMs: Long, style: VisualStyle, kind: AudioKind = AudioKind.MUSIC): List<AudioMatch> {
        val targetEnergy = when (style) { VisualStyle.PUNCHY -> .8f; VisualStyle.CLEAN -> .45f; VisualStyle.WARM -> .35f; VisualStyle.NATURAL -> .4f }
        val desiredMood = style.name.lowercase()
        return items.asSequence().filter {
            it.kind == kind &&
                it.status == "ready" &&
                it.quarantineReasons.isEmpty() &&
                "rendered-video" in it.allowedUse &&
                (it.licenseId.isNotBlank() || !it.licenseText.isNullOrBlank()) &&
                it.checksum.matches(Regex("[a-fA-F0-9]{64}")) &&
                (!it.attributionRequired || !it.attribution.isNullOrBlank())
        }.map { item ->
            val energyScore = 1f - abs(item.energy - targetEnergy).coerceAtMost(1f)
            val durationScore = if (item.durationMs >= targetDurationMs) 1f else (item.durationMs.toFloat() / targetDurationMs).coerceIn(.2f, 1f)
            val moodScore = if (desiredMood in item.moods) 1f else .55f
            AudioMatch(item, energyScore * .5f + durationScore * .3f + moodScore * .2f, listOf("energy", "duration", "mood"))
        }.sortedWith(compareByDescending<AudioMatch> { it.score }.thenBy { it.item.id }).toList()
    }

    private fun eligible(items: List<AudioCatalogItem>, kind: AudioKind) = items.asSequence().filter {
        it.kind == kind && it.status == "ready" && it.quarantineReasons.isEmpty() &&
            "rendered-video" in it.allowedUse && (it.licenseId.isNotBlank() || !it.licenseText.isNullOrBlank()) &&
            it.checksum.matches(Regex("[a-fA-F0-9]{64}")) && (!it.attributionRequired || !it.attribution.isNullOrBlank())
    }.toList()

    private fun durationFit(assetMs: Long, editMs: Long): Float {
        val ratio = assetMs.toFloat() / editMs
        return when {
            ratio >= 1f -> (1f - (ratio - 1f).coerceAtMost(2f) * .1f)
            else -> (.25f + ratio * .75f)
        }.coerceIn(0f, 1f)
    }

    private fun sectionFit(item: AudioCatalogItem, context: StoryAudioContext): Float {
        if (item.effectiveSections().isEmpty()) return .35f
        val confident = item.effectiveSections().filter { it.confidence >= .4f && it.endMs > it.startMs }
        if (confident.isEmpty()) return .35f
        val coverage = confident.sumOf { it.endMs - it.startMs }.toFloat() / context.editDurationMs
        val storyChanges = context.storyEvents.drop(1).map { it.atMs }
        val sectionChanges = confident.drop(1).map { it.startMs }
        val aligned = if (storyChanges.isEmpty() || sectionChanges.isEmpty()) .5f else
            storyChanges.count { story -> sectionChanges.any { abs(it - story) <= 1_500 } }.toFloat() / storyChanges.size
        return (coverage.coerceAtMost(1f) * .6f + aligned * .4f).coerceIn(0f, 1f)
    }

    private fun curveFit(asset: List<AudioEnergyPoint>, story: List<AudioEnergyPoint>): Float {
        if (story.isEmpty()) return .5f
        fun normalized(points: List<AudioEnergyPoint>, index: Int, size: Int) =
            points.minByOrNull { abs(it.atMs.toFloat() / max(1L, points.last().atMs) - index.toFloat() / max(1, size - 1)) }?.energy ?: .5f
        val error = story.indices.sumOf { index ->
            abs(normalized(asset, index, story.size) - story[index].energy).toDouble()
        }.toFloat() / story.size
        return (1f - error).coerceIn(0f, 1f)
    }

    private fun previewGain(item: AudioCatalogItem): Float {
        val loudnessGain = (item.analysis?.integratedLufs ?: item.integratedLufs)?.let { -16f - it } ?: 0f
        val peakGain = (item.analysis?.truePeakDbtp ?: item.truePeakDbtp)?.let { -1f - it } ?: loudnessGain
        return rounded(minOf(loudnessGain, peakGain).coerceIn(-24f, 12f))
    }

    private fun mergedDuration(ranges: List<TimeRange>): Long {
        if (ranges.isEmpty()) return 0
        val sorted = ranges.sortedBy { it.startMs }
        var start = sorted.first().startMs
        var end = sorted.first().endMs
        var total = 0L
        for (range in sorted.drop(1)) {
            if (range.startMs <= end) end = max(end, range.endMs)
            else { total += end - start; start = range.startMs; end = range.endMs }
        }
        return total + end - start
    }
}

private fun AudioCatalogItem.effectiveTags() = analysis?.tags ?: tags
private fun AudioCatalogItem.effectiveSections() = analysis?.sections ?: sections
private fun AudioCatalogItem.effectiveEnergyCurve() = analysis?.energyCurve ?: energyCurve
private fun AudioCatalogItem.effectiveBeatConfidence() = analysis?.beatConfidence ?: beatConfidence
private fun rounded(value: Float) = kotlin.math.round(value * 10_000f) / 10_000f
private fun percent(value: Float) = "${kotlin.math.round(value.coerceIn(0f, 1f) * 100).toInt()}%"
private fun stableHash(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
