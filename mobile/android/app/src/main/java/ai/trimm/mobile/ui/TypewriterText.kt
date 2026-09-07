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
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    animate: Boolean = true
) {
    if (!animate) {
        Text(
            text = text,
            color = color,
            lineHeight = lineHeight,
            fontSize = fontSize,
            modifier = modifier
        )
        return
    }

    var displayedText by rememberSaveable { mutableStateOf("") }
    
    LaunchedEffect(text) {
        if (text == displayedText) return@LaunchedEffect
        
        val start = if (text.startsWith(displayedText)) displayedText.length else 0
        if (start == 0) displayedText = ""
        
        for (i in start until text.length) {
            displayedText += text[i]
            delay(15) // typing speed
        }
    }
    
    Text(
        text = displayedText,
        color = color,
        lineHeight = lineHeight,
        fontSize = fontSize,
        modifier = modifier
    )
}
