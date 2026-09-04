package ai.trimm.mobile.ui

import ai.trimm.mobile.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


@Composable fun <T> Choice(label: String, values: List<T>, selected: T, select: (T) -> Unit) {
    Text(label, color = Cream, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            val chosen = value == selected
            FilterChip(selected = chosen, onClick = { select(value) }, label = { Text(value.toString().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                shape = RoundedCornerShape(9.dp), colors = FilterChipDefaults.filterChipColors(containerColor = Raised, labelColor = Muted, selectedContainerColor = Coral.copy(alpha = .18f), selectedLabelColor = Cream),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = chosen, borderColor = Border, selectedBorderColor = Coral))
        }
    }
    Spacer(Modifier.height(20.dp))
}
