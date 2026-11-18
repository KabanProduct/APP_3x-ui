package dev.threex.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable internal fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.headlineSmall) }
@Composable internal fun AppCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable internal fun Actions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
@Composable internal fun Field(label: String, value: String, change: (String) -> Unit, secret: Boolean = false, numeric: Boolean = false, multiline: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = !multiline,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text))
}
@Composable internal fun FormDialog(title: String, onDismiss: () -> Unit, confirm: () -> Unit, label: String, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { TextButton(onClick = confirm, enabled = enabled) { Text(label) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
internal fun bytes(value: Long): String = when {
    value >= 1073741824 -> String.format(Locale.getDefault(), "%.1f ГиБ", value / 1073741824.0)
    value >= 1048576 -> String.format(Locale.getDefault(), "%.1f МиБ", value / 1048576.0)
    value >= 1024 -> String.format(Locale.getDefault(), "%.1f КиБ", value / 1024.0)
    else -> "$value Б"
}
