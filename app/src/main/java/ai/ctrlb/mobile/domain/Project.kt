package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.layer1.RequestShapedMediaIndex
import ai.ctrlb.mobile.planner.PersistedAgentRun
import ai.ctrlb.mobile.evidence.TakeGroup
import ai.ctrlb.mobile.evidence.EditorialProfile
import ai.ctrlb.mobile.evidence.StoryGraph
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class TimeRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
    val durationMs get() = endMs - startMs
}
@Serializable data class TranscriptWord(
    val text: String,
    val range: TimeRange,
    val confidence: Float,
    val speakerId: String? = null
)
@Serializable data class CaptionCue(val text: String, val range: TimeRange)
@Serializable data class SourceMedia(val uri: String, val fingerprint: String, val durationMs: Long, val width: Int, val height: Int, val rotation: Int = 0, val hasAudio: Boolean = true)
@Serializable data class WizardPreferences(
    val platform: Platform = Platform.YOUTUBE,
    val aspectRatio: AspectRatio = AspectRatio.LANDSCAPE,
    val pace: Pace = Pace.NATURAL,
    val style: VisualStyle = VisualStyle.NATURAL,
    val captions: Boolean = true,
    val music: MusicChoice = MusicChoice.NONE,
    val mustKeep: String = ""
)
@Serializable enum class Platform { YOUTUBE, INSTAGRAM, TIKTOK, LINKEDIN }
@Serializable enum class AspectRatio { LANDSCAPE, PORTRAIT, SQUARE }

data class DisplaySize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val longSide: Int get() = maxOf(width, height)
    val shortSide: Int get() = minOf(width, height)
    val aspectRatio: Float get() = width.toFloat() / height
}

fun SourceMedia.displaySize(): DisplaySize {
    val normalizedRotation = ((rotation % 360) + 360) % 360
    return if (normalizedRotation == 90 || normalizedRotation == 270) {
        DisplaySize(height, width)
    } else {
        DisplaySize(width, height)
    }
}

fun SourceMedia.detectedAspectRatio(): AspectRatio {
    val displayed = displaySize()
    val ratio = displayed.aspectRatio
    return when {
        ratio in .9f..1.1f -> AspectRatio.SQUARE
        displayed.width > displayed.height -> AspectRatio.LANDSCAPE
        else -> AspectRatio.PORTRAIT
    }
}

fun SourceMedia.renderSizeWithin(maxLongSide: Int, maxShortSide: Int): DisplaySize {
    require(maxLongSide > 0 && maxShortSide > 0)
    val displayed = displaySize()
    val square = displayed.width == displayed.height
    val maxWidth = if (square) maxShortSide else if (displayed.width > displayed.height) maxLongSide else maxShortSide
    val maxHeight = if (square) maxShortSide else if (displayed.height > displayed.width) maxLongSide else maxShortSide
    return displayed.fitWithin(maxWidth, maxHeight)
}

private fun DisplaySize.fitWithin(maxWidth: Int, maxHeight: Int): DisplaySize {
    val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    fun even(value: Int): Int = if (value <= 2) value else value - value % 2
    return DisplaySize(
        even((width * scale).toInt()).coerceAtLeast(1),
        even((height * scale).toInt()).coerceAtLeast(1)
    )
}

fun List<SourceMedia>.renderSizeWithin(maxLongSide: Int, maxShortSide: Int): DisplaySize {
    require(isNotEmpty())
    val displayed = map(SourceMedia::displaySize)
    val primary = displayed.first()
    val square = primary.width == primary.height
    val maxWidth = if (square) maxShortSide else if (primary.width > primary.height) maxLongSide else maxShortSide
    val maxHeight = if (square) maxShortSide else if (primary.height > primary.width) maxLongSide else maxShortSide
    return primary.fitWithin(
        minOf(maxWidth, displayed.minOf(DisplaySize::width)),
        minOf(maxHeight, displayed.minOf(DisplaySize::height))
    )
}
@Serializable enum class Pace { RELAXED, NATURAL, TIGHT }
@Serializable enum class VisualStyle { NATURAL, WARM, CLEAN, PUNCHY }
@Serializable enum class MusicChoice { AUTOMATIC, USER_FILE, NONE }

@Serializable sealed interface EditOperation { val id: String; val enabled: Boolean }
@Serializable data class OperationTarget(val sourceId: String, val startMs: Long, val endMs: Long) {
    init { require(sourceId.isNotBlank() && startMs >= 0 && endMs > startMs) }
}
@Serializable @SerialName("remove_ranges") data class RemoveRanges(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val ranges: List<TimeRange>, val confidence: Float, val reason: String = "", val pacing: String = "natural") : EditOperation
@Serializable @SerialName("set_color_grade") data class SetColorGrade(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val grade: ColorGrade? = null, val target: OperationTarget? = null, val parameter: String? = null, val value: Float? = null) : EditOperation
@Serializable @SerialName("set_audio_levels") data class SetAudioLevels(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val parameter: String? = null, val dialogueLufs: Float = -16f, val musicDuckDb: Float = -9f, val attackMs: Long = 120, val releaseMs: Long = 450, val target: OperationTarget? = null) : EditOperation
@Serializable enum class VfxType { SINGLE_CLIP, DUAL_CLIP }
@Serializable @SerialName("add_vfx") data class AddVfx(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val kind: VfxType,
    val range: TimeRange,
    val fragmentShader: String,
    val uniforms: Map<String, Float> = emptyMap()
) : EditOperation
@Serializable @SerialName("add_motion_preset") data class AddMotionPreset(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val range: TimeRange, val preset: String, val intensity: Float = .5f) : EditOperation

