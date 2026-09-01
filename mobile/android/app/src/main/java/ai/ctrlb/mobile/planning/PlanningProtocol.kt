package ai.ctrlb.mobile.planning

import ai.ctrlb.mobile.domain.MobileProject
import ai.ctrlb.mobile.domain.RemoveRanges
import ai.ctrlb.mobile.domain.TimeRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

const val LAYER1_PROTOCOL_VERSION = 1

@Serializable
data class PlanningProgram(
    val protocolVersion: Int = LAYER1_PROTOCOL_VERSION,
    val programId: String,
    val projectId: String,
    val baseRevision: Long,
    val commands: List<PlanningCommand>
)

@Serializable
sealed interface PlanningCommand {
    val id: String
    val requiredCapability: String
}

@Serializable @SerialName("asset.probe")
data class ProbeAsset(override val id: String, val sourceUri: String) : Layer1Command {
    override val requiredCapability = "asset.probe"
}

@Serializable @SerialName("asset.fingerprint")
data class FingerprintAsset(override val id: String, val sourceUri: String) : Layer1Command {
    override val requiredCapability = "asset.fingerprint"
}

@Serializable @SerialName("proxy.generate")
data class GenerateProxy(
    override val id: String,
    val sourceUri: String,
    val outputUri: String,
    val maximumWidth: Int,
    val maximumHeight: Int
) : PlanningCommand { override val requiredCapability = "proxy.generate" }

@Serializable @SerialName("audio.extract")
data class ExtractAudio(
    override val id: String,
    val sourceUri: String,
    val outputUri: String,
    val range: TimeRange,
    val codec: String,
    val sampleRate: Int,
    val channels: Int
) : PlanningCommand { override val requiredCapability = "audio.extract" }

@Serializable @SerialName("frame.extract")
data class ExtractFrame(
    override val id: String,
    val sourceUri: String,
    val outputUri: String,
    val atMs: Long,
    val maximumWidth: Int,
    val maximumHeight: Int
) : PlanningCommand { override val requiredCapability = "frame.extract" }

@Serializable data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float)

@Serializable @SerialName("frame.crop")
data class CropFrame(
    override val id: String, val sourceUri: String, val outputUri: String,
    val atMs: Long, val crop: NormalizedRect
) : PlanningCommand { override val requiredCapability = "frame.crop" }

@Serializable @SerialName("frames.sample")
data class SampleFrames(
    override val id: String, val sourceUri: String, val outputDirectoryUri: String,
    val range: TimeRange, val maximumCount: Int
) : PlanningCommand { override val requiredCapability = "frames.sample" }

@Serializable @SerialName("timeline.remove")
data class RemoveTimelineRanges(
    override val id: String,
    val sourceFingerprint: String,
    val ranges: List<TimeRange>,
    val evidenceIds: List<String> = emptyList()
) : PlanningCommand { override val requiredCapability = "timeline.remove" }

@Serializable @SerialName("timeline.insert")
data class InsertTimelineRange(
    override val id: String,
    val sourceFingerprint: String,
    val sourceRange: TimeRange,
    val targetStartMs: Long,
    val evidenceIds: List<String> = emptyList()
) : PlanningCommand { override val requiredCapability = "timeline.insert" }

@Serializable @SerialName("timeline.move")
data class MoveTimelineRange(
    override val id: String,
    val timelineRange: TimeRange,
    val targetStartMs: Long
) : PlanningCommand { override val requiredCapability = "timeline.move" }

@Serializable @SerialName("clip.transform")
data class TransformClip(
    override val id: String, val operationId: String, val scale: Float = 1f,
    val rotationDegrees: Float = 0f, val crop: NormalizedRect? = null
) : PlanningCommand { override val requiredCapability = "clip.transform" }

@Serializable @SerialName("audio.mix")
data class MixAudio(
    override val id: String, val operationId: String, val gainDb: Float,
    val duckingDb: Float? = null
) : PlanningCommand { override val requiredCapability = "audio.mix" }

@Serializable @SerialName("caption.apply")
data class ApplyCaptions(
    override val id: String, val operationId: String, val styleId: String
) : PlanningCommand { override val requiredCapability = "caption.apply" }

