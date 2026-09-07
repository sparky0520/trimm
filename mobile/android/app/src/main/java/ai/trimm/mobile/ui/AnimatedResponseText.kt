package ai.trimm.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AnimatedResponseText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(500))
    ) {
        Text(
            text = text,
            color = color,
            lineHeight = lineHeight,
            fontSize = fontSize,
            modifier = modifier
        )
    }
}
