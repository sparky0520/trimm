package ai.ctrlb.mobile.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Pair
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

@UnstableApi
class TextGraphicOverlay(
    private val text: String,
    private val blueprintJson: String,
    private val startMs: Long,
    private val endMs: Long
) : BitmapOverlay() {

    private val cachedBitmap: Bitmap by lazy { generateBitmap() }
    
    private var posX: Float = 0f
    private var posY: Float = 0f
    private var configScale: Float = 1f
    private var rotation: Float = 0f

    private fun parseColor(colorString: String?, defaultColor: Int): Int {
        if (colorString.isNullOrEmpty()) return defaultColor
        return try { Color.parseColor(colorString) } catch (e: Exception) { defaultColor }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        if (timeMs < startMs || timeMs > endMs) {
            val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            empty.eraseColor(Color.TRANSPARENT)
            return empty
        }
        return cachedBitmap
    }

    private fun generateBitmap(): Bitmap {
        val width = 1000
        val height = 1000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val cy = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        try {
            val blueprint = JSONObject(blueprintJson)
            val layout = blueprint.optJSONObject("layout")
            if (layout != null) {
                posX = layout.optDouble("posX", 0.0).toFloat()
                posY = layout.optDouble("posY", 0.0).toFloat()
                configScale = layout.optDouble("scale", 1.0).toFloat()
                rotation = layout.optDouble("rotation", 0.0).toFloat()
            }

            // Draw Background Shapes
            val shapes = blueprint.optJSONArray("backgroundShapes")
            if (shapes != null) {
                for (i in 0 until shapes.length()) {
                    val shape = shapes.getJSONObject(i)
                    val type = shape.optString("type")
                    val fillColor = parseColor(shape.optString("color"), Color.TRANSPARENT)
                    val strokeColor = parseColor(shape.optString("strokeColor"), Color.TRANSPARENT)
                    val strokeWidth = shape.optDouble("strokeWidth", 0.0).toFloat()

                    if (type == "starburst") {
                        val points = shape.optInt("points", 16)
                        val innerRadius = shape.optDouble("innerRadius", 200.0).toFloat()
                        val outerRadius = shape.optDouble("outerRadius", 400.0).toFloat()
                        
                        val path = Path()
                        for (p in 0 until points * 2) {
                            val radius = if (p % 2 == 0) outerRadius else innerRadius
                            val angle = (p * Math.PI / points).toFloat()
                            val px = cx + radius * cos(angle)
                            val py = cy + radius * sin(angle)
                            if (p == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()

                        if (fillColor != Color.TRANSPARENT) {
                            paint.style = Paint.Style.FILL
                            paint.color = fillColor
                            canvas.drawPath(path, paint)
                        }
                        if (strokeColor != Color.TRANSPARENT && strokeWidth > 0) {
                            paint.style = Paint.Style.STROKE
                            paint.color = strokeColor
                            paint.strokeWidth = strokeWidth
                            canvas.drawPath(path, paint)
                        }
                    } else if (type == "rectangle") {
                        val rw = shape.optDouble("width", 500.0).toFloat()
                        val rh = shape.optDouble("height", 200.0).toFloat()
                        val corner = shape.optDouble("cornerRadius", 0.0).toFloat()
                        
                        if (fillColor != Color.TRANSPARENT) {
                            paint.style = Paint.Style.FILL
                            paint.color = fillColor
                            canvas.drawRoundRect(cx - rw/2, cy - rh/2, cx + rw/2, cy + rh/2, corner, corner, paint)
                        }
                        if (strokeColor != Color.TRANSPARENT && strokeWidth > 0) {
                            paint.style = Paint.Style.STROKE
                            paint.color = strokeColor
                            paint.strokeWidth = strokeWidth
                            canvas.drawRoundRect(cx - rw/2, cy - rh/2, cx + rw/2, cy + rh/2, corner, corner, paint)
                        }
                    } else if (type == "circle") {
                        val radius = shape.optDouble("outerRadius", 300.0).toFloat()
                        if (fillColor != Color.TRANSPARENT) {
                            paint.style = Paint.Style.FILL
                            paint.color = fillColor
                            canvas.drawCircle(cx, cy, radius, paint)
                        }
                        if (strokeColor != Color.TRANSPARENT && strokeWidth > 0) {
                            paint.style = Paint.Style.STROKE
                            paint.color = strokeColor
                            paint.strokeWidth = strokeWidth
                            canvas.drawCircle(cx, cy, radius, paint)
                        }
                    }
                }
            }

            // Draw Text
            val textStyle = blueprint.optJSONObject("textStyle")
            val tColor = parseColor(textStyle?.optString("color"), Color.WHITE)
            val tStrokeColor = parseColor(textStyle?.optString("strokeColor"), Color.TRANSPARENT)
            val tStrokeWidth = textStyle?.optDouble("strokeWidth", 0.0)?.toFloat() ?: 0f
            val tShadowRadius = textStyle?.optDouble("shadowRadius", 0.0)?.toFloat() ?: 0f
            val tShadowColor = parseColor(textStyle?.optString("shadowColor"), Color.TRANSPARENT)

            paint.style = Paint.Style.FILL
            paint.color = tColor
            paint.textSize = 120f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            
            if (tShadowRadius > 0 && tShadowColor != Color.TRANSPARENT) {
                paint.setShadowLayer(tShadowRadius, 5f, 5f, tShadowColor)
            } else {
                paint.clearShadowLayer()
            }

            val textY = cy - ((paint.descent() + paint.ascent()) / 2)
            
            // Text Outline
            if (tStrokeColor != Color.TRANSPARENT && tStrokeWidth > 0) {
                val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                outlinePaint.style = Paint.Style.STROKE
                outlinePaint.strokeWidth = tStrokeWidth
                outlinePaint.color = tStrokeColor
                outlinePaint.textSize = 120f
                outlinePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                outlinePaint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, cx, textY, outlinePaint)
            }
            
            canvas.drawText(text, cx, textY, paint)

        } catch (e: Exception) {
            // Fallback plain text if JSON is malformed
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 120f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            val textY = cy - ((paint.descent() + paint.ascent()) / 2)
            canvas.drawText(text, cx, textY, paint)
        }

        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1000
        val isVisible = timeMs in startMs..endMs

        val actualPosX = if (isVisible) posX else -1000f

        return object : OverlaySettings {
            override fun getBackgroundFrameAnchor() = Pair(actualPosX, posY)
            override fun getOverlayFrameAnchor() = Pair(0f, 0f)
            override fun getScale() = Pair(configScale, configScale)
            override fun getRotationDegrees() = rotation
        }
    }
}
