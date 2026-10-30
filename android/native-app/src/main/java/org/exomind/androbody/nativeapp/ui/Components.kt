package org.exomind.androbody.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun SectionTitle(title: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun SelectableText(text: String, code: Boolean = false) {
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (code) FontFamily.Monospace else FontFamily.Default) }
}
@Composable fun InfoPanel(title: String, detail: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectableText(detail)
        }
    }
}
@Composable fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit,
    enabled: Boolean = true, testTag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title }.then(
                if (testTag == null) Modifier else Modifier.testTag(testTag)))
    }
}
fun readableTime(millis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
