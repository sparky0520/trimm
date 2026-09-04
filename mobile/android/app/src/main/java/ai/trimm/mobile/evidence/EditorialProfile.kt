package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.Pace
import ai.trimm.mobile.domain.Platform
import ai.trimm.mobile.domain.TimeRange
import ai.trimm.mobile.domain.WizardPreferences
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.ceil

@Serializable
data class RobustDistribution(
    val count: Int,
    val median: Float,
    val p10: Float,
    val p25: Float,
    val p75: Float,
    val p90: Float,
    val medianAbsoluteDeviation: Float
) {
    fun percentile(value: Float): Float = when {
        count < 2 -> .5f
        value <= p10 -> .1f
        value <= p25 -> interpolate(value, p10, p25, .1f, .25f)
        value <= median -> interpolate(value, p25, median, .25f, .5f)
        value <= p75 -> interpolate(value, median, p75, .5f, .75f)
        value <= p90 -> interpolate(value, p75, p90, .75f, .9f)
        else -> .9f
    }

    private fun interpolate(value: Float, low: Float, high: Float, lowRank: Float, highRank: Float) =
        if (high == low) highRank else lowRank + (value - low) / (high - low) * (highRank - lowRank)
}

@Serializable
data class EditorialSegment(
    val id: String,
    val sourceFingerprint: String,
    val speakerId: String? = null,
    val recordingSetup: String,
    val distributions: Map<String, RobustDistribution>
)

@Serializable
data class EditorialPolicy(
    val pauseCutPercentile: Float,
    val automaticCutConfidence: Float,
    val repeatedWordingThreshold: Float,
    val nearTieDelta: Float,
    val rankingWeights: Map<String, Float>,
    val safetyBounds: Map<String, Float>,
    val reasons: List<String>
)

@Serializable
data class EditorialProfile(
    val schemaVersion: Int = 1,
    val policyVersion: String = "adaptive-editorial-v1",
    val evidenceFingerprint: String,
    val requestedPace: Pace,
    val platform: Platform,
    val distributions: Map<String, RobustDistribution>,
    val segments: List<EditorialSegment>,
    val policy: EditorialPolicy,
    val defaultsUsed: List<String>,
    val unavailableSignals: List<String>
) {
    init {
        require(schemaVersion == 1)
        require(evidenceFingerprint.isNotBlank())
    }
}

object EditorialProfileDeriver {
    private const val MIN_DISTRIBUTION_SAMPLES = 3
    private val signalNames = setOf(
        "pauseMs", "speakingRateWpm", "transcriptConfidence", "shotChangeMagnitude",
        "motion", "shake", "exposure", "blur", "noiseFloor", "loudness", "clipping"
    )

    fun derive(graphs: List<EvidenceGraph>, preferences: WizardPreferences): EditorialProfile {
        require(graphs.isNotEmpty())
        val values = mutableMapOf<String, MutableList<Float>>()
        graphs.forEach { collect(it, values) }
        val distributions = values.mapNotNull { (name, samples) ->
            distribution(samples)?.let { name to it }
        }.toMap()
        val defaults = mutableListOf<String>()
        val pausePercentile = when (preferences.pace) {
            Pace.TIGHT -> .65f
            Pace.NATURAL -> .78f
            Pace.RELAXED -> .88f
        }
        if ((values["pauseMs"]?.size ?: 0) < MIN_DISTRIBUTION_SAMPLES) defaults +=
            "Pause policy uses a documented pace fallback because fewer than $MIN_DISTRIBUTION_SAMPLES pauses were observed."
        if ((values["transcriptConfidence"]?.size ?: 0) < MIN_DISTRIBUTION_SAMPLES) defaults +=
            "Destructive confidence uses the 0.93 safety floor because transcript evidence is sparse."
        val transcript = distributions["transcriptConfidence"]
        val automaticConfidence = maxOf(.93f, transcript?.p25 ?: .93f).coerceAtMost(.985f)
        val confidenceSpread = transcript?.let { it.p90 - it.p10 } ?: 0f
        val wordingThreshold = (.70f + confidenceSpread * .12f).coerceIn(.68f, .82f)
        val nearTie = (.035f + confidenceSpread * .08f).coerceIn(.03f, .08f)
        val weights = linkedMapOf(
            "completeness" to .18f, "clarity" to .16f, "delivery" to .16f,
            "technicalQuality" to .14f, "continuity" to .12f, "storyFit" to .18f,
            "editRisk" to -.06f
        )
        val segments = graphs.flatMap { graph ->
            val speakers = graph.observations.filterIsInstance<SpeechEvidence>()
                .filter { it.kind == SpeechKind.SPEAKER_REGION }.mapNotNull { it.speakerId }.distinct()
            (listOf<String?>(null) + speakers).map { speaker ->
                val segmentValues = mutableMapOf<String, MutableList<Float>>()
                collect(graph, segmentValues, speaker)
                EditorialSegment(
                    id = "${graph.sourceFingerprint}:${speaker ?: "all"}",
                    sourceFingerprint = graph.sourceFingerprint,
                    speakerId = speaker,
                    recordingSetup = recordingSetup(graph),
                    distributions = segmentValues.mapNotNull { (name, samples) -> distribution(samples)?.let { name to it } }.toMap()
                )
            }
        }
        return EditorialProfile(
            evidenceFingerprint = fingerprint(graphs, preferences),
            requestedPace = preferences.pace,
            platform = preferences.platform,
            distributions = distributions,
            segments = segments,
            policy = EditorialPolicy(
                pauseCutPercentile = pausePercentile,
                automaticCutConfidence = automaticConfidence,
                repeatedWordingThreshold = wordingThreshold,
                nearTieDelta = nearTie,
                rankingWeights = weights,
                safetyBounds = mapOf("minimumAutomaticCutConfidence" to .93f, "minimumCutMarginMs" to 180f),
                reasons = listOf(
                    "Requested ${preferences.pace.name.lowercase()} pace selects the pause percentile.",
                    "Take similarity and near-tie tolerance scale with this video's transcript-confidence spread.",
                    "Ranking weights are versioned heuristic policy and are disclosed with every take group."
                )
            ),
            defaultsUsed = defaults,
            unavailableSignals = signalNames.filter { it !in values || values[it].isNullOrEmpty() }.sorted()
        )
    }

