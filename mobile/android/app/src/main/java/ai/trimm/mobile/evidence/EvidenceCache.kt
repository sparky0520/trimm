package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.SourceMedia
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class EvidenceCache(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }

    fun artifactDirectory(source: SourceMedia, analyzer: AnalyzerIdentity): File =
        File(root, "${source.fingerprint}/${safe(analyzer.name)}-${safe(analyzer.version)}/artifacts").apply { mkdirs() }

    fun readAnalyzer(source: SourceMedia, analyzer: AnalyzerIdentity): List<EvidenceObservation>? {
        val file = analyzerFile(source, analyzer)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(ListSerializer(EvidenceObservation.serializer()), file.readText()) }.getOrNull()
    }

    fun writeAnalyzer(source: SourceMedia, analyzer: AnalyzerIdentity, observations: List<EvidenceObservation>) =
        atomicWrite(analyzerFile(source, analyzer), json.encodeToString(ListSerializer(EvidenceObservation.serializer()), observations))

    fun writeGraph(graph: EvidenceGraph) =
        atomicWrite(File(root, "${graph.sourceFingerprint}/graph-v${graph.schemaVersion}.json"), json.encodeToString(EvidenceGraph.serializer(), graph))

    fun readGraph(sourceFingerprint: String): EvidenceGraph? {
        val file = File(root, "$sourceFingerprint/graph-v1.json")
        return file.takeIf(File::isFile)?.let { runCatching { json.decodeFromString(EvidenceGraph.serializer(), it.readText()) }.getOrNull() }
    }

    private fun analyzerFile(source: SourceMedia, analyzer: AnalyzerIdentity) =
        File(root, "${source.fingerprint}/${safe(analyzer.name)}-${safe(analyzer.version)}/observations.json")

    private fun atomicWrite(target: File, value: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.partial")
        temporary.writeText(value)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun safe(value: String) = value.lowercase().replace(Regex("[^a-z0-9._-]"), "-")
}
