package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.ColorGrade
import ai.ctrlb.mobile.domain.SetColorGrade
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class FineColorSettings(
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val skinProtection: Float = 0f
)

internal object FineColorMath {
    fun settings(operations: List<SetColorGrade>): FineColorSettings {
        var result = FineColorSettings()
        operations.forEach { operation ->
            operation.grade?.let { grade -> result = grade.toFineSettings() }
            val value = operation.value ?: return@forEach
            result = when (operation.parameter) {
                "exposure" -> result.copy(exposure = value)
                "temperature" -> result.copy(temperature = value)
                "contrast" -> result.copy(contrast = value)
                "saturation" -> result.copy(saturation = value)
                "highlights" -> result.copy(highlights = value)
                "shadows" -> result.copy(shadows = value)
                "skinProtection" -> result.copy(skinProtection = value)
                else -> result
            }
        }
        return result
    }

    fun adjust(red: Float, green: Float, blue: Float, settings: FineColorSettings): FloatArray {
        val original = floatArrayOf(red, green, blue)
        var r = red + settings.exposure * .18f + settings.temperature * .08f
        var g = green + settings.exposure * .18f
        var b = blue + settings.exposure * .18f - settings.temperature * .08f
        var luma = luma(r, g, b)
        r = luma + (r - luma) * settings.saturation
        g = luma + (g - luma) * settings.saturation
        b = luma + (b - luma) * settings.saturation
        r = (r - .5f) * settings.contrast + .5f
        g = (g - .5f) * settings.contrast + .5f
        b = (b - .5f) * settings.contrast + .5f
        luma = luma(r, g, b).coerceIn(0f, 1f)
        val tonal = settings.shadows * .18f * (1f - luma) * (1f - luma) +
            settings.highlights * .18f * luma * luma
        r += tonal; g += tonal; b += tonal
        val protection = settings.skinProtection * skinLikelihood(original[0], original[1], original[2])
        return floatArrayOf(
            mix(r, original[0], protection).coerceIn(0f, 1f),
            mix(g, original[1], protection).coerceIn(0f, 1f),
            mix(b, original[2], protection).coerceIn(0f, 1f)
        )
    }

    private fun luma(r: Float, g: Float, b: Float) = r * .2126f + g * .7152f + b * .0722f
    private fun mix(adjusted: Float, original: Float, amount: Float) = adjusted * (1f - amount) + original * amount
    private fun skinLikelihood(r: Float, g: Float, b: Float): Float {
        val maximum = max(r, max(g, b)); val minimum = min(r, min(g, b))
        if (r <= g || g <= b || maximum - minimum < .06f || r < .2f) return 0f
        val warmth = ((r - b) / .45f).coerceIn(0f, 1f)
        val greenBalance = (1f - abs(g - (r + b) * .5f) / .3f).coerceIn(0f, 1f)
        return warmth * greenBalance
    }

    private fun ColorGrade.toFineSettings() = FineColorSettings(
        exposure, temperature, contrast, saturation, highlights, shadows, skinProtection
    )
}

@OptIn(UnstableApi::class)
internal object FineColorEffect {
    private const val LUT_SIZE = 17

    fun create(operations: List<SetColorGrade>): Effect? {
        if (operations.isEmpty()) return null
        val settings = FineColorMath.settings(operations)
        val cube = Array(LUT_SIZE) { Array(LUT_SIZE) { IntArray(LUT_SIZE) } }
        for (red in 0 until LUT_SIZE) for (green in 0 until LUT_SIZE) for (blue in 0 until LUT_SIZE) {
            val adjusted = FineColorMath.adjust(
                red.toFloat() / (LUT_SIZE - 1), green.toFloat() / (LUT_SIZE - 1), blue.toFloat() / (LUT_SIZE - 1), settings
            )
            cube[red][green][blue] = Color.argb(255, (adjusted[0] * 255).toInt(),
                (adjusted[1] * 255).toInt(), (adjusted[2] * 255).toInt())
        }
        return SingleColorLut.createFromCube(cube)
    }
}
