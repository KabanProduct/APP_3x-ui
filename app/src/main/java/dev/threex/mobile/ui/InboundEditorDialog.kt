package dev.threex.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable internal fun EditorLoadingDialog(title: String, busy: Boolean, error: String?, dismiss: () -> Unit, retry: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Загрузка с сервера…") }
            else Text(error ?: "Не удалось загрузить настройки")
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = retry) { Text("Повторить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Закрыть") } })
}

@Composable internal fun InboundEditorDialog(inbound: JSONObject, busy: Boolean, error: String?, dismiss: () -> Unit, save: (Int, String) -> Unit) {
    var source by remember(inbound) { mutableStateOf(inbound.toString(2)) }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Настройки подключения", style = MaterialTheme.typography.titleLarge)
                Text("${inbound.optString("remark")} · ID ${inbound.getInt("id")}")
                Text("Редактор JSON. Смена транспорта или ключей может потребовать новых ссылок для клиентов.", style = MaterialTheme.typography.bodySmall)
                // A bounded editor avoids AlertDialog's intrinsic sizing of a large multiline field.
                OutlinedTextField(source, { source = it }, label = { Text("Конфигурация JSON") }, enabled = !busy,
                    modifier = Modifier.weight(1f).fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState())) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Actions {
                    TextButton(enabled = !busy, onClick = dismiss) { Text("Закрыть") }
                    Button(enabled = !busy, onClick = { save(inbound.getInt("id"), source) }) { Text("Сохранить") }
                }
            }
        }
    }
}
