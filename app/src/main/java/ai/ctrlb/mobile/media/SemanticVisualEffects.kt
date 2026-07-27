package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.ApplySemanticVisual
import ai.ctrlb.mobile.domain.MotionCurve
import ai.ctrlb.mobile.domain.SemanticTreatment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import kotlin.math.PI
import kotlin.math.cos

@UnstableApi
class SemanticVisualOverlay(
    private val operations: List<ApplySemanticVisual>
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 48)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(135, 4, 6, 10) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val timeMs = presentationTimeUs / 1_000
        operations.filter { timeMs in it.range.startMs until it.range.endMs }.forEach { operation ->
            val bounds = operation.subject.bounds
            val left = bounds.left * bitmap.width
            val top = bounds.top * bitmap.height
            val right = bounds.right * bitmap.width
            val bottom = bounds.bottom * bitmap.height
            when (operation.treatment) {
                SemanticTreatment.CALLOUT -> {
                    canvas.drawRoundRect(left - 8, top - 8, right + 8, bottom + 8, 18f, 18f, stroke)
                    canvas.drawLine(right + 8, top, minOf(bitmap.width.toFloat(), right + 72), maxOf(12f, top - 42), stroke)
                }
                SemanticTreatment.SPOTLIGHT -> {
                    canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), shade)
                    canvas.drawOval(left - 12, top - 12, right + 12, bottom + 12, Paint().apply {
                        isAntiAlias = true
                        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    })
                }
                SemanticTreatment.REFRAME -> Unit
            }
        }
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1_000
        val active = operations.firstOrNull { timeMs in it.range.startMs until it.range.endMs }
        val alpha = active?.let { operation ->
            if (operation.motion.reducedMotion || operation.motion.curve == MotionCurve.HOLD) 1f
            else {
                val progress = (timeMs - operation.range.startMs).toFloat() / operation.range.durationMs
                val edge = minOf(progress / .15f, (1f - progress) / .15f).coerceIn(0f, 1f)
                (.65f + .35f * edge)
            }
        } ?: 0f
        return object : OverlaySettings {
            override fun getAlphaScale() = alpha
        }
    }
}

@UnstableApi
class SemanticReframeTransformation(
    private val operations: List<ApplySemanticVisual>
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val timeMs = presentationTimeUs / 1_000
        val operation = operations.firstOrNull {
            it.treatment == SemanticTreatment.REFRAME && timeMs in it.range.startMs until it.range.endMs
        } ?: return Matrix()
        val framing = operation.framing ?: return Matrix()
        val progress = ((timeMs - operation.range.startMs).toFloat() / operation.range.durationMs).coerceIn(0f, 1f)
        val eased = if (operation.motion.reducedMotion || operation.motion.curve == MotionCurve.HOLD) 1f
        else ((1f - cos(progress * PI).toFloat()) / 2f)
        val zoom = 1f + (framing.zoom - 1f) * eased
        val centerX = (operation.subject.bounds.left + operation.subject.bounds.right) / 2f
        val centerY = (operation.subject.bounds.top + operation.subject.bounds.bottom) / 2f
        return Matrix().apply {
            setScale(zoom, zoom)
            postTranslate((.5f - centerX) * 2f * zoom, (centerY - .5f) * 2f * zoom)
        }
    }
}
