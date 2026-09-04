package ai.trimm.mobile.media

import ai.trimm.mobile.domain.SetColorGrade
import org.junit.Assert.assertTrue
import org.junit.Test

class FineColorEffectTest {
    @Test fun `contrast expands tones around midpoint`() {
        val settings = FineColorMath.settings(listOf(SetColorGrade(parameter = "contrast", value = 1.3f)))
        val dark = FineColorMath.adjust(.25f, .25f, .25f, settings)
        val light = FineColorMath.adjust(.75f, .75f, .75f, settings)
        assertTrue(dark[0] < .25f)
        assertTrue(light[0] > .75f)
    }

    @Test fun `shadows and highlights affect their intended tonal regions`() {
        val shadows = FineColorMath.settings(listOf(SetColorGrade(parameter = "shadows", value = .8f)))
        val highlights = FineColorMath.settings(listOf(SetColorGrade(parameter = "highlights", value = -.8f)))
        assertTrue(FineColorMath.adjust(.15f, .15f, .15f, shadows)[0] > .15f)
        assertTrue(FineColorMath.adjust(.85f, .85f, .85f, highlights)[0] < .85f)
    }

    @Test fun `skin protection attenuates a grade for skin-like colors`() {
        val unprotected = FineColorMath.settings(listOf(
            SetColorGrade(parameter = "temperature", value = 1f),
            SetColorGrade(parameter = "skinProtection", value = 0f)
        ))
        val protected = unprotected.copy(skinProtection = 1f)
        val original = floatArrayOf(.72f, .48f, .34f)
        val changed = FineColorMath.adjust(original[0], original[1], original[2], unprotected)
        val guarded = FineColorMath.adjust(original[0], original[1], original[2], protected)
        assertTrue(kotlin.math.abs(guarded[0] - original[0]) < kotlin.math.abs(changed[0] - original[0]))
    }
}
