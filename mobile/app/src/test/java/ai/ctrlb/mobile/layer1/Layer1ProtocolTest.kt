package ai.ctrlb.mobile.layer1

import ai.ctrlb.mobile.domain.MobileProject
import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TimeRange
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Layer1ProtocolTest {
    private val source = SourceMedia("content://media/video/1", "sha256:source", 10_000, 1920, 1080)
    private val project = MobileProject(id = "project-1", title = "Test", source = source, revision = 4)
    private val root = Files.createTempDirectory("ctrlb-layer1-").toFile()
    private val policy = Layer1PathPolicy(setOf(source.uri), setOf(root))
    private val capabilities = Layer1Capabilities(
        commands = setOf(
            "asset.probe", "asset.fingerprint", "proxy.generate", "audio.extract",
            "frame.extract", "frame.crop", "frames.sample", "timeline.remove",
            "timeline.insert", "timeline.move", "clip.transform", "audio.mix",
            "caption.apply", "effect.apply", "preview.render", "export.render"
        ),
        codecs = setOf("audio/opus", "video/avc"),
        maximumSourceDurationMs = 3_600_000,
        maximumExportWidth = 1920,
        maximumExportHeight = 1080
    )

    @Test fun `validates bounded extraction and negotiated capability`() {
        val program = Layer1Program(
            programId = "program-1", projectId = project.id, baseRevision = 4,
            commands = listOf(
                ExtractAudio("audio-1", source.uri, File(root, "audio.opus").path, TimeRange(0, 8_000), "audio/opus", 16_000, 1),
                RenderPreview("preview-1", File(root, "preview.mp4").path)
            )
        )
        Layer1ProtocolValidator.validate(program, project, capabilities, policy)
    }

    @Test fun `rejects stale revisions unsafe paths invalid ranges and unsupported capabilities`() {
        fun rejects(program: Layer1Program) =
            assertThrows(Layer1ProtocolException::class.java) { Layer1ProtocolValidator.validate(program, project, capabilities, policy) }

        rejects(Layer1Program(programId = "stale", projectId = project.id, baseRevision = 3, commands = listOf(ProbeAsset("probe", source.uri))))
        rejects(Layer1Program(programId = "unsafe", projectId = project.id, baseRevision = 4, commands = listOf(RenderPreview("preview", "C:\\Users\\Public\\leak.mp4"))))
        rejects(Layer1Program(programId = "range", projectId = project.id, baseRevision = 4, commands = listOf(ExtractFrame("frame", source.uri, File(root, "frame.jpg").path, 10_001, 320, 180))))
        rejects(Layer1Program(programId = "capability", projectId = project.id, baseRevision = 4, commands = listOf(RenderExport("export", File(root, "out.mp4").path, 3840, 2160, "video/hevc", 20_000_000))))
    }

    @Test fun `applies proposal atomically with exact undo and idempotent replay`() {
        val program = Layer1Program(
            programId = "program-apply", projectId = project.id, baseRevision = 4,
            commands = listOf(RemoveTimelineRanges("remove-1", source.fingerprint, listOf(TimeRange(1_000, 2_000)), listOf("evidence-1")))
        )
        Layer1ProtocolValidator.validate(program, project, capabilities, policy)
        val applied = Layer1ProgramApplier.apply(program, project)

        assertEquals(5, applied.revision)
        assertEquals(1, applied.undo.size)
        assertEquals(project.operations, applied.undoOnce().operations)
        assertSame(applied, Layer1ProgramApplier.apply(program, applied))
    }

    @Test fun `wire decoder rejects executable and unknown commands`() {
        val payload = """
            {
              "protocolVersion": 1,
              "programId": "unsafe-program",
              "projectId": "project-1",
              "baseRevision": 4,
              "commands": [{"type": "shell.exec", "id": "unsafe", "command": "ffmpeg -i source.mp4"}]
            }
        """.trimIndent()

        assertThrows(Exception::class.java) {
            Json { classDiscriminator = "type"; ignoreUnknownKeys = false }
                .decodeFromString<Layer1Program>(payload)
        }
    }
}
