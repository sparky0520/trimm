package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.planning.RequestShapedMediaIndex
import ai.ctrlb.mobile.planner.PersistedAgentRun
import ai.ctrlb.mobile.planner.AgentPlanResponse
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
@Serializable data class CaptionCue(val text: String, val range: TimeRange, val words: List<TranscriptWord> = emptyList())
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

fun DisplaySize.fitWithin(maxWidth: Int, maxHeight: Int): DisplaySize {
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

@Serializable data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float)
@Serializable sealed interface EditInstruction { val id: String; val enabled: Boolean }
@Serializable data class OperationTarget(val sourceId: String, val startMs: Long, val endMs: Long) {
    init { require(sourceId.isNotBlank() && startMs >= 0 && endMs > startMs) }
}
@Serializable @SerialName("remove_ranges") data class RemoveRanges(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val ranges: List<TimeRange>, val confidence: Float, val reason: String = "", val pacing: String = "natural") : EditInstruction
@Serializable @SerialName("set_color_grade") data class SetColorGrade(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val grade: ColorGrade? = null, val target: OperationTarget? = null, val parameter: String? = null, val value: Float? = null) : EditInstruction
@Serializable @SerialName("set_volume") data class SetVolume(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val itemId: String, val volume: Float) : EditInstruction
@Serializable @SerialName("set_aspect_ratio") data class SetAspectRatio(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val ratio: AspectRatio = AspectRatio.PORTRAIT) : EditInstruction
@Serializable @SerialName("add_text_overlay") data class AddTextOverlay(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val text: String,
    val target: OperationTarget? = null,
    val designBlueprint: String = "{}"
) : EditInstruction
@Serializable @SerialName("add_motion_preset") data class AddMotionPreset(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val range: TimeRange? = null, val preset: String, val intensity: Float = .5f) : EditInstruction
@Serializable @SerialName("add_captions") data class AddCaptions(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val cues: List<CaptionCue> = emptyList(), val target: OperationTarget? = null, val speakerId: String? = null, val style: String = "bold-bottom") : EditInstruction
@Serializable @SerialName("set_speed") data class SetSpeed(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val itemId: String, val speed: Float) : EditInstruction
@Serializable @SerialName("add_callout") data class AddCallout(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val text: String, val target: NormalizedRect, val range: TimeRange? = null) : EditInstruction
@Serializable @SerialName("add_spotlight") data class AddSpotlight(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val target: NormalizedRect, val range: TimeRange? = null) : EditInstruction


@Serializable @SerialName("add_bgm") data class AddBgm(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val target: OperationTarget? = null,
    val prompt: String,
    val reason: String = "",
    val url: String = ""
) : EditInstruction

@Serializable @SerialName("add_bgm_effect") data class AddBgmEffect(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val target: OperationTarget? = null,
    val prompt: String,
    val reason: String = "",
    val url: String = ""
) : EditInstruction

@Serializable @SerialName("add_sfx") data class AddSfx(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val target: OperationTarget? = null,
    val prompt: String,
    val reason: String = "",
    val url: String = ""
) : EditInstruction
@Serializable @SerialName("set_rotation") data class SetRotation(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val itemId: String,
    val rotation: Float
) : EditInstruction
@Serializable @SerialName("clear_rotation") data class ClearRotation(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val itemId: String
) : EditInstruction
@Serializable @SerialName("set_crop") data class SetCrop(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val itemId: String,
    val crop: NormalizedRect
) : EditInstruction
@Serializable @SerialName("clear_crop") data class ClearCrop(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val itemId: String
) : EditInstruction
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
    val agentPlan: AgentPlanResponse? = null,
    val agentRun: PersistedAgentRun? = null,
    val editorialPlan: StagedEditorialPlan? = null,
    val pendingProposal: PendingEditorialProposal? = null,
    val revisionConversation: List<EditorialConversationTurn> = emptyList(),
    val appliedEditCheckpoints: List<AppliedEditCheckpoint> = emptyList(),
    val settledConstraints: List<SettledEditorialConstraint> = emptyList(),
    val warnings: List<String> = emptyList(),
    val completedAtEpochMs: Long? = null,
    val activeTurnId: String? = null,
    val activeCheckpointId: String? = null
) {
    val activeConversationPath: List<EditorialConversationTurn> get() {
        val path = mutableListOf<EditorialConversationTurn>()
        var currentId = activeTurnId ?: revisionConversation.lastOrNull()?.id
        while (currentId != null) {
            val turn = revisionConversation.firstOrNull { it.id == currentId }
            if (turn != null) {
                path.add(0, turn)
                currentId = turn.parentId
            } else {
                break
            }
        }
        return path
    }
    
    val activeCheckpointPath: List<AppliedEditCheckpoint> get() {
        val path = mutableListOf<AppliedEditCheckpoint>()
        var currentId = activeCheckpointId ?: appliedEditCheckpoints.lastOrNull()?.id
        while (currentId != null) {
            val cp = appliedEditCheckpoints.firstOrNull { it.id == currentId }
            if (cp != null) {
                path.add(0, cp)
                currentId = cp.parentId
            } else {
                break
            }
        }
        return path
    }
}
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
    val parentId: String? = null,
    val summary: String,
    val changes: List<String>,
    val revisionBefore: Long,
    val revisionAfter: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
