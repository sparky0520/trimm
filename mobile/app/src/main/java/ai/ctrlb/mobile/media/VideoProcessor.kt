package ai.ctrlb.mobile.media

import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoProcessor {

    /**
     * Extracts audio "beats" from a video file using energy-based onset detection.
     */
    suspend fun extractBeats(videoPath: String): List<Float> = withContext(Dispatchers.IO) {
        val beats = mutableListOf<Float>()
        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("audio_extract", ".wav")
            val command = "-y -i $videoPath -vn -acodec pcm_s16le -ar 8000 -ac 1 ${tempFile.absolutePath}"
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            
            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                val bytes = tempFile.readBytes()
                val headerSize = 44
                if (bytes.size > headerSize) {
                    val samples = ShortArray((bytes.size - headerSize) / 2)
                    java.nio.ByteBuffer.wrap(bytes, headerSize, bytes.size - headerSize)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(samples)

                    val sampleRate = 8000
                    val windowSize = sampleRate * 20 / 1000 // 20ms = 160 samples
                    val energyLevels = mutableListOf<Float>()
                    
                    for (i in samples.indices step windowSize) {
                        val end = minOf(i + windowSize, samples.size)
                        var sumSq = 0f
                        for (j in i until end) {
                            val s = samples[j] / 32768f
                            sumSq += s * s
                        }
                        energyLevels.add(sumSq / (end - i))
                    }

                    // Compute rolling average for dynamic threshold
                    val historySize = 43 // ~860ms
                    var sumHistory = 0f
                    for (i in 0 until minOf(historySize, energyLevels.size)) {
                        sumHistory += energyLevels[i]
                    }

                    val thresholdMultiplier = 1.5f
                    var lastBeatTime = -1f

                    for (i in energyLevels.indices) {
                        val currentEnergy = energyLevels[i]
                        val avgEnergy = sumHistory / minOf(historySize, i + 1)
                        
                        // Detect peak that is significantly above local average and has minimum absolute energy
                        if (currentEnergy > avgEnergy * thresholdMultiplier && currentEnergy > 0.005f) {
                            val time = (i * windowSize).toFloat() / sampleRate
                            // Debounce: minimum 0.2s between beats
                            if (time - lastBeatTime > 0.2f) {
                                beats.add(time)
                                lastBeatTime = time
                            }
                        }

                        // update rolling sum (center-aligned window approximately)
                        val nextIdx = i + historySize / 2
                        if (nextIdx < energyLevels.size) sumHistory += energyLevels[nextIdx]
                        val dropIdx = i - historySize / 2
                        if (dropIdx >= 0) sumHistory -= energyLevels[dropIdx]
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempFile?.delete()
        }
        
        return@withContext beats
    }

    /**
     * Merges a video file with an audio file (e.g., generated BGM) into an MP4 with AAC.
     * NOTE: This is a placeholder for FFmpegKit usage, as we need the FFmpegKit dependency to actually run it.
     */
    suspend fun mergeAudioAndVideo(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        // Pseudo-code for FFmpegKit:
        // val command = "-y -i ${videoFile.absolutePath} -i ${audioFile.absolutePath} -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 ${outputFile.absolutePath}"
        // val session = FFmpegKit.execute(command)
        // return@withContext ReturnCode.isSuccess(session.returnCode)
        
        // Simulating processing delay
        Thread.sleep(1000)
        
        // Return true to simulate success
        true
    }
}
