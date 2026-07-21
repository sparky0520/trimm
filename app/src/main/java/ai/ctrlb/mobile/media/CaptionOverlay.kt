package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.CaptionCue
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Pair
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextOverlay

@UnstableApi
class CaptionOverlay(
    private val cues: List<CaptionCue>,
    private val portrait: Boolean
) : TextOverlay() {
    // TextOverlay renders text into a bitmap and Bitmap.createBitmap rejects a
    // zero-sized result. Keep a transparent, non-zero-width placeholder between
    // cues instead of returning an empty SpannableString.
    private val empty = SpannableString("\u00A0").apply {
        setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    override fun getText(presentationTimeUs: Long): SpannableString {
        val timeMs = presentationTimeUs / 1_000
        val cue = cues.firstOrNull { timeMs >= it.range.startMs && timeMs < it.range.endMs } ?: return empty
        return SpannableString(wrap(cue.text, if (portrait) 20 else 34)).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(Color.argb(210, 12, 12, 14)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        override fun getBackgroundFrameAnchor() = Pair(0f, -0.72f)
        override fun getOverlayFrameAnchor() = Pair(0f, -1f)
        override fun getScale() = if (portrait) Pair(.48f, .48f) else Pair(.6f, .6f)
    }

    private fun wrap(text: String, maxCharacters: Int): String {
        val lines = mutableListOf<String>()
        var current = ""
        text.trim().split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && candidate.length > maxCharacters) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.joinToString("\n")
    }
}
