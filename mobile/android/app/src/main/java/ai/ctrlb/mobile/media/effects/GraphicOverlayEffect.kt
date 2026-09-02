@file:OptIn(UnstableApi::class)
package ai.ctrlb.mobile.media.effects

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

class GraphicOverlayEffect(
    private val callouts: List<CalloutData>,
    private val spotlights: List<SpotlightData>
) : GlEffect {
    data class CalloutData(val text: String, val rect: ai.ctrlb.mobile.domain.NormalizedRect, val range: ai.ctrlb.mobile.domain.TimeRange?)
    data class SpotlightData(val rect: ai.ctrlb.mobile.domain.NormalizedRect, val range: ai.ctrlb.mobile.domain.TimeRange?)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return GraphicOverlayShaderProgram(useHdr, callouts, spotlights)
    }
}

class GraphicOverlayShaderProgram(
    useHdr: Boolean,
    private val callouts: List<GraphicOverlayEffect.CalloutData>,
    private val spotlights: List<GraphicOverlayEffect.SpotlightData>
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // Implementation would involve:
        // 1. Rendering the spotlight mask (darkening the screen except for the target rect)
        // 2. Drawing callout lines and text overlays using a separate texture or canvas
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, inputTexId)
    }
}
