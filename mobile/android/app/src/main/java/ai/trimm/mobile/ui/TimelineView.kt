package ai.trimm.mobile.ui

import ai.trimm.mobile.domain.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TimelineView(project: MobileProject) {
    val clips = project.orderedSources
    if (clips.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Workspace, RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("TIMELINE", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            clips.forEachIndexed { index, clip ->
                val operationsForClip = project.operations.filter { op ->
                    op.enabled && when (op) {
                        is RemoveRanges -> true 
                        is SetColorGrade -> op.target?.sourceId == clip.fingerprint || op.target?.sourceId == "${clip.fingerprint}-$index"
                        is NormaliseAudio -> op.target?.sourceId == clip.fingerprint || op.target?.sourceId == "${clip.fingerprint}-$index"
                        is SetVolume -> op.itemId == clip.fingerprint || op.itemId == "${clip.fingerprint}-$index"
                        is SetSpeed -> op.itemId == clip.fingerprint || op.itemId == "${clip.fingerprint}-$index" || op.target?.sourceId == clip.fingerprint || op.target?.sourceId == "${clip.fingerprint}-$index"
                        is SetRotation -> op.itemId == clip.fingerprint || op.itemId == "${clip.fingerprint}-$index"
                        is SetCrop -> op.itemId == clip.fingerprint || op.itemId == "${clip.fingerprint}-$index"
                        is AddMotionPreset -> true
                        else -> false
                    }
                }

                Column(Modifier.width(120.dp).background(Raised, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text("Clip ${index + 1}", color = Cream, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(formatDuration(clip.durationMs), color = Muted, fontSize = 10.sp)
                    if (operationsForClip.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        operationsForClip.forEach { op ->
                            Text("• ${operationShortName(op)}", color = Color(0xFFAAA9B8), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun operationShortName(op: EditInstruction) = when (op) {
    is RemoveRanges -> "Cuts"
    is SetColorGrade -> "Color"
    is NormaliseAudio -> "Audio Duck"
    is SetVolume -> "Volume"
    is SetSpeed -> "Speed"
    is SetRotation -> "Rotate"
    is SetCrop -> "Crop"
    is AddMotionPreset -> "Motion"
    else -> "Edit"
}