@Serializable data class ExportSettings(val width: Int = 1920, val height: Int = 1080, val codec: String = "video/avc", val bitrate: Int = 16_000_000)
@Serializable data class ProjectSnapshot(
    val operations: List<EditInstruction>,
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
    val operations: List<EditInstruction> = emptyList(),
    val undo: List<ProjectSnapshot> = emptyList(),
    val redo: List<ProjectSnapshot> = emptyList(),
    val export: ExportSettings = ExportSettings(),
    val revision: Long = 0,
    val appliedProgramIds: List<String> = emptyList()
) {
    init { require(schemaVersion == 1); require(source.durationMs > 0) }
    val orderedSources: List<SourceMedia> get() = sources.ifEmpty { listOf(source) }
    val durationMs: Long get() = orderedSources.sumOf { it.durationMs }
    private fun withExportRatioRecomputed(): MobileProject {
        var targetRatio = operations.filterIsInstance<SetAspectRatio>().lastOrNull { it.enabled }?.ratio ?: preferences.aspectRatio
        
        operations.filterIsInstance<SetRotation>().lastOrNull { it.enabled }?.let { op ->
            val rotation = op.rotation
            if (rotation == 90f || rotation == 270f) {
                targetRatio = if (targetRatio == AspectRatio.LANDSCAPE) AspectRatio.PORTRAIT else AspectRatio.LANDSCAPE
            }
        }

        val longSide = maxOf(export.width, export.height)
        val shortSide = minOf(export.width, export.height)
        val nextExport = when (targetRatio) {
            AspectRatio.PORTRAIT -> export.copy(width = shortSide, height = longSide)
            AspectRatio.LANDSCAPE -> export.copy(width = longSide, height = shortSide)
            AspectRatio.SQUARE -> export.copy(width = shortSide, height = shortSide)
        }
        return if (export == nextExport) this else copy(export = nextExport)
    }

    private fun snapshot() = ProjectSnapshot(operations, orderedSources, analysis)
    fun replaceEditInstructions(next: List<EditInstruction>) = copy(operations = next, undo = (undo + snapshot()).takeLast(30), redo = emptyList(), revision = revision + 1).withExportRatioRecomputed()
    fun applyEditInstructions(programId: String, next: List<EditInstruction>) =
        copy(
            operations = next,
            undo = (undo + snapshot()).takeLast(30),
            redo = emptyList(),
            revision = revision + 1,
            appliedProgramIds = (appliedProgramIds + programId).takeLast(100)
        ).withExportRatioRecomputed()
    fun markProgramApplied(programId: String) =
        copy(appliedProgramIds = (appliedProgramIds + programId).takeLast(100))
    fun replaceSources(next: List<SourceMedia>): MobileProject {
        require(next.isNotEmpty())
        return copy(source = next.first(), sources = next, undo = (undo + snapshot()).takeLast(30), revision = revision + 1)
    }
    fun restoreCheckpoint() = undo.lastOrNull()?.let { snapshot ->
        val restoredSources = snapshot.sources.ifEmpty { orderedSources }
        copy(
            source = restoredSources.first(),
            sources = restoredSources,
            operations = snapshot.operations,
            analysis = snapshot.analysis ?: analysis,
            undo = undo.dropLast(1),
            redo = (redo + snapshot()).takeLast(30),
            revision = revision + 1
        ).withExportRatioRecomputed()
    } ?: this

    fun redoCheckpoint() = redo.lastOrNull()?.let { snapshot ->
        val restoredSources = snapshot.sources.ifEmpty { orderedSources }
        copy(
            source = restoredSources.first(),
            sources = restoredSources,
            operations = snapshot.operations,
            analysis = snapshot.analysis ?: analysis,
            redo = redo.dropLast(1),
            undo = (undo + snapshot()).takeLast(30),
            revision = revision + 1
        ).withExportRatioRecomputed()
    } ?: this
}