@Serializable @SerialName("effect.apply")
data class ApplyEffect(
    override val id: String, val operationId: String, val effectId: String,
    val intensity: Float
) : PlanningCommand { override val requiredCapability = "effect.apply" }

@Serializable @SerialName("preview.render")
data class RenderPreview(
    override val id: String,
    val outputUri: String
) : PlanningCommand { override val requiredCapability = "preview.render" }

@Serializable @SerialName("export.render")
data class RenderExport(
    override val id: String,
    val outputUri: String,
    val width: Int,
    val height: Int,
    val codec: String,
    val bitrate: Int
) : PlanningCommand { override val requiredCapability = "export.render" }

@Serializable
data class PlanningCapabilities(
    val protocolVersions: Set<Int> = setOf(LAYER1_PROTOCOL_VERSION),
    val commands: Set<String>,
    val codecs: Set<String>,
    val maximumSourceDurationMs: Long,
    val maximumExportWidth: Int,
    val maximumExportHeight: Int
) {
    fun supports(command: PlanningCommand) = command.requiredCapability in commands
}

data class PlanningPathPolicy(
    val selectedMediaUris: Set<String>,
    val appOwnedRoots: Set<File>
) {
    fun permitsInput(value: String): Boolean =
        value in selectedMediaUris || permitsAppOwnedFile(value)

    fun permitsOutput(value: String): Boolean = permitsAppOwnedFile(value)

    private fun permitsAppOwnedFile(value: String): Boolean {
        val path = when {
            value.startsWith("file://") -> value.removePrefix("file://")
            value.startsWith("/") || Regex("^[A-Za-z]:[/\\\\].*").matches(value) -> value
            else -> return false
        }
        val candidate = File(path).canonicalFile
        return appOwnedRoots.any { root ->
            val canonicalRoot = root.canonicalFile
            candidate == canonicalRoot || candidate.toPath().startsWith(canonicalRoot.toPath())
        }
    }
}

class PlanningProtocolException(message: String) : IllegalArgumentException(message)

