package ai.ctrlb.mobile.media

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {
    private val avc = CodecWorkload(
        mime = MediaFormat.MIMETYPE_VIDEO_AVC,
        canDecode720p30 = true,
        canDecode1080p30 = true,
        canEncode1080p30 = true,
        canEncode4k30 = true,
        hardwareAccelerated = true
    )

    private fun profile(
        api: Int = 37,
        abis: Set<String> = setOf("arm64-v8a"),
        memoryMb: Int = 8 * 1024,
        storageGiB: Long = 12,
        codecs: List<CodecWorkload> = listOf(avc),
        thermal: ThermalClass = ThermalClass.NOMINAL,
        latencyMs: Long? = 900
    ) = DeviceWorkloadProfile(api, abis, memoryMb, storageGiB * DeviceCapability.GIB, codecs, thermal, latencyMs)

    @Test fun `minimum baseline rejects incompatible devices with user-facing reasons`() {
        val report = DeviceCapability.assess(profile(api = 30, abis = setOf("armeabi-v7a"), memoryMb = 2048, storageGiB = 0, codecs = emptyList()))
        assertEquals(CapabilityTier.UNSUPPORTED, report.tier)
        val reason = report.features.getValue("standardEditing").explanation
        assertTrue(reason.contains("Android 12"))
        assertTrue(reason.contains("64-bit"))
        assertTrue(reason.contains("3 GB"))
        assertTrue(reason.contains("1 GB"))
        assertTrue(reason.contains("720p30"))
    }

    @Test fun `minimum tier reduces editing and routes advanced perception to Layer 2`() {
        val limitedAvc = avc.copy(canDecode1080p30 = false, canEncode1080p30 = false, canEncode4k30 = false)
        val report = DeviceCapability.assess(profile(memoryMb = 3 * 1024, storageGiB = 1, codecs = listOf(limitedAvc), latencyMs = null))
        assertEquals(CapabilityTier.MINIMUM_SUPPORTED, report.tier)
        assertEquals(CapabilityMode.REDUCED_RESOLUTION, report.features.getValue("standardEditing").mode)
        assertEquals(CapabilityMode.LAYER_2, report.features.getValue("localPerception").mode)
        assertEquals(CapabilityMode.REDUCED_RESOLUTION, report.features.getValue("highResolutionExport").mode)
    }

    @Test fun `standard editing does not require an NPU or measured model latency`() {
        val report = DeviceCapability.assess(profile(memoryMb = 4 * 1024, storageGiB = 3, latencyMs = null))
        assertEquals(CapabilityTier.STANDARD_EDITING, report.tier)
        assertEquals(CapabilityMode.LOCAL, report.features.getValue("standardEditing").mode)
        assertEquals(CapabilityMode.LAYER_2, report.features.getValue("localPerception").mode)
        assertTrue(report.features.getValue("localPerception").explanation.contains("not been measured"))
    }

    @Test fun `hardware 4k support implies lower resolution encoding when vendor 1080 report is non-monotonic`() {
        val nonMonotonic = avc.copy(canEncode1080p30 = false, canEncode4k30 = true)
        val report = DeviceCapability.assess(
            profile(memoryMb = 7_485, storageGiB = 12, codecs = listOf(nonMonotonic), latencyMs = null)
        )

        assertEquals(CapabilityTier.HIGH_RESOLUTION_EXPORT, report.tier)
        assertEquals(CapabilityMode.LOCAL, report.features.getValue("standardEditing").mode)
        assertEquals(CapabilityMode.LOCAL, report.features.getValue("highResolutionExport").mode)
    }

    @Test fun `advanced tier is selected from measured Layer 1 latency`() {
        val report = DeviceCapability.assess(profile(memoryMb = 6 * 1024, storageGiB = 6, codecs = listOf(avc.copy(canEncode4k30 = false)), latencyMs = 1_500))
        assertEquals(CapabilityTier.ADVANCED_LOCAL_PERCEPTION, report.tier)
        assertEquals(CapabilityMode.LOCAL, report.features.getValue("localPerception").mode)
        assertEquals(CapabilityMode.REDUCED_RESOLUTION, report.features.getValue("highResolutionExport").mode)
    }

    @Test fun `high-resolution tier requires hardware encoding storage memory and sustainable thermals`() {
        val ready = DeviceCapability.assess(profile())
        assertEquals(CapabilityTier.HIGH_RESOLUTION_EXPORT, ready.tier)
        assertEquals(CapabilityMode.LOCAL, ready.features.getValue("highResolutionExport").mode)

        val warm = DeviceCapability.assess(profile(thermal = ThermalClass.SEVERE))
        assertFalse(warm.tier == CapabilityTier.HIGH_RESOLUTION_EXPORT)
        assertTrue(warm.features.getValue("highResolutionExport").explanation.contains("Thermal"))
        assertTrue(warm.provisional)

        val sixGb = DeviceCapability.assess(profile(memoryMb = 6 * 1024))
        assertFalse(sixGb.tier == CapabilityTier.HIGH_RESOLUTION_EXPORT)
        assertTrue(sixGb.features.getValue("highResolutionExport").explanation.contains("8 GB-class"))
    }
}
