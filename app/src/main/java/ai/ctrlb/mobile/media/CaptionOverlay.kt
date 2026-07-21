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
class CaptionOverlay(private val cues: List<CaptionCue>) : TextOverlay() {
    private val empty = SpannableString("")
    override fun getText(presentationTimeUs: Long): SpannableString {
        val timeMs = presentationTimeUs / 1_000
        val cue = cues.firstOrNull { timeMs >= it.range.startMs && timeMs < it.range.endMs } ?: return empty
        return SpannableString(cue.text).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(Color.argb(210, 12, 12, 14)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {
        override fun getBackgroundFrameAnchor() = Pair(0f, -0.72f)
        override fun getOverlayFrameAnchor() = Pair(0f, -1f)
        override fun getScale() = Pair(.72f, .72f)
    }
}
