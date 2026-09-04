package ai.trimm.mobile.media

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

enum class CapabilityTier {
    UNSUPPORTED,
    MINIMUM_SUPPORTED,
    STANDARD_EDITING,
    ADVANCED_LOCAL_PERCEPTION,
    HIGH_RESOLUTION_EXPORT
}

enum class CapabilityMode { LOCAL, LAYER_2, REDUCED_RESOLUTION, UNAVAILABLE }
enum class ThermalClass { NOMINAL, MODERATE, SEVERE, CRITICAL }

data class CodecWorkload(
    val mime: String,
    val canDecode720p30: Boolean = false,
    val canDecode1080p30: Boolean = false,
    val canEncode1080p30: Boolean = false,
    val canEncode4k30: Boolean = false,
    val hardwareAccelerated: Boolean = false,
    val encoderNames: List<String> = emptyList(),
    val widthAlignment: Int = 1,
    val heightAlignment: Int = 1,
    val usedAligned1080Probe: Boolean = false
)

data class DeviceWorkloadProfile(
    val apiLevel: Int,
    val abis: Set<String>,
    val memoryClassMb: Int,
    val freeStorageBytes: Long,
    val codecs: List<CodecWorkload>,
    val thermalClass: ThermalClass,
    val layer1ModelLatencyMs: Long?
)

data class FeatureCapability(
    val mode: CapabilityMode,
    val explanation: String
)

data class DeviceCapabilityReport(
    val tier: CapabilityTier,
    val features: Map<String, FeatureCapability>,
    val measurements: DeviceWorkloadProfile,
    // Thresholds remain provisional until the physical-device matrix in #7.
    val provisional: Boolean = true
) {
    fun diagnosticSummary(): String = buildString {
        append(userFacingSummary())
        append(
            " Measurements: api=${measurements.apiLevel}, " +
                "abis=${measurements.abis}, ram=${measurements.memoryClassMb}MB, " +
                "freeStorage=${measurements.freeStorageBytes}B, " +
                "thermal=${measurements.thermalClass}, " +
                "layer1Latency=${measurements.layer1ModelLatencyMs}ms, codecs=",
        )
        append(
            measurements.codecs.joinToString(prefix = "[", postfix = "]") { codec ->
                "${codec.mime}(" +
                    "decode720p=${codec.canDecode720p30}," +
                    "decode1080p=${codec.canDecode1080p30}," +
                    "encode1080p=${codec.canEncode1080p30}," +
                    "encode4k=${codec.canEncode4k30}," +
                    "hardware=${codec.hardwareAccelerated}," +
                    "alignment=${codec.widthAlignment}x${codec.heightAlignment}," +
                    "aligned1080Probe=${codec.usedAligned1080Probe}," +
                    "encoders=${codec.encoderNames})"
            },
        )
    }

    fun userFacingSummary(): String {
        val headline = when (tier) {
            CapabilityTier.UNSUPPORTED -> "This device does not meet the minimum editing requirements."
            CapabilityTier.MINIMUM_SUPPORTED -> "This device is ready for lighter local edits."
            CapabilityTier.STANDARD_EDITING -> "This device is ready for standard local editing."
            CapabilityTier.ADVANCED_LOCAL_PERCEPTION -> "This device supports advanced on-device analysis."
            CapabilityTier.HIGH_RESOLUTION_EXPORT -> "This device supports high-resolution local editing and export."
        }
        val adjustments = features.values
            .filter { it.mode != CapabilityMode.LOCAL }
            .map { it.explanation.trim() }
            .filter(String::isNotEmpty)
            .distinct()
        return if (adjustments.isEmpty()) headline
        else "$headline trimm will adapt automatically: ${adjustments.joinToString(" ")}"
    }
}

data class ExportCapability(
    val allow4k: Boolean,
    val recommendedWidth: Int,
    val recommendedHeight: Int,
    val reasons: List<String>,
    val tier: CapabilityTier = CapabilityTier.MINIMUM_SUPPORTED
)

object DeviceCapability {
    const val GIB: Long = 1024L * 1024L * 1024L
    const val LAYER_1_LATENCY_BUDGET_MS = 2_000L
    const val HIGH_RESOLUTION_MEMORY_MB = 7 * 1024