object PlanningProtocolValidator {
    fun validate(
        program: PlanningProgram,
        project: MobileProject,
        capabilities: PlanningCapabilities,
        paths: PlanningPathPolicy
    ) {
        check(program.protocolVersion in capabilities.protocolVersions) { "Unsupported Layer 1 protocol version" }
        check(program.projectId == project.id) { "Program targets another project" }
        check(program.baseRevision == project.revision) { "Stale project revision" }
        check(program.programId.isNotBlank()) { "Program id is required" }
        check(program.commands.isNotEmpty()) { "Program must contain commands" }
        check(program.commands.map { it.id }.all { it.isNotBlank() }) { "Command id is required" }
        check(program.commands.map { it.id }.distinct().size == program.commands.size) { "Duplicate command id" }
        check(program.commands.all(capabilities::supports)) { "Unsupported Layer 1 capability" }
        check(project.durationMs <= capabilities.maximumSourceDurationMs) { "Project exceeds device duration capability" }

        program.commands.forEach { command ->
            when (command) {
                is ProbeAsset -> requireInput(command.sourceUri, paths)
                is FingerprintAsset -> requireInput(command.sourceUri, paths)
                is GenerateProxy -> {
                    requireInput(command.sourceUri, paths); requireOutput(command.outputUri, paths)
                    check(command.maximumWidth > 0 && command.maximumHeight > 0) { "Invalid proxy dimensions" }
                }
                is ExtractAudio -> {
                    requireInput(command.sourceUri, paths); requireOutput(command.outputUri, paths)
                    check(command.range.endMs <= project.durationMs) { "Audio range exceeds project" }
                    check(command.codec in capabilities.codecs && command.sampleRate in 8_000..48_000 && command.channels in 1..2) { "Unsupported audio settings" }
                }
                is ExtractFrame -> {
                    requireInput(command.sourceUri, paths); requireOutput(command.outputUri, paths)
                    check(command.atMs in 0 until project.durationMs) { "Frame timestamp exceeds project" }
                    check(command.maximumWidth > 0 && command.maximumHeight > 0) { "Invalid frame dimensions" }
                }
                is CropFrame -> {
                    requireInput(command.sourceUri, paths); requireOutput(command.outputUri, paths)
                    check(command.atMs in 0 until project.durationMs && validRect(command.crop)) { "Invalid frame crop" }
                }
                is SampleFrames -> {
                    requireInput(command.sourceUri, paths); requireOutput(command.outputDirectoryUri, paths)
                    check(command.range.endMs <= project.durationMs && command.maximumCount in 1..120) { "Invalid frame sampling request" }
                }
                is RemoveTimelineRanges -> {
                    requireSource(command.sourceFingerprint, project)
                    check(command.ranges.isNotEmpty() && command.ranges.all { it.endMs <= project.durationMs }) { "Invalid removal range" }
                }
                is InsertTimelineRange -> {
                    requireSource(command.sourceFingerprint, project)
                    check(command.sourceRange.endMs <= sourceDuration(command.sourceFingerprint, project)) { "Insert range exceeds source" }
                    check(command.targetStartMs >= 0) { "Invalid insert target" }
                }
                is MoveTimelineRange -> {
                    check(command.timelineRange.endMs <= project.durationMs && command.targetStartMs >= 0) { "Invalid move range" }
                }
                is TransformClip -> check(command.operationId.isNotBlank() && command.scale in 0.1f..4f && command.rotationDegrees in -360f..360f && (command.crop == null || validRect(command.crop))) { "Invalid clip transform" }
                is MixAudio -> check(command.operationId.isNotBlank() && command.gainDb in -60f..24f && (command.duckingDb == null || command.duckingDb in 0f..30f)) { "Invalid audio mix" }
                is ApplyCaptions -> check(command.operationId.isNotBlank() && command.styleId.matches(Regex("^[a-z0-9-]{1,40}$"))) { "Invalid caption command" }
                is ApplyEffect -> check(command.operationId.isNotBlank() && command.effectId.matches(Regex("^[a-z0-9-]{1,40}$")) && command.intensity in 0f..1f) { "Invalid effect command" }
                is RenderPreview -> requireOutput(command.outputUri, paths)
                is RenderExport -> {
                    requireOutput(command.outputUri, paths)
                    check(command.width in 1..capabilities.maximumExportWidth && command.height in 1..capabilities.maximumExportHeight) { "Unsupported export dimensions" }
                    check(command.codec in capabilities.codecs && command.bitrate > 0) { "Unsupported export settings" }
                }
            }
        }
    }

    private fun requireInput(uri: String, paths: PlanningPathPolicy) =
        check(paths.permitsInput(uri)) { "Unsafe input path" }

    private fun requireOutput(uri: String, paths: PlanningPathPolicy) =
        check(paths.permitsOutput(uri)) { "Unsafe output path" }

    private fun requireSource(fingerprint: String, project: MobileProject) =
        check(project.orderedSources.any { it.fingerprint == fingerprint }) { "Unknown source fingerprint" }

    private fun sourceDuration(fingerprint: String, project: MobileProject) =
        project.orderedSources.first { it.fingerprint == fingerprint }.durationMs

    private fun validRect(rect: NormalizedRect) =
        rect.x >= 0f && rect.y >= 0f && rect.width > 0f && rect.height > 0f &&
            rect.x + rect.width <= 1f && rect.y + rect.height <= 1f

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw PlanningProtocolException(message())
    }
}

/**
 * Applies timeline mutations as one project revision and one undo checkpoint.
 * Replaying a completed program is a no-op; non-timeline commands are executed
 * by the media workers after the same validation step.
 */
object PlanningProgramApplier {
    fun apply(program: PlanningProgram, project: MobileProject): MobileProject {
        if (program.programId in project.appliedProgramIds) return project
        val removals = program.commands.filterIsInstance<RemoveTimelineRanges>()
        if (removals.isEmpty()) return project.markProgramApplied(program.programId)
        val operations = project.operations + removals.map { command ->
            RemoveRanges(
                id = "layer1:${command.id}",
                ranges = command.ranges,
                confidence = 1f,
                reason = command.evidenceIds.firstOrNull() ?: "Layer 1 edit program"
            )
        }
        return project.applyEditOperation(program.programId, operations)
    }
}
