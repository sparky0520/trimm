package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.domain.TranscriptWord
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

@Serializable private data class RemoteTranscriptWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)
@Serializable private data class RemoteTranscript(val id: String, val languageCode: String? = null, val words: List<RemoteTranscriptWord>)

class TranscriptionClient(private val endpoint: String, private val accessToken: suspend () -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    init { require(URI(endpoint).scheme == "https") { "Transcription endpoint must use HTTPS" } }

    suspend fun transcribe(context: Context, source: Uri): List<TranscriptWord> = withContext(Dispatchers.IO) {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15 * 60_000
            connection.doOutput = true
            connection.setChunkedStreamingMode(256 * 1024)
            connection.setRequestProperty("Content-Type", context.contentResolver.getType(source) ?: "application/octet-stream")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Could not open source media for transcription." }
                connection.outputStream.use { output -> input.copyTo(output, 256 * 1024) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }
                error("Transcription request failed ($status)${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            val transcript = connection.inputStream.bufferedReader().use { json.decodeFromString<RemoteTranscript>(it.readText()) }
            transcript.words.map { word -> TranscriptWord(word.text, TimeRange(word.startMs, word.endMs), word.confidence) }
        } finally { connection.disconnect() }
    }
}
