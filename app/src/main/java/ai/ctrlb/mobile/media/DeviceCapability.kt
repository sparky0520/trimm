package ai.ctrlb.mobile.media

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

data class ExportCapability(val allow4k: Boolean, val recommendedWidth: Int, val recommendedHeight: Int, val reasons: List<String>)

object DeviceCapability {
    fun evaluate(context: Context, estimatedOutputBytes: Long = 0): ExportCapability {
        val reasons = mutableListOf<String>()
        val memoryGb = (context.getSystemService(ActivityManager::class.java).memoryClass / 1024f)
        if (Build.VERSION.SDK_INT < 31) reasons += "Android 12 or newer is required"
        if (memoryGb < 8f) reasons += "8 GB RAM is required for guaranteed 4K export"
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val has4kEncoder = codecs.any { info -> info.isEncoder && info.supportedTypes.any { type ->
            (type == MediaFormat.MIMETYPE_VIDEO_AVC || type == MediaFormat.MIMETYPE_VIDEO_HEVC) && runCatching { info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(3840, 2160) == true }.getOrDefault(false)
        } }
        if (!has4kEncoder) reasons += "No hardware 4K H.264/HEVC encoder"
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (estimatedOutputBytes > 0 && available < estimatedOutputBytes * 2) reasons += "Not enough free storage for safe export"
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) reasons += "Device is too warm for 4K export"
        return if (reasons.isEmpty()) ExportCapability(true, 3840, 2160, emptyList()) else ExportCapability(false, 1920, 1080, reasons)
    }
}
