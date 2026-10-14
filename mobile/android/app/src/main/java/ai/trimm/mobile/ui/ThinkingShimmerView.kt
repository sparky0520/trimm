package ai.trimm.mobile.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ThinkingShimmerView(
    text: String,
    modifier: Modifier = Modifier
) {
    val isEdit = text.contains("edit", ignoreCase = true)
    val icon = if (isEdit) Icons.Default.AutoAwesome else Icons.Default.AutoAwesome

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslateAnim by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerColors = listOf(
        Cream.copy(alpha = 0.35f),
        Color.White.copy(alpha = 0.95f),
        Cream.copy(alpha = 0.35f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = androidx.compose.ui.geometry.Offset(shimmerTranslateAnim, 0f),
        end = androidx.compose.ui.geometry.Offset(shimmerTranslateAnim + 180f, 0f)
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Workspace)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFFDE047),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            style = androidx.compose.ui.text.TextStyle(brush = brush)
        )
    }
}
