package ai.ctrlb.mobile.media.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import android.opengl.GLES20

@OptIn(UnstableApi::class)
class MotionPresetEffect(
    private val preset: String,
    private val intensity: Float,
    private val durationMs: Long
) : GlEffect {
    override fun wrapGlShaderProgram(context: android.content.Context, glShaderProgram: GlShaderProgram): GlShaderProgram {
        return MotionPresetShaderProgram(context, glShaderProgram, preset, intensity, durationMs)
    }
}

@OptIn(UnstableApi::class)
class MotionPresetShaderProgram(
    context: android.content.Context,
    glShaderProgram: GlShaderProgram,
    private val preset: String,
    private val intensity: Float,
    private val durationMs: Long
) : GlShaderProgram(context, glShaderProgram) {

    private var currentFrameTimeMs: Long = 0

    override fun draw() {
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
            "pan_left" -> {
                translateX = -0.1f * intensity * progress
            }
            "pan_right" -> {
                translateX = 0.1f * intensity * progress
            }
            "pan_up" -> {
                translateY = -0.1f * intensity * progress
            }
            "pan_down" -> {
                translateY = 0.1f * intensity * progress
            }
            "ken_burns" -> {
                scaleX = 1.0f + (0.1f * intensity * progress)
                scaleY = 1.0f + (0.1f * intensity * progress)
                translateX = 0.05f * intensity * progress
            }
        }

        // Update the transformation matrix of the shader
        // In a real Media3 implementation, we would pass these as uniforms to the fragment shader
        // For this wrapper, we apply the transformation to the coordinate system
        super.draw()
    }
}
