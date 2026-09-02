@file:OptIn(UnstableApi::class)
package ai.ctrlb.mobile.media.effects

import androidx.annotation.OptIn
import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

class MotionPresetEffect(
    private val preset: String,
    private val intensity: Float,
    private val durationMs: Long
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MotionPresetShaderProgram(useHdr, preset, intensity, durationMs)
    }
}

class MotionPresetShaderProgram(
    useHdr: Boolean,
    private val preset: String,
    private val intensity: Float,
    private val durationMs: Long
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val currentFrameTimeMs = presentationTimeUs / 1000
        val progress = (currentFrameTimeMs.toFloat() / durationMs).coerceIn(0f, 1f)

        var scaleX = 1.0f
        var scaleY = 1.0f
        var translateX = 0.0f
        var translateY = 0.0f

        when (preset) {
            "slow_zoom_in" -> {
                scaleX = 1.0f + (0.2f * intensity * progress)
                scaleY = 1.0f + (0.2f * intensity * progress)
            }
            "slow_zoom_out" -> {
                scaleX = 1.2f - (0.2f * intensity * progress)
                scaleY = 1.2f - (0.2f * intensity * progress)
            }
            "pan_left" -> translateX = -0.1f * intensity * progress
            "pan_right" -> translateX = 0.1f * intensity * progress
            "pan_up" -> translateY = -0.1f * intensity * progress
            "pan_down" -> translateY = 0.1f * intensity * progress
            "ken_burns" -> {
                scaleX = 1.0f + (0.1f * intensity * progress)
                scaleY = 1.0f + (0.1f * intensity * progress)
                translateX = 0.05f * intensity * progress
            }
        }

        // Transformation parameters (scaleX, scaleY, translateX, translateY) would be passed
        // as uniforms to a custom GLSL shader in a full implementation.
        // Currently passes through the frame unmodified.
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, inputTexId)
    }
}
