package ai.trimm.mobile.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.util.Pair
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import ai.trimm.mobile.R
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

data class CaptionCue(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val blueprintJson: String
)

data class RenderedCaption(
    val bitmap: Bitmap,
    val posX: Float,
    val posY: Float,
    val scale: Float,
    val rotation: Float
)

@UnstableApi
class TimelineCaptionsOverlay(
    private val context: Context,
    private val cues: List<CaptionCue>,
    private val videoWidth: Int,
    private val videoHeight: Int
) : BitmapOverlay() {

    private val width = 1000
    private val height = if (videoWidth > 0) (1000f * videoHeight / videoWidth).toInt() else 1000

    private val emptyBitmap: Bitmap by lazy {
        val empty = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        empty.eraseColor(Color.TRANSPARENT)
        empty
    }

    private val cache = LruCache<String, RenderedCaption>(24)

    private var lastActiveCue: CaptionCue? = null
    private var lastRenderedCaption: RenderedCaption? = null

    private fun parseColor(colorString: String?, defaultColor: Int): Int {
        if (colorString.isNullOrEmpty()) return defaultColor
        return try { Color.parseColor(colorString) } catch (e: Exception) { defaultColor }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        val activeCue = cues.firstOrNull { timeMs in it.startMs..it.endMs }
        lastActiveCue = activeCue

        if (activeCue == null) {
            lastRenderedCaption = null
            return emptyBitmap
        }

        val cacheKey = "${activeCue.id}_${activeCue.blueprintJson.hashCode()}"
        val rendered = cache.get(cacheKey) ?: renderCue(activeCue).also {
            cache.put(cacheKey, it)
        }
        lastRenderedCaption = rendered
        return rendered.bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val rendered = lastRenderedCaption
        val isVisible = (lastActiveCue != null && rendered != null)

        val actualPosX = if (isVisible && rendered != null) rendered.posX else -1000f
        val actualPosY = if (isVisible && rendered != null) rendered.posY else -1000f
        val actualScale = if (isVisible && rendered != null) rendered.scale else 1f
        val actualRot = if (isVisible && rendered != null) rendered.rotation else 0f

        return object : OverlaySettings {
            override fun getBackgroundFrameAnchor() = Pair(actualPosX, actualPosY)
            override fun getOverlayFrameAnchor() = Pair(0f, 0f)
            override fun getScale() = Pair(actualScale, actualScale)
            override fun getRotationDegrees() = actualRot
        }
    }

    private fun renderCue(cue: CaptionCue): RenderedCaption {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val cy = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var posX = 0f
        var posY = -0.35f
        var configScale = 1f
        var rotation = 0f

        try {
            val blueprint = JSONObject(cue.blueprintJson)
            val layout = blueprint.optJSONObject("layout")
            if (layout != null) {
                posX = layout.optDouble("posX", 0.0).toFloat()
                posY = layout.optDouble("posY", -0.35).toFloat()
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
                            canvas.drawRoundRect(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2, corner, corner, paint)
                        }
                        if (strokeColor != Color.TRANSPARENT && strokeWidth > 0) {
                            paint.style = Paint.Style.STROKE
                            paint.color = strokeColor
                            paint.strokeWidth = strokeWidth
                            canvas.drawRoundRect(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2, corner, corner, paint)
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
            val fontFamily = textStyle?.optString("fontFamily") ?: "plain"
            val fontSize = textStyle?.optDouble("fontSize", 100.0)?.toFloat() ?: 100f
            val tBackgroundColor = parseColor(textStyle?.optString("backgroundColor"), Color.TRANSPARENT)
            val bgPadding = (textStyle?.optDouble("backgroundPadding", 24.0)?.toFloat() ?: 24f) * (width / 1000f).coerceAtLeast(0.5f)
            val bgCornerRadius = (textStyle?.optDouble("backgroundCornerRadius", 16.0)?.toFloat() ?: 16f) * (width / 1000f).coerceAtLeast(0.5f)

            val tf = when (fontFamily) {
                "comic" -> ResourcesCompat.getFont(context, R.font.bangers)
                "neon" -> ResourcesCompat.getFont(context, R.font.orbitron)
                "typewriter" -> ResourcesCompat.getFont(context, R.font.special_elite)
                "elegant" -> ResourcesCompat.getFont(context, R.font.pacifico)
                else -> ResourcesCompat.getFont(context, R.font.roboto)
            } ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            textPaint.style = Paint.Style.FILL
            textPaint.color = tColor
            textPaint.textSize = fontSize
            textPaint.typeface = tf

            if (tShadowRadius > 0 && tShadowColor != Color.TRANSPARENT) {
                textPaint.setShadowLayer(tShadowRadius, 5f, 5f, tShadowColor)
            } else if (tBackgroundColor != Color.TRANSPARENT) {
                textPaint.clearShadowLayer()
            } else {
                textPaint.setShadowLayer(4f, 0f, 2f, Color.argb(200, 0, 0, 0))
            }

            val segmentsArray = blueprint.optJSONArray("segments")
            val charSequence: CharSequence = if (segmentsArray != null && segmentsArray.length() > 0) {
                val ssb = android.text.SpannableStringBuilder()
                for (i in 0 until segmentsArray.length()) {
                    val seg = segmentsArray.getJSONObject(i)
                    val segText = seg.optString("text", "")
                    val segColorStr = seg.optString("color")
                    val segColor = parseColor(segColorStr, tColor)
                    val start = ssb.length
                    ssb.append(segText)
                    ssb.setSpan(
                        android.text.style.ForegroundColorSpan(segColor),
                        start,
                        ssb.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                ssb
            } else {
                cue.text
            }

            val textWidth = (width * 0.9f).toInt()
            val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(charSequence, 0, charSequence.length, textPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(charSequence, textPaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
            }

            val textHeight = staticLayout.height
            val textY = cy - (textHeight / 2f)

            // Draw Background Badge (if backgroundColor is specified)
            if (tBackgroundColor != Color.TRANSPARENT) {
                var maxLineWidth = 0f
                for (i in 0 until staticLayout.lineCount) {
                    val lw = staticLayout.getLineWidth(i)
                    if (lw > maxLineWidth) maxLineWidth = lw
                }
                if (maxLineWidth <= 0f) maxLineWidth = staticLayout.width.toFloat()

                val padX = bgPadding
                val padY = bgPadding * 0.6f
                val bgLeft = (cx - (maxLineWidth / 2f) - padX).coerceAtLeast(0f)
                val bgRight = (cx + (maxLineWidth / 2f) + padX).coerceAtMost(width.toFloat())
                val bgTop = textY - padY
                val bgBottom = textY + textHeight + padY

                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = tBackgroundColor
                }
                canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, bgCornerRadius, bgCornerRadius, bgPaint)
            }

            // Text Outline
            if (tStrokeColor != Color.TRANSPARENT && tStrokeWidth > 0) {
                val outlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                outlinePaint.style = Paint.Style.STROKE
                outlinePaint.strokeWidth = tStrokeWidth
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.color = tStrokeColor
                outlinePaint.textSize = fontSize
                outlinePaint.typeface = tf

                val outlineLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(cue.text, 0, cue.text.length, outlinePaint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(cue.text, outlinePaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
                }

                canvas.save()
                canvas.translate(cx - textWidth / 2f, textY)
                outlineLayout.draw(canvas)
                canvas.restore()
            }

            canvas.save()
            canvas.translate(cx - textWidth / 2f, textY)
            staticLayout.draw(canvas)
            canvas.restore()

        } catch (e: Exception) {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
                textSize = 100f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val textWidth = (width * 0.9f).toInt()
            val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(cue.text, 0, cue.text.length, textPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(cue.text, textPaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
            }
            val textY = cy - (staticLayout.height / 2f)
            canvas.save()
            canvas.translate(cx - textWidth / 2f, textY)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        return RenderedCaption(
            bitmap = bitmap,
            posX = posX,
            posY = posY,
            scale = configScale,
            rotation = rotation
        )
    }

    override fun release() {
        super.release()
        cache.evictAll()
        if (!emptyBitmap.isRecycled) {
            emptyBitmap.recycle()
        }
    }
}
