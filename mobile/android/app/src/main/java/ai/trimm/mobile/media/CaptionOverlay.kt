package ai.trimm.mobile.media

import ai.trimm.mobile.domain.CaptionCue
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
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
        val wrappedText = wrap(cue.text, if (portrait) 20 else 34)
        return SpannableString(wrappedText).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(Color.argb(210, 12, 12, 14)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            val activeWord = cue.words.firstOrNull { timeMs >= it.range.startMs && timeMs < it.range.endMs }
            if (activeWord != null) {
                var currentIndex = 0
                for (word in cue.words) {
                    val wordText = word.text.trim()
                    val startIndex = wrappedText.indexOf(wordText, currentIndex)
                    if (startIndex != -1) {
                        if (word == activeWord) {
                            setSpan(ForegroundColorSpan(Color.YELLOW), startIndex, startIndex + wordText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            break
                        }
                        currentIndex = startIndex + wordText.length
                    }
                }
            }
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        override fun getBackgroundFrameAnchor() = Pair(0f, -0.72f)
        override fun getOverlayFrameAnchor() = Pair(0f, -1f)
        override fun getScale() = if (portrait) Pair(.7f, .7f) else Pair(.4f, .4f)
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