    fun assess(profile: DeviceWorkloadProfile): DeviceCapabilityReport {
        val avc = profile.codecs.firstOrNull { it.mime == MediaFormat.MIMETYPE_VIDEO_AVC }
        val hevc = profile.codecs.firstOrNull { it.mime == MediaFormat.MIMETYPE_VIDEO_HEVC }
        val has64BitAbi = profile.abis.any { it == "arm64-v8a" || it == "x86_64" }
        val minimumFailures = buildList {
            if (profile.apiLevel < 31) add("Android 12 or newer is required.")
            if (!has64BitAbi) add("A 64-bit Android ABI is required.")
            if (profile.memoryClassMb < 3 * 1024) add("At least 3 GB of device RAM is required.")
            if (profile.freeStorageBytes < GIB) add("At least 1 GB of free storage is required.")
            if (avc?.canDecode720p30 != true) add("720p30 H.264 decoding is required.")
        }
        if (minimumFailures.isNotEmpty()) {
            val explanation = minimumFailures.joinToString(" ")
            return DeviceCapabilityReport(
                CapabilityTier.UNSUPPORTED,
                mapOf(
                    "standardEditing" to FeatureCapability(CapabilityMode.UNAVAILABLE, explanation),
                    "localPerception" to FeatureCapability(CapabilityMode.UNAVAILABLE, explanation),
                    "highResolutionExport" to FeatureCapability(CapabilityMode.UNAVAILABLE, explanation)
                ),
                profile
            )
        }

        val standardReasons = buildList {
            if (profile.memoryClassMb < 4 * 1024) add("4 GB of device RAM is needed for standard 1080p editing.")
            if (profile.freeStorageBytes < 2 * GIB) add("2 GB free storage is needed for standard editing.")
            if (avc?.canDecode1080p30 != true) add("The device cannot decode H.264 1080p30.")
            if (avc?.canEncode1080p30 != true && avc?.canEncode4k30 != true) {
                add("The device cannot encode H.264 1080p30.")
            }
            if (profile.thermalClass >= ThermalClass.SEVERE) add("The device is currently too warm for sustained 1080p editing.")
        }
        val standard = standardReasons.isEmpty()
        val perceptionReasons = buildList {
            if (!standard) addAll(standardReasons)
            if (profile.memoryClassMb < 6 * 1024) add("6 GB of device RAM is needed for advanced local perception.")
            if (profile.freeStorageBytes < 4 * GIB) add("4 GB free storage is needed for local perception caches.")
            when {
                profile.layer1ModelLatencyMs == null -> add("Layer 1 model latency has not been measured on this device.")
                profile.layer1ModelLatencyMs > LAYER_1_LATENCY_BUDGET_MS ->
                    add("Measured Layer 1 latency exceeds the ${LAYER_1_LATENCY_BUDGET_MS} ms budget.")
            }
            if (profile.thermalClass > ThermalClass.MODERATE) add("Thermal state is too high for sustained local perception.")
        }
        val advanced = perceptionReasons.isEmpty()
        val highResolutionReasons = buildList {
            if (!standard) addAll(standardReasons)
            if (profile.memoryClassMb < HIGH_RESOLUTION_MEMORY_MB) {
                add("An 8 GB-class device is needed for high-resolution export.")
            }
            if (profile.freeStorageBytes < 8 * GIB) add("8 GB free storage is needed for safe high-resolution export.")
            if (listOfNotNull(avc, hevc).none { it.canEncode4k30 && it.hardwareAccelerated }) {
                add("No hardware H.264/HEVC 4K30 encoder is available.")
            }
            if (profile.thermalClass > ThermalClass.MODERATE) add("Thermal state is too high for sustained 4K export.")
        }
        val highResolution = highResolutionReasons.isEmpty()
        val tier = when {
            highResolution -> CapabilityTier.HIGH_RESOLUTION_EXPORT
            advanced -> CapabilityTier.ADVANCED_LOCAL_PERCEPTION
            standard -> CapabilityTier.STANDARD_EDITING
            else -> CapabilityTier.MINIMUM_SUPPORTED
        }
        return DeviceCapabilityReport(
            tier,
            mapOf(
                "standardEditing" to if (standard) {
                    FeatureCapability(CapabilityMode.LOCAL, "1080p preview and export run locally.")
                } else {
                    FeatureCapability(CapabilityMode.REDUCED_RESOLUTION, "${standardReasons.joinToString(" ")} Editing is limited to 720p.")
                },
                "localPerception" to if (advanced) {
                    FeatureCapability(CapabilityMode.LOCAL, "Advanced Layer 1 perception runs locally.")
                } else {
                    FeatureCapability(CapabilityMode.LAYER_2, "${perceptionReasons.joinToString(" ")} Advanced perception uses Layer 2.")
                },
                "highResolutionExport" to if (highResolution) {
                    FeatureCapability(CapabilityMode.LOCAL, "Hardware-accelerated 4K export is available.")
                } else {
                    FeatureCapability(CapabilityMode.REDUCED_RESOLUTION, "${highResolutionReasons.joinToString(" ")} Export is limited to 1080p or 720p.")
                }
            ),
            profile
        )
    }

