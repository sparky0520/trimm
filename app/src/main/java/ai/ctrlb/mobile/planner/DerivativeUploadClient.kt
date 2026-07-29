package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.media.PreparedDerivative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

class DerivativeUploadClient(
    agentRunsEndpoint: String,
    private val accessToken: suspend () -> String,
    private val projectId: String,
    private val deviceSessionId: String
) {
    private val derivativeRoot = URI(agentRunsEndpoint.substringBefore("/v1/agent/runs") + "/v1/derivatives/jobs/")

    suspend fun upload(derivative: PreparedDerivative): String = withContext(Dispatchers.IO) {
        val endpoint = derivativeRoot.resolve(derivative.jobId)
        val token = accessToken()
        var offset = head(endpoint, token)
        val file = java.io.File(derivative.localPath)
        require(offset in 0..file.length()) { "Cloud derivative offset is invalid." }
        file.inputStream().use { input ->
            input.skipNBytes(offset)
            val buffer = ByteArray(4 * 1024 * 1024)
            while (offset < file.length()) {
                val count = input.read(buffer)
                check(count > 0) { "Derivative upload ended early." }
                offset = put(endpoint, token, offset, buffer, count)
            }
        }
        complete(endpoint, token, derivative.sourceRange.durationMs)
        derivative.jobId
    }

    private fun head(endpoint: URI, token: String): Long {
        val connection = open(endpoint, token, "HEAD")
        return try {
            require(connection.responseCode in 200..299) { "Could not resume derivative upload." }
            connection.getHeaderField("upload-offset")?.toLongOrNull() ?: 0L
        } finally { connection.disconnect() }
    }

    private fun put(endpoint: URI, token: String, offset: Long, bytes: ByteArray, count: Int): Long {
        val connection = open(endpoint, token, "PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Upload-Offset", offset.toString())
            setFixedLengthStreamingMode(count)
        }
        return try {
            connection.outputStream.use { it.write(bytes, 0, count) }
            require(connection.responseCode in 200..299) { "Could not upload derivative bytes." }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            Regex(""""offset"\s*:\s*(\d+)""").find(response)?.groupValues?.get(1)?.toLong()
                ?: throw IllegalStateException("Derivative upload returned no offset.")
        } finally { connection.disconnect() }
    }

    private fun complete(endpoint: URI, token: String, durationMs: Long) {
        val connection = open(URI("${endpoint}/complete"), token, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write("""{"durationMs":$durationMs}""".toByteArray()) }
            require(connection.responseCode in 200..299) { "Could not complete derivative upload." }
        } finally { connection.disconnect() }
    }

    private fun open(endpoint: URI, token: String, method: String) =
        (endpoint.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-ctrlB-Project-Id", projectId)
            setRequestProperty("X-ctrlB-Device-Session-Id", deviceSessionId)
        }
}