@Serializable @SerialName("add_captions") data class AddCaptions(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val cues: List<CaptionCue> = emptyList(), val target: OperationTarget? = null, val speakerId: String? = null, val style: String = "bold-bottom") : EditOperation


@Serializable data class ColorGrade(val exposure: Float = 0f, val temperature: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f, val highlights: Float = 0f, val shadows: Float = 0f, val skinProtection: Float = .8f) {
    init { require(exposure in -1f..1f && temperature in -1f..1f && contrast in .75f..1.35f && saturation in .7f..1.3f && highlights in -1f..1f && shadows in -1f..1f && skinProtection in 0f..1f) }
}
@Serializable data class Analysis(
    val words: List<TranscriptWord> = emptyList(),
    val captions: List<CaptionCue> = emptyList(),
    val speechHints: List<FumbleCandidate> = emptyList(),
    // Retained for decoding existing projects; new analysis writes speechHints.
    val lowConfidenceFumbles: List<FumbleCandidate> = emptyList(),
    val takeGroups: List<TakeGroup> = emptyList(),
    val editorialProfile: EditorialProfile? = null,
    val storyGraph: StoryGraph? = null,
    val mediaIndex: RequestShapedMediaIndex? = null,
    val agentRun: PersistedAgentRun? = null,
    val editorialPlan: StagedEditorialPlan? = null,
    val pendingProposal: PendingEditorialProposal? = null,
    val revisionConversation: List<EditorialConversationTurn> = emptyList(),
    val appliedEditCheckpoints: List<AppliedEditCheckpoint> = emptyList(),
    val settledConstraints: List<SettledEditorialConstraint> = emptyList(),
    val warnings: List<String> = emptyList(),
    val completedAtEpochMs: Long? = null
)
@Serializable data class EvidenceGraphReference(val sourceFingerprint: String, val schemaVersion: Int = 1, val relativeCachePath: String, val analyzerVersions: Map<String, String>, val partial: Boolean)
@Serializable data class FumbleCandidate(
    val range: TimeRange,
    // Confidence describes the local measurement, never confidence that the
    // speech should be removed.
    val confidence: Float,
    val reason: String,
    val contextBefore: String = "",
    val context: String = "",
    val contextAfter: String = "",
    val transcriptConfidence: Float? = null,
    val speakerIds: Set<String> = emptySet(),
    val provenance: String = "local-transcript-timing"
)
@Serializable data class AppliedEditCheckpoint(
    val id: String = UUID.randomUUID().toString(),
    val summary: String,
    val changes: List<String>,
    val revisionBefore: Long,
    val revisionAfter: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
@Serializable data class ExportSettings(val width: Int = 1920, val height: Int = 1080, val codec: String = "video/avc", val bitrate: Int = 16_000_000)
@Serializable data class ProjectSnapshot(
    val operations: List<EditOperation>,
    val sources: List<SourceMedia> = emptyList(),
    val analysis: Analysis? = null
)
@Serializable data class MobileProject(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val source: SourceMedia,
    val sources: List<SourceMedia> = emptyList(),
    val proxyUri: String? = null,
    val preferences: WizardPreferences = WizardPreferences(),
    val creatorPreferences: CreatorPreferenceProfile = CreatorPreferenceProfile(),
    val analysis: Analysis = Analysis(),
    val evidenceGraphs: List<EvidenceGraphReference> = emptyList(),
    val operations: List<EditOperation> = emptyList(),
    val undo: List<ProjectSnapshot> = emptyList(),
    val export: ExportSettings = ExportSettings(),
    val revision: Long = 0,
    val appliedLayer1ProgramIds: List<String> = emptyList()
) {
    init { require(schemaVersion == 1); require(source.durationMs > 0) }
    val orderedSources: List<SourceMedia> get() = sources.ifEmpty { listOf(source) }
    val durationMs: Long get() = orderedSources.sumOf { it.durationMs }
    private fun snapshot() = ProjectSnapshot(operations, orderedSources, analysis)
    fun replaceOperations(next: List<EditOperation>) = copy(operations = next, undo = (undo + snapshot()).takeLast(30), revision = revision + 1)
    fun applyLayer1Program(programId: String, next: List<EditOperation>) =
        copy(
            operations = next,
            undo = (undo + snapshot()).takeLast(30),
            revision = revision + 1,
            appliedLayer1ProgramIds = (appliedLayer1ProgramIds + programId).takeLast(100)
        )
    fun markLayer1ProgramApplied(programId: String) =
        copy(appliedLayer1ProgramIds = (appliedLayer1ProgramIds + programId).takeLast(100))
    fun replaceSources(next: List<SourceMedia>): MobileProject {
        require(next.isNotEmpty())
        return copy(source = next.first(), sources = next, undo = (undo + snapshot()).takeLast(30), revision = revision + 1)
    }
    fun undoOnce() = undo.lastOrNull()?.let { snapshot ->
        val restoredSources = snapshot.sources.ifEmpty { orderedSources }
        copy(
            source = restoredSources.first(),
            sources = restoredSources,
            operations = snapshot.operations,
            analysis = snapshot.analysis ?: analysis,
            undo = undo.dropLast(1),
            revision = revision + 1
        )
    } ?: this
}