    fun report(context: Context, layer1ModelLatencyMs: Long? = null): DeviceCapabilityReport =
        assess(measure(context, layer1ModelLatencyMs))

    fun evaluate(
        context: Context,
        requestedWidth: Int = 3840,
        requestedHeight: Int = 2160,
        estimatedOutputBytes: Long = 0
    ): ExportCapability {
        val measured = measure(context, null)
        val report = assess(measured)
        val requests4k = maxOf(requestedWidth, requestedHeight) > 1920 ||
            minOf(requestedWidth, requestedHeight) > 1080
        val highResolution = report.features.getValue("highResolutionExport")
        val hasSafeExportStorage = estimatedOutputBytes <= 0 || measured.freeStorageBytes >= estimatedOutputBytes * 2
        val allow4k = requests4k && highResolution.mode == CapabilityMode.LOCAL && hasSafeExportStorage
        val standard = report.features.getValue("standardEditing")
        val fallback = if (standard.mode == CapabilityMode.LOCAL) 1920 to 1080 else 1280 to 720
        val reasons = buildList {
            if (report.tier == CapabilityTier.UNSUPPORTED) add(standard.explanation)
            else if (requests4k && highResolution.mode != CapabilityMode.LOCAL) add(highResolution.explanation)
            if (!hasSafeExportStorage) {
                add("Not enough free storage for a safe export.")
            }
        }
        return when {
            !requests4k -> ExportCapability(false, requestedWidth, requestedHeight, reasons, report.tier)
            allow4k -> ExportCapability(true, requestedWidth, requestedHeight, reasons, report.tier)
            else -> ExportCapability(false, fallback.first, fallback.second, reasons, report.tier)
        }
    }

    private fun measure(context: Context, layer1ModelLatencyMs: Long?): DeviceWorkloadProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val support = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC).map { mime ->
            val matching = codecs.filter { mime in it.supportedTypes }
            val encoders = matching.filter(MediaCodecInfo::isEncoder)
            fun aligned(value: Int, alignment: Int): Int =
                ((value + alignment - 1) / alignment) * alignment
            fun supports(info: MediaCodecInfo, width: Int, height: Int, fps: Double): Pair<Boolean, Boolean> =
                runCatching {
                    val capabilities = info.getCapabilitiesForType(mime).videoCapabilities
                        ?: return@runCatching false to false
                    val exact = capabilities.areSizeAndRateSupported(width, height, fps)
                    val alignedWidth = aligned(width, capabilities.widthAlignment)
                    val alignedHeight = aligned(height, capabilities.heightAlignment)
                    val alignedSupport = !exact &&
                        capabilities.areSizeAndRateSupported(alignedWidth, alignedHeight, fps)
                    (exact || alignedSupport) to alignedSupport
                }.getOrDefault(false to false)
            fun supportsAny(encoder: Boolean, width: Int, height: Int, fps: Double): Boolean =
                matching.filter { it.isEncoder == encoder }.any { supports(it, width, height, fps).first }
            val aligned1080 = encoders.any { supports(it, 1920, 1080, 30.0).second }
            val hardwareEncoders = encoders.filter(::isHardwareAccelerated)
            val alignments = encoders.mapNotNull { info ->
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities
                }.getOrNull()
            }
            val hardware4k = hardwareEncoders.any {
                supports(it, 3840, 2160, 30.0).first
            }
            CodecWorkload(
                mime = mime,
                canDecode720p30 = supportsAny(false, 1280, 720, 30.0),
                canDecode1080p30 = supportsAny(false, 1920, 1080, 30.0),
                canEncode1080p30 = encoders.any { supports(it, 1920, 1080, 30.0).first } || hardware4k,
                canEncode4k30 = hardware4k,
                hardwareAccelerated = hardwareEncoders.isNotEmpty(),
                encoderNames = encoders.map { it.name },
                widthAlignment = alignments.maxOfOrNull { it.widthAlignment } ?: 1,
                heightAlignment = alignments.maxOfOrNull { it.heightAlignment } ?: 1,
                usedAligned1080Probe = aligned1080
            )
        }
        val thermal = when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
            in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_LIGHT -> ThermalClass.NOMINAL
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalClass.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalClass.SEVERE
            else -> ThermalClass.CRITICAL
        }
        return DeviceWorkloadProfile(
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toSet(),
            memoryClassMb = (memoryInfo.totalMem / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            freeStorageBytes = StatFs(context.filesDir.absolutePath).availableBytes,
            codecs = support,
            thermalClass = thermal,
            layer1ModelLatencyMs = layer1ModelLatencyMs
        )
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
}
