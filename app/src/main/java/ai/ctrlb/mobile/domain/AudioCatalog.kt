package ai.ctrlb.mobile.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable enum class AudioKind { MUSIC, SOUND_EFFECT }
@Serializable data class AudioCatalogItem(
    val id: String,
    val kind: AudioKind,
    val assetPath: String,
    val durationMs: Long,
    val bpm: Float? = null,
    val energy: Float,
    val moods: Set<String>,
    val licenseId: String,
    val checksum: String,
    val downloadUrl: String? = null,
    val attribution: String? = null,
    val userImported: Boolean = false,
    val title: String = id,
    val artist: String = "",
    val previewUrl: String? = null,
    val sourceUrl: String? = null,
    val attributionRequired: Boolean = false
)
data class AudioMatch(val item: AudioCatalogItem, val score: Float, val reasons: List<String>)

object AudioMatcher {
    fun rank(items: List<AudioCatalogItem>, targetDurationMs: Long, style: VisualStyle, kind: AudioKind = AudioKind.MUSIC): List<AudioMatch> {
        val targetEnergy = when (style) { VisualStyle.PUNCHY -> .8f; VisualStyle.CLEAN -> .45f; VisualStyle.WARM -> .35f; VisualStyle.NATURAL -> .4f }
        val desiredMood = style.name.lowercase()
        return items.asSequence().filter { it.kind == kind && it.licenseId.isNotBlank() && it.checksum.isNotBlank() }.map { item ->
            val energyScore = 1f - abs(item.energy - targetEnergy).coerceAtMost(1f)
            val durationScore = if (item.durationMs >= targetDurationMs) 1f else (item.durationMs.toFloat() / targetDurationMs).coerceIn(.2f, 1f)
            val moodScore = if (desiredMood in item.moods) 1f else .55f
            AudioMatch(item, energyScore * .5f + durationScore * .3f + moodScore * .2f, listOf("energy", "duration", "mood"))
        }.sortedWith(compareByDescending<AudioMatch> { it.score }.thenBy { it.item.id }).toList()
    }
}
