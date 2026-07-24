package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.abs

class LocalVisualAnalyzer(private val context: android.content.Context) : EvidenceAnalyzer {
    override val identity = AnalyzerIdentity("ctrlB frame sampler", "1.0.0", ExecutionLocation.LOCAL_DEVICE)

    override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> {
        val source = context.source
        val sampleTimes = sampleTimes(source.durationMs)
        val retriever = MediaMetadataRetriever().apply { setDataSource(this@LocalVisualAnalyzer.context, Uri.parse(source.uri)) }
        try {
            val frames = sampleTimes.mapNotNull { at ->
                retriever.getFrameAtTime(at * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { bitmap ->
                    val range = pointRange(at, source.durationMs)
                    val id = stableEvidenceId(source.fingerprint, identity, "frame", range)
                    val file = File(context.artifactDirectory, "$id.jpg")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    FrameSample(at, range, id, file, bitmap, metrics(bitmap))
                }
            }
            if (frames.isEmpty()) error("No representative video frames could be decoded.")
            return buildList {
                frames.forEach { frame ->
                    val provenance = EvidenceProvenance(identity, source.fingerprint, listOf(frame.id))
                    add(FrameEvidence(frame.id, source.fingerprint, frame.range, .95f, provenance, frame.file.absolutePath, frame.bitmap.width, frame.bitmap.height))
                    add(visual(source.fingerprint, frame.range, VisualKind.EXPOSURE, frame.metrics.luma, .85f, provenance))
                    add(visual(source.fingerprint, frame.range, VisualKind.BLUR, frame.metrics.edge, .72f, provenance))
                    add(visual(source.fingerprint, frame.range, VisualKind.SALIENCE, frame.metrics.centerContrast, .65f, provenance))
                    add(visual(source.fingerprint, frame.range, VisualKind.CROP_SAFETY, frame.metrics.centerContrast, .55f, provenance))
                }
                frames.zipWithNext().forEach { (before, after) ->
                    val range = TimeRange(before.atMs, after.atMs)
                    val delta = abs(after.metrics.luma - before.metrics.luma) + abs(after.metrics.edge - before.metrics.edge)
                    add(visual(source.fingerprint, range, VisualKind.MOTION, delta.coerceIn(0f, 1f), .65f, EvidenceProvenance(identity, source.fingerprint, listOf(before.id, after.id))))
                    add(visual(source.fingerprint, range, VisualKind.SHAKE, (delta * .7f).coerceIn(0f, 1f), .45f, EvidenceProvenance(identity, source.fingerprint, listOf(before.id, after.id), "Frame-difference estimate; no gyroscope data.")))
                }
                val boundaries = frames.zipWithNext().filter { (a, b) -> abs(b.metrics.luma - a.metrics.luma) + abs(b.metrics.edge - a.metrics.edge) > .38f }.map { it.second.atMs }
                val starts = listOf(0L) + boundaries
                val ends = boundaries + source.durationMs
                starts.zip(ends).filter { it.second > it.first }.forEach { (start, end) ->
                    val range = TimeRange(start, end)
                    val representatives = frames.filter { it.atMs in start until end }.map { it.id }.ifEmpty { listOf(frames.minBy { abs(it.atMs - start) }.id) }
                    add(ShotEvidence(stableEvidenceId(source.fingerprint, identity, "shot", range), source.fingerprint, range, .62f, EvidenceProvenance(identity, source.fingerprint, representatives), representatives))
                }
            }.also { frames.forEach { it.bitmap.recycle() } }
        } finally { retriever.release() }
    }

    private fun visual(fingerprint: String, range: TimeRange, kind: VisualKind, value: Float, confidence: Float, provenance: EvidenceProvenance) =
        VisualEvidence(stableEvidenceId(fingerprint, identity, kind.name, range), fingerprint, range, confidence, provenance, kind, value)

    private fun sampleTimes(durationMs: Long): List<Long> {
        val interval = (durationMs / 12).coerceIn(500, 3_000)
        return generateSequence(0L) { it + interval }.takeWhile { it < durationMs }.toList().take(60)
    }

    private fun pointRange(at: Long, duration: Long) = TimeRange(at, minOf(duration, at + 1).coerceAtLeast(at + 1))

    private fun metrics(bitmap: Bitmap): FrameMetrics {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(1024).also { scaled.getPixels(it, 0, 32, 0, 0, 32, 32) }
        var lumaSum = 0f
        val lumas = FloatArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            val luma = ((color shr 16 and 255) * .2126f + (color shr 8 and 255) * .7152f + (color and 255) * .0722f) / 255f
            lumas[index] = luma; lumaSum += luma
        }
        var edge = 0f
        for (y in 1 until 32) for (x in 1 until 32) edge += abs(lumas[y * 32 + x] - lumas[y * 32 + x - 1]) + abs(lumas[y * 32 + x] - lumas[(y - 1) * 32 + x])
        val center = (10..21).flatMap { y -> (10..21).map { x -> lumas[y * 32 + x] } }.average().toFloat()
        val mean = lumaSum / pixels.size
        scaled.recycle()
        return FrameMetrics(mean, (edge / 1984).coerceIn(0f, 1f), abs(center - mean).coerceIn(0f, 1f))
    }

    private data class FrameMetrics(val luma: Float, val edge: Float, val centerContrast: Float)
    private data class FrameSample(val atMs: Long, val range: TimeRange, val id: String, val file: File, val bitmap: Bitmap, val metrics: FrameMetrics)
}
