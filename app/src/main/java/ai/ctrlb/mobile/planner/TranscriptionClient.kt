package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.domain.TranscriptWord
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

@Serializable private data class RemoteTranscriptWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)
@Serializable private data class RemoteTranscript(val id: String, val languageCode: String? = null, val words: List<RemoteTranscriptWord>)
@Serializable private data class CreateUpload(val contentType: String, val size: Long)
@Serializable private data class UploadSession(val id: String, val offset: Long, val chunkSize: Int)
@Serializable private data class UploadOffset(val offset: Long)

class TranscriptionClient(private val endpoint: String, private val accessToken: suspend () -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val uploadsEndpoint = endpoint.trimEnd('/') + "/uploads"
    init { require(URI(endpoint).scheme == "https") { "Transcription endpoint must use HTTPS" } }

    suspend fun transcribe(
        context: Context,
        source: Uri,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): List<TranscriptWord> = withContext(Dispatchers.IO) {
        val size = contentLength(context, source)
        require(size > 0) { "The selected media size is unavailable." }
        val contentType = context.contentResolver.getType(source) ?: "application/octet-stream"
        val token = accessToken()
        val session = createSession(token, contentType, size)
        var completed = false
        try {
            var offset = session.offset
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Could not open source media for transcription." }
                skipFully(input, offset)
                val buffer = ByteArray(session.chunkSize.coerceIn(256 * 1024, 8 * 1024 * 1024))
                while (offset < size) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                    check(count > 0) { "Media ended before its declared size." }
                    val chunk = buffer.copyOf(count)
                    offset = uploadChunkWithRetry(session.id, token, offset, chunk)
                    onProgress(offset, size)
                }
            }
            val transcript = completeSession(session.id, token)
            completed = true
            transcript.words.map { word -> TranscriptWord(word.text, TimeRange(word.startMs, word.endMs), word.confidence) }
        } finally {
            if (!completed) runCatching { deleteSession(session.id, token) }
        }
    }

    private fun contentLength(context: Context, source: Uri): Long {
        context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1
    }

    private suspend fun createSession(token: String, contentType: String, size: Long): UploadSession {
        val connection = connection(uploadsEndpoint, "POST", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return connection.useResponse { outputStream.use { it.write(json.encodeToString(CreateUpload(contentType, size)).toByteArray()) }; decodeSuccess(this) }
    }

    private suspend fun uploadChunkWithRetry(id: String, token: String, initialOffset: Long, chunk: ByteArray): Long {
        var offset = initialOffset
        repeat(4) { attempt ->
            coroutineContext.ensureActive()
            try {
                val connection = connection("$uploadsEndpoint/$id", "PUT", token).apply {
                    doOutput = true
                    setFixedLengthStreamingMode(chunk.size)
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("Upload-Offset", offset.toString())
                }
                return connection.useResponse { outputStream.use { it.write(chunk) }; decodeSuccess<UploadOffset>(this).offset }
            } catch (failure: IOException) {
                if (attempt == 3) throw failure
                delay(500L * (attempt + 1))
                val remoteOffset = remoteOffset(id, token)
                if (remoteOffset == initialOffset + chunk.size) return remoteOffset
                if (remoteOffset != initialOffset) error("Server returned an unsafe upload offset.")
                offset = remoteOffset
            }
        }
        error("Upload retry limit reached.")
    }

    private fun remoteOffset(id: String, token: String): Long {
        val connection = connection("$uploadsEndpoint/$id", "HEAD", token)
        return connection.useResponse { requireSuccess(this); getHeaderField("Upload-Offset")?.toLongOrNull() ?: error("Missing upload offset.") }
    }

    private fun completeSession(id: String, token: String): RemoteTranscript {
        val connection = connection("$uploadsEndpoint/$id/complete", "POST", token).apply { doOutput = true; setFixedLengthStreamingMode(0) }
        return connection.useResponse { outputStream.close(); decodeSuccess(this) }
    }

    private fun deleteSession(id: String, token: String) {
        val connection = connection("$uploadsEndpoint/$id", "DELETE", token)
        connection.useResponse { if (responseCode !in 200..299 && responseCode != 404) requireSuccess(this) }
    }

    private fun connection(url: String, method: String, token: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = if (url.endsWith("/complete")) 15 * 60_000 else 60_000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Cache-Control", "no-store")
    }

    private inline fun <T> HttpURLConnection.useResponse(block: HttpURLConnection.() -> T): T = try { block() } finally { disconnect() }
    private inline fun <reified T> decodeSuccess(connection: HttpURLConnection): T {
        requireSuccess(connection)
        return connection.inputStream.bufferedReader().use { json.decodeFromString<T>(it.readText()) }
    }
    private fun requireSuccess(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }
            throw IOException("Transcription upload failed ($status)${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
        }
    }
    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) { val skipped = input.skip(remaining); if (skipped > 0) remaining -= skipped else { check(input.read() != -1) { "Media ended before upload offset." }; remaining-- } }
    }
}
