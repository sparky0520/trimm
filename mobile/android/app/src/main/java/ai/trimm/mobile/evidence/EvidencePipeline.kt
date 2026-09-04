package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.SourceMedia
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class AnalyzerContext(val source: SourceMedia, val artifactDirectory: java.io.File)

interface EvidenceAnalyzer {
    val identity: AnalyzerIdentity
    suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation>
}

class UnsupportedEvidenceAnalyzer(
    override val identity: AnalyzerIdentity,
    private val reason: String
) : EvidenceAnalyzer {
    override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> =
        throw UnsupportedOperationException(reason)
}

data class AnalyzerResult(val observations: List<EvidenceObservation>, val run: AnalyzerRun)

class EvidencePipeline(
    private val cache: EvidenceCache,
    private val analyzers: List<EvidenceAnalyzer>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun analyze(source: SourceMedia): EvidenceGraph = coroutineScope {
        val results = analyzers.map { analyzer -> async {
            cache.readAnalyzer(source, analyzer.identity)?.let { cached ->
                val unique = cached.distinctBy(EvidenceObservation::id)
                return@async AnalyzerResult(unique, AnalyzerRun(analyzer.identity, AnalyzerStatus.CACHED, clock(), clock(), unique.size))
            }
            val started = clock()
            runCatching {
                val context = AnalyzerContext(source, cache.artifactDirectory(source, analyzer.identity))
                analyzer.analyze(context).distinctBy(EvidenceObservation::id).also { observations ->
                    require(observations.all { it.sourceFingerprint == source.fingerprint })
                    cache.writeAnalyzer(source, analyzer.identity, observations)
                }
            }.fold(
                onSuccess = { AnalyzerResult(it, AnalyzerRun(analyzer.identity, AnalyzerStatus.COMPLETE, started, clock(), it.size)) },
                onFailure = { AnalyzerResult(emptyList(), AnalyzerRun(analyzer.identity, if (it is UnsupportedOperationException) AnalyzerStatus.UNSUPPORTED else AnalyzerStatus.FAILED, started, clock(), 0, it.safeMessage())) }
            )
        } }.awaitAll()
        val graph = EvidenceGraph(
            sourceFingerprint = source.fingerprint,
            sourceDurationMs = source.durationMs,
            createdAtEpochMs = clock(),
            observations = results.flatMap(AnalyzerResult::observations).distinctBy(EvidenceObservation::id)
                .sortedWith(compareBy({ it.range.startMs }, { it.id })),
            analyzerRuns = results.map(AnalyzerResult::run),
            disclosure = EvidenceDisclosure(
                computedLocally = analyzers.filter { it.identity.execution == ExecutionLocation.LOCAL_DEVICE }.map { it.identity.name }.sorted(),
                sentExternally = analyzers.filter { it.identity.execution == ExecutionLocation.EXTERNAL_SERVICE }.map {
                    ExternalDisclosure(it.identity.name, "audio track with source timestamps", "speech transcription and speaker evidence")
                }
            )
        )
        cache.writeGraph(graph)
        graph
    }
}

private fun Throwable.safeMessage(): String = (message ?: this::class.simpleName ?: "Analyzer failed")
    .replace(Regex("""(?:content|file)://\S+"""), "[private media]")
    .take(240)
