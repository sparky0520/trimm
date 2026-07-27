package ai.ctrlb.mobile.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class TimeRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
    val durationMs get() = endMs - startMs
}
@Serializable data class TranscriptWord(val text: String, val range: TimeRange, val confidence: Float)
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
@Serializable enum class Pace { RELAXED, NATURAL, TIGHT }
@Serializable enum class VisualStyle { NATURAL, WARM, CLEAN, PUNCHY }
@Serializable enum class MusicChoice { AUTOMATIC, USER_FILE, NONE }

@Serializable sealed interface EditOperation { val id: String; val enabled: Boolean }
@Serializable @SerialName("remove_fumbles") data class RemoveFumbles(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val ranges: List<TimeRange>, val confidence: Float, val reasons: List<String>) : EditOperation
@Serializable @SerialName("set_color_grade") data class SetColorGrade(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val grade: ColorGrade) : EditOperation
@Serializable @SerialName("set_audio_cleanup") data class SetAudioCleanup(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val strength: Float = .55f) : EditOperation
@Serializable @SerialName("set_audio_levels") data class SetAudioLevels(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val dialogueLufs: Float = -16f, val musicDuckDb: Float = -9f) : EditOperation
@Serializable @SerialName("add_transition") data class AddTransition(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val atSourceMs: Long, val preset: String = "dip", val durationMs: Long = 280) : EditOperation
@Serializable @SerialName("add_motion_preset") data class AddMotionPreset(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val range: TimeRange, val preset: String, val intensity: Float = .5f) : EditOperation
@Serializable @SerialName("add_sound_effect") data class AddSoundEffect(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val atSourceMs: Long, val catalogId: String, val gainDb: Float = -12f) : EditOperation
@Serializable @SerialName("set_background_music") data class SetBackgroundMusic(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val catalogId: String, val gainDb: Float = -24f, val loop: Boolean = true) : EditOperation
@Serializable @SerialName("add_captions") data class AddCaptions(override val id: String = UUID.randomUUID().toString(), override val enabled: Boolean = true, val cues: List<CaptionCue>, val style: String = "bold-bottom") : EditOperation

@Serializable data class ColorGrade(val exposure: Float = 0f, val temperature: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f, val highlights: Float = 0f, val shadows: Float = 0f, val skinProtection: Float = .8f) {
    init { require(exposure in -1f..1f && temperature in -1f..1f && contrast in .75f..1.35f && saturation in .7f..1.3f && highlights in -1f..1f && shadows in -1f..1f && skinProtection in 0f..1f) }
}
@Serializable data class Analysis(val words: List<TranscriptWord> = emptyList(), val captions: List<CaptionCue> = emptyList(), val lowConfidenceFumbles: List<FumbleCandidate> = emptyList(), val warnings: List<String> = emptyList(), val completedAtEpochMs: Long? = null)
@Serializable data class EvidenceGraphReference(val sourceFingerprint: String, val schemaVersion: Int = 1, val relativeCachePath: String, val analyzerVersions: Map<String, String>, val partial: Boolean)
@Serializable data class FumbleCandidate(val range: TimeRange, val confidence: Float, val reason: String)
@Serializable data class ExportSettings(val width: Int = 1920, val height: Int = 1080, val codec: String = "video/avc", val bitrate: Int = 16_000_000)
@Serializable data class ProjectSnapshot(val operations: List<EditOperation>, val sources: List<SourceMedia> = emptyList())
@Serializable data class MobileProject(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val source: SourceMedia,
    val sources: List<SourceMedia> = emptyList(),
    val proxyUri: String? = null,
    val preferences: WizardPreferences = WizardPreferences(),
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
    private fun snapshot() = ProjectSnapshot(operations, orderedSources)
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
        copy(source = restoredSources.first(), sources = restoredSources, operations = snapshot.operations, undo = undo.dropLast(1), revision = revision + 1)
    } ?: this
}
