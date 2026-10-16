package ai.trimm.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

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

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(500))
    ) {
        MarkdownText(
            markdown = text,
            color = if (color != Color.Unspecified) color else Cream,
            fontSize = if (fontSize != TextUnit.Unspecified) fontSize else 15.sp,
            lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else 22.sp,
            modifier = modifier
        )
    }
}
