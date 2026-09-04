package ai.trimm.mobile.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Pair
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import org.json.JSONObject

@UnstableApi
class TransitionGraphicOverlay(
    private val preset: String,
    private val blueprintJson: String,
    private val startMs: Long,
    private val endMs: Long
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        if (timeMs < startMs || timeMs > endMs) {
            val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            empty.eraseColor(Color.TRANSPARENT)
            return empty
        }

        val progress = (timeMs - startMs).toFloat() / maxOf(1L, endMs - startMs).toFloat()

        val width = 1000
        val height = 1000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (preset) {
            "fade-in" -> {
                // Fade from black to transparent
                val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }
            "fade-out", "dip-to-black" -> {
                // Fade from transparent to black
                val alpha = (progress * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 0, 0, 0))
            }
            "dip-to-white" -> {
                // Fade from transparent to white
                val alpha = (progress * 255).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(alpha, 255, 255, 255))
            }
            "custom" -> {
                // Basic custom implementation drawing a circle based on blueprint
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
                                canvas.drawCircle(width / 2f, height / 2f, currentRadius, paint)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed custom blueprints
                }
            }
        }

        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        return object : OverlaySettings {
            override fun getBackgroundFrameAnchor() = Pair(0f, 0f)
            override fun getOverlayFrameAnchor() = Pair(0f, 0f)
            override fun getScale() = Pair(2f, 2f) // Scale to cover screen
            override fun getRotationDegrees() = 0f
        }
    }
}