    private fun collect(
        graph: EvidenceGraph,
        output: MutableMap<String, MutableList<Float>>,
        speakerId: String? = null
    ) {
        fun add(name: String, value: Float?) { if (value != null && value.isFinite()) output.getOrPut(name, ::mutableListOf).add(value) }
        val speech = graph.observations.filterIsInstance<SpeechEvidence>()
        val speakerRanges = if (speakerId == null) emptyList() else speech.filter {
            it.kind == SpeechKind.SPEAKER_REGION && it.speakerId == speakerId
        }.map { it.range }
        fun allowed(range: TimeRange) = speakerId == null || speakerRanges.any { it.overlaps(range) }
        speech.filter { allowed(it.range) }.forEach {
            when (it.kind) {
                SpeechKind.PAUSE -> add("pauseMs", it.range.durationMs.toFloat())
                SpeechKind.WORD, SpeechKind.SENTENCE -> add("transcriptConfidence", it.confidence)
                else -> Unit
            }
        }
        val words = speech.filter { it.kind == SpeechKind.WORD && allowed(it.range) }
        if (words.isNotEmpty()) {
            val spanMs = words.maxOf { it.range.endMs } - words.minOf { it.range.startMs }
            if (spanMs > 0) add("speakingRateWpm", words.size * 60_000f / spanMs)
        }
        graph.observations.filterIsInstance<VisualEvidence>().filter { allowed(it.range) }.forEach {
            val name = when (it.kind) {
                VisualKind.MOTION -> "motion"
                VisualKind.SHAKE -> "shake"
                VisualKind.EXPOSURE -> "exposure"
                VisualKind.BLUR -> "blur"
                else -> null
            }
            if (name != null) add(name, it.value)
        }
        graph.observations.filterIsInstance<SoundEvidence>().filter { allowed(it.range) }.forEach {
            val name = when (it.kind) {
                SoundKind.NOISE_FLOOR -> "noiseFloor"
                SoundKind.LOUDNESS -> "loudness"
                SoundKind.CLIPPING -> "clipping"
                else -> null
            }
            if (name != null) add(name, it.value)
        }
        graph.observations.filterIsInstance<ShotEvidence>().zipWithNext().forEach { (left, right) ->
            add("shotChangeMagnitude", ((right.range.startMs - left.range.endMs).coerceAtLeast(0) / 1_000f).coerceIn(0f, 1f))
        }
    }

    private fun distribution(values: List<Float>): RobustDistribution? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun q(rank: Float) = sorted[(ceil(rank * (sorted.size - 1)).toInt()).coerceIn(sorted.indices)]
        val median = q(.5f)
        val deviations = sorted.map { kotlin.math.abs(it - median) }.sorted()
        val mad = deviations[(deviations.size - 1) / 2]
        return RobustDistribution(sorted.size, median, q(.1f), q(.25f), q(.75f), q(.9f), mad)
    }

    private fun recordingSetup(graph: EvidenceGraph): String {
        val width = graph.observations.filterIsInstance<FrameEvidence>().firstOrNull()?.width
        val height = graph.observations.filterIsInstance<FrameEvidence>().firstOrNull()?.height
        return when {
            width == null || height == null -> "unknown"
            height > width -> "portrait-camera"
            width > height -> "landscape-camera"
            else -> "square-camera"
        }
    }

    private fun fingerprint(graphs: List<EvidenceGraph>, preferences: WizardPreferences): String {
        val material = buildString {
            append("adaptive-editorial-v1|${preferences.pace}|${preferences.platform}|")
            graphs.sortedBy { it.sourceFingerprint }.forEach { graph ->
                append("${graph.sourceFingerprint}:${graph.schemaVersion}:")
                graph.observations.sortedBy { it.id }.forEach {
                    append("${it.id}:${it.range.startMs}:${it.range.endMs}:${it.confidence}:")
                    when (it) {
                        is VisualEvidence -> append("${it.kind}:${it.value}")
                        is SoundEvidence -> append("${it.kind}:${it.value}")
                        is SpeechEvidence -> append("${it.kind}:${it.text}:${it.speakerId}")
                        is ShotEvidence -> append(it.representativeFrameIds.joinToString(","))
                        is FrameEvidence -> append("${it.width}x${it.height}")
                    }
                    append(';')
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }

    private fun TimeRange.overlaps(other: TimeRange) =
        startMs < other.endMs && other.startMs < endMs
}
