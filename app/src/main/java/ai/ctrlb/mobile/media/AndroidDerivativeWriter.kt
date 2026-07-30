package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.layer1.NormalizedRect
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidDerivativeWriter(private val context: Context) : DerivativeWriter {
    override suspend fun write(
        type: SelectiveDerivativeType,
        source: SourceMedia,
        range: TimeRange,
        crop: NormalizedRect?,
        acceptedFormat: String,
        destination: File
    ) {
        when (type) {
            SelectiveDerivativeType.FRAME -> writeFrame(source, range.startMs, null, acceptedFormat, destination)
            SelectiveDerivativeType.CROP -> writeFrame(source, range.startMs, requireNotNull(crop), acceptedFormat, destination)
            SelectiveDerivativeType.AUDIO -> {
                require(acceptedFormat == "audio/mp4") { "Android audio derivatives require audio/mp4." }
                transform(source, range, removeAudio = false, removeVideo = true, destination)
            }
            SelectiveDerivativeType.AUDIOVISUAL -> {
                require(acceptedFormat == "video/mp4") { "Android audiovisual derivatives require video/mp4." }
                transform(source, range, removeAudio = !source.hasAudio, removeVideo = false, destination)
            }
        }
    }

    private fun writeFrame(
        source: SourceMedia,
        atMs: Long,
        crop: NormalizedRect?,
        acceptedFormat: String,
        destination: File
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(source.uri))
            val frame = requireNotNull(
                retriever.getFrameAtTime(atMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
            ) { "No frame was available at the authorized timestamp." }
            val selected = crop?.let { rect ->
                val left = (rect.x * frame.width).toInt().coerceIn(0, frame.width - 1)
                val top = (rect.y * frame.height).toInt().coerceIn(0, frame.height - 1)
                val width = (rect.width * frame.width).toInt().coerceIn(1, frame.width - left)
                val height = (rect.height * frame.height).toInt().coerceIn(1, frame.height - top)
                Bitmap.createBitmap(frame, left, top, width, height)
            } ?: frame
            val format = when (acceptedFormat) {
                "image/jpeg" -> Bitmap.CompressFormat.JPEG
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> error("Unsupported image format.")
            }
            destination.outputStream().use { output ->
                check(selected.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 88, output)) {
                    "Frame compression failed."
                }
            }
            if (selected !== frame) selected.recycle()
            frame.recycle()
        } finally {
            retriever.release()
        }
    }

    private suspend fun transform(
        source: SourceMedia,
        range: TimeRange,
        removeAudio: Boolean,
        removeVideo: Boolean,
        destination: File
    ) = withContext(Dispatchers.Main.immediate) { suspendCancellableCoroutine { continuation ->
        val mediaItem = MediaItem.Builder()
            .setUri(source.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.startMs)
                    .setEndPositionMs(range.endMs)
                    .build()
            )
            .build()
        lateinit var transformer: Transformer
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                if (continuation.isActive) continuation.resumeWithException(exportException)
            }
        }
        transformer = Transformer.Builder(context).addListener(listener).build()
        continuation.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post {
                transformer.cancel()
                destination.delete()
            }
        }
        transformer.start(
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(removeAudio)
                .setRemoveVideo(removeVideo)
                .build(),
            destination.absolutePath
        )
    } }
}
