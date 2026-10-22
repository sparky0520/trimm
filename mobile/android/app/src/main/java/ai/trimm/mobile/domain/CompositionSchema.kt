package ai.trimm.mobile.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CompositionSchema(
    var settings: CompositionSettings = CompositionSettings(),
    var metadata: CompositionMetadata = CompositionMetadata(),
    var timeline: List<TimelineNode> = emptyList()
)

@Serializable
data class CompositionSettings(
    var aspectRatio: String = "9:16",
    var backgroundColor: String = "#000000",
    var autoEnhance: Boolean = false
)

@Serializable
data class CompositionMetadata(
    val transcripts: List<TranscriptWordNode> = emptyList(),
    val silences: List<TimeRangeNode> = emptyList()
)

@Serializable
data class TranscriptWordNode(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

@Serializable
data class TimeRangeNode(
    val startMs: Long,
    val endMs: Long
)

@Serializable
sealed class TimelineNode {
    val type: String
        get() = when (this) {
            is Video -> "video"
            is Text -> "text"
            is Effect -> "effect"
            is Audio -> "audio"
        }

    @Serializable
    @SerialName("video")
    data class Video(
        val id: String = UUID.randomUUID().toString(),
        val src: String = "",
        val startMs: Long,
        val endMs: Long,
        val sourceStartMs: Long = 0L,
        val transition: String? = null
    ) : TimelineNode()

    @Serializable
    @SerialName("text")
    data class Text(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val style: TextNodeStyle? = null,
        val segments: List<TextSegment>? = null,
        val visible: Boolean? = null
    ) : TimelineNode() {
        val isVisible: Boolean get() = visible ?: true
    }

    @Serializable
    @SerialName("effect")
    data class Effect(
        val id: String = UUID.randomUUID().toString(),
        val effectName: String,
        val startMs: Long,
        val endMs: Long,
        val intensity: Float? = null
    ) : TimelineNode()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val id: String = UUID.randomUUID().toString(),
        val src: String = "",
        val startMs: Long,
        val endMs: Long,
        val autoDuck: Boolean? = null,
        val volume: Float? = null
    ) : TimelineNode()
}

typealias VideoNode = TimelineNode.Video
typealias TextNode = TimelineNode.Text
typealias EffectNode = TimelineNode.Effect
typealias AudioNode = TimelineNode.Audio

@Serializable
data class TextSegment(
    val text: String,
    val color: String? = null
)

@Serializable
data class TextNodeStyle(
    val color: String? = null,
    val fontSize: Float? = null,
    val posY: Float? = null,
    val posX: Float? = null,
    val scale: Float? = null,
    val strokeColor: String? = null,
    val strokeWidth: Float? = null,
    val backgroundColor: String? = null,
    val backgroundPadding: Float? = null,
    val backgroundCornerRadius: Float? = null
)

data class UndoTarget(
    val id: String,
    val userPrompt: String,
    val turnIndex: Int
)

data class UiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val turnId: String? = null
)
