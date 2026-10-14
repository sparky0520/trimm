package ai.trimm.mobile.domain

import ai.trimm.mobile.planning.RequestShapedMediaIndex
import ai.trimm.mobile.planner.PersistedAgentRun
import ai.trimm.mobile.planner.AgentPlanResponse
import ai.trimm.mobile.evidence.TakeGroup
import ai.trimm.mobile.evidence.EditorialProfile
import ai.trimm.mobile.evidence.StoryGraph
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
@Serializable @SerialName("set_speed") data class SetSpeed(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val itemId: String = "", val speed: Float, val target: OperationTarget? = null) : EditInstruction
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
@Serializable @SerialName("normalise_audio") data class NormaliseAudio(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val parameter: String? = null,
    val dialogueLufs: Float = -20f,
    val musicDuckDb: Float = -15f,
    val attackMs: Int = 150,
    val releaseMs: Int = 350,
    val target: OperationTarget? = null
) : EditInstruction
@Serializable @SerialName("set_audio_levels") data class SetAudioLevels(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val dialogueLufs: Float = -20f,
    val musicDuckDb: Float = -15f,
    val target: OperationTarget? = null
) : EditInstruction
@Serializable @SerialName("add_section_transition") data class AddSectionTransition(
    override val id: String = UUID.randomUUID().toString(),
    override val enabled: Boolean = true,
    val preset: String,
    val target: OperationTarget? = null
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
    val chatHistory: List<EditorialConversationTurn> = emptyList(),
    val appliedEditCheckpoints: List<AppliedEditCheckpoint> = emptyList(),
    val settledConstraints: List<SettledEditorialConstraint> = emptyList(),
    val warnings: List<String> = emptyList(),
    val completedAtEpochMs: Long? = null,
    val activeTurnId: String? = null,
    val activeCheckpointId: String? = null
) {
    val activeChatPath: List<EditorialConversationTurn> get() {
        val path = mutableListOf<EditorialConversationTurn>()
        var currentId = activeTurnId ?: chatHistory.lastOrNull()?.id
        while (currentId != null) {
            val turn = chatHistory.firstOrNull { it.id == currentId }
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
@Serializable data class ExportSettings(val width: Int, val height: Int, val codec: String = "video/avc", val bitrate: Int = 16_000_000)

object ExportSettingsResolver {
    fun resolve(
        source: SourceMedia,
        operations: List<EditInstruction> = emptyList(),
        current: ExportSettings? = null
    ): ExportSettings {
        val isRotated = source.rotation == 90 || source.rotation == 270
        val effectiveWidth = if (isRotated) source.height else source.width
        val effectiveHeight = if (isRotated) source.width else source.height

        val shortSide = minOf(effectiveWidth, effectiveHeight).takeIf { it > 0 } ?: 1080
        val longSide = maxOf(effectiveWidth, effectiveHeight).takeIf { it > 0 } ?: 1920

        val ratio = effectiveWidth.toFloat() / effectiveHeight.toFloat().coerceAtLeast(1f)
        var targetRatio = when {
            ratio in 0.9f..1.1f -> AspectRatio.SQUARE
            effectiveWidth > effectiveHeight -> AspectRatio.LANDSCAPE
            else -> AspectRatio.PORTRAIT
        }

        for (op in operations) {
            if (op is SetAspectRatio && op.enabled) {
                targetRatio = op.ratio
            }
        }

        operations.filterIsInstance<SetRotation>().lastOrNull { it.enabled }?.let { op ->
            val rotation = op.rotation
            if (kotlin.math.abs(rotation % 180f) == 90f) {
                targetRatio = if (targetRatio == AspectRatio.LANDSCAPE) AspectRatio.PORTRAIT else AspectRatio.LANDSCAPE
            }
        }

        val (outW, outH) = when (targetRatio) {
            AspectRatio.PORTRAIT -> shortSide to longSide
            AspectRatio.SQUARE -> shortSide to shortSide
            AspectRatio.LANDSCAPE -> longSide to shortSide
        }

        return ExportSettings(
            width = outW,
            height = outH,
            codec = current?.codec ?: "video/avc",
            bitrate = current?.bitrate ?: 16_000_000
        )
    }
}

@Serializable data class ProjectSnapshot(
    val operations: List<EditInstruction> = emptyList(),
    val sources: List<SourceMedia> = emptyList(),
    val analysis: Analysis? = null,
    val schema: CompositionSchema? = null
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
    val export: ExportSettings = ExportSettings(1080, 1920),
    val revision: Long = 0,
    val appliedProgramIds: List<String> = emptyList()
) {
    init { require(schemaVersion == 1); require(source.durationMs > 0) }
    val orderedSources: List<SourceMedia> get() = sources.ifEmpty { listOf(source) }
    val durationMs: Long get() = orderedSources.sumOf { it.durationMs }
    private fun withExportRatioRecomputed(): MobileProject {
        val nextExport = ExportSettingsResolver.resolve(source, operations, export)
        return if (export == nextExport) this else copy(export = nextExport)
    }

    fun snapshot(schema: CompositionSchema? = null) = ProjectSnapshot(operations, orderedSources, analysis, schema)
    fun replaceEditInstructions(next: List<EditInstruction>) = copy(operations = next, undo = (undo + snapshot()).takeLast(30), redo = emptyList(), revision = revision + 1).withExportRatioRecomputed()
    fun applyEditInstructions(
        next: List<EditInstruction>,
        removedOperations: List<String> = emptyList(),
        activeTurnId: String? = null,
        request: String? = null,
        response: String? = null,
        currentSchema: CompositionSchema? = null
    ): MobileProject {
        val currentTurnId = activeTurnId ?: UUID.randomUUID().toString()
        val newHistory = if (request != null && response != null) {
            (analysis.chatHistory + EditorialConversationTurn(
                id = currentTurnId,
                request = request,
                response = response
            )).takeLast(100)
        } else {
            analysis.chatHistory
        }

        val newAnalysis = analysis.copy(
            activeTurnId = currentTurnId,
            chatHistory = newHistory
        )
        val finalOperations = operations.filter { it.id !in removedOperations } + next
        val newUndo = (undo + snapshot(currentSchema)).takeLast(30)
        return copy(
            analysis = newAnalysis,
            operations = finalOperations,
            undo = newUndo,
            redo = emptyList(),
            revision = revision + 1
        ).withExportRatioRecomputed()
    }
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
        val currentSourcesByFingerprint = orderedSources.associateBy { it.fingerprint }
        val updatedRestoredSources = restoredSources.map { source ->
            currentSourcesByFingerprint[source.fingerprint]?.let { source.copy(uri = it.uri) } ?: source
        }
        copy(
            source = updatedRestoredSources.first(),
            sources = updatedRestoredSources,
            operations = snapshot.operations,
            analysis = snapshot.analysis ?: analysis,
            undo = undo.dropLast(1),
            redo = (redo + snapshot()).takeLast(30),
            revision = revision + 1
        ).withExportRatioRecomputed()
    } ?: this

    fun redoCheckpoint() = redo.lastOrNull()?.let { snapshot ->
        val restoredSources = snapshot.sources.ifEmpty { orderedSources }
        val currentSourcesByFingerprint = orderedSources.associateBy { it.fingerprint }
        val updatedRestoredSources = restoredSources.map { source ->
            currentSourcesByFingerprint[source.fingerprint]?.let { source.copy(uri = it.uri) } ?: source
        }
        copy(
            source = updatedRestoredSources.first(),
            sources = updatedRestoredSources,
            operations = snapshot.operations,
            analysis = snapshot.analysis ?: analysis,
            redo = redo.dropLast(1),
            undo = (undo + snapshot()).takeLast(30),
            revision = revision + 1
        ).withExportRatioRecomputed()
    } ?: this
}
