package ai.trimm.mobile.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Pair
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import org.json.JSONObject

@UnstableApi
class TransitionGraphicOverlay(
    private val preset: String,
    private val blueprintJson: String = "",
    private val startMs: Long,
    private val endMs: Long,
    private val videoWidth: Int = 1080,
    private val videoHeight: Int = 1920
) : BitmapOverlay() {

    private val emptyBitmap: Bitmap by lazy {
        val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        empty.eraseColor(Color.TRANSPARENT)
        empty
    }

    private val frameBitmap: Bitmap by lazy {
        Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        if (timeMs < startMs || timeMs > endMs) {
            return emptyBitmap
        }

        val progress = ((timeMs - startMs).toFloat() / maxOf(1L, endMs - startMs).toFloat()).coerceIn(0f, 1f)

        frameBitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(frameBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val normalizedPreset = preset.lowercase().replace("-", "_").trim()

        when (normalizedPreset) {
            "crossfade", "dip_to_black", "fade_in" -> {
                // Smooth fade from black (at transition start progress=0) to transparent (revealing incoming clip)
                val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }
            "dip_to_white" -> {
                // Flash / dip through white to transparent
                val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 255, 255, 255))
            }
            "wipe" -> {
                // Horizontal wipe: shutter covers from revealed width to edge
                val revealX = videoWidth * progress
                val shutterRect = RectF(revealX, 0f, videoWidth.toFloat(), videoHeight.toFloat())
                paint.color = Color.BLACK
                paint.style = Paint.Style.FILL
                canvas.drawRect(shutterRect, paint)

                // Subtle white wipe line along the leading edge
                if (progress > 0.01f && progress < 0.99f) {
                    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(180, 255, 255, 255)
                        strokeWidth = 4f
                        style = Paint.Style.STROKE
                    }
                    canvas.drawLine(revealX, 0f, revealX, videoHeight.toFloat(), linePaint)
                }
            }
            "slide" -> {
                // Slide in curtain: covers right side as it slides away to the right
                val revealX = videoWidth * progress
                val curtainRect = RectF(revealX, 0f, videoWidth.toFloat(), videoHeight.toFloat())
                paint.color = Color.BLACK
                paint.style = Paint.Style.FILL
                canvas.drawRect(curtainRect, paint)

                // Subtle slide shadow / gradient edge
                if (progress > 0.01f && progress < 0.99f) {
                    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(120, 0, 0, 0)
                        strokeWidth = 8f
                        style = Paint.Style.STROKE
                    }
                    canvas.drawLine(revealX, 0f, revealX, videoHeight.toFloat(), shadowPaint)
                }
            }
            "fade_out" -> {
                // Fade from transparent to black
                val alpha = (progress * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }
            "custom" -> {
                try {
                    val blueprint = JSONObject(blueprintJson)
                    val shapes = blueprint.optJSONArray("shapes")
                    if (shapes != null) {
                        for (i in 0 until shapes.length()) {
                            val shape = shapes.getJSONObject(i)
                            if (shape.optString("type") == "circle") {
                                val baseRadius = shape.optDouble("radius", 500.0).toFloat()
                                val animate = shape.optBoolean("animateScale", false)
                                val currentRadius = if (animate) baseRadius * progress else baseRadius
                                val color = shape.optString("color", "#000000")
                                paint.color = Color.parseColor(color)
                                paint.style = Paint.Style.FILL
                                canvas.drawCircle(videoWidth / 2f, videoHeight / 2f, currentRadius, paint)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed custom blueprints
                }
            }
            else -> {
                // Default fallback: smooth crossfade / fade from black
                val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }
        }

        return frameBitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        return object : OverlaySettings {
            override fun getBackgroundFrameAnchor() = Pair(0f, 0f)
            override fun getOverlayFrameAnchor() = Pair(0f, 0f)
            override fun getScale() = Pair(1f, 1f)
            override fun getRotationDegrees() = 0f
        }
    }
}
