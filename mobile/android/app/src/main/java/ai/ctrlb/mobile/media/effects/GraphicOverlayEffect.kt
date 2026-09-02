package ai.ctrlb.mobile.media.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import android.opengl.GLES20

@OptIn(UnstableApi::class)
class GraphicOverlayEffect(
    private val callouts: List<CalloutData>,
    private val spotlights: List<SpotlightData>
) : GlEffect {
    data class CalloutData(val text: String, val rect: ai.ctrlb.mobile.domain.NormalizedRect, val range: ai.ctrlb.mobile.domain.TimeRange?)
    data class SpotlightData(val rect: ai.ctrlb.mobile.domain.NormalizedRect, val range: ai.ctrlb.mobile.domain.TimeRange?)

    override fun wrapGlShaderProgram(context: android.content.Context, glShaderProgram: GlShaderProgram): GlShaderProgram {
        return GraphicOverlayShaderProgram(context, glShaderProgram, callouts, spotlights)
    }
}

@OptIn(UnstableApi::class)
class GraphicOverlayShaderProgram(
    context: android.content.Context,
    glShaderProgram: GlShaderProgram,
    private val callouts: List<CalloutData>,
    private val spotlights: List<SpotlightData>
) : GlShaderProgram(context, glShaderProgram) {

    override fun draw() {
        // Implementation would involve:
        // 1. Rendering the spotlight mask (darkening the screen except for the target rect)
        // 2. Drawing callout lines and text overlays using a separate texture or canvas
        super.draw()
    }
}
