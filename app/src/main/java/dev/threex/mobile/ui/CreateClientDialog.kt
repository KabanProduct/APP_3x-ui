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
import dev.threex.mobile.domain.ClientDraft
import dev.threex.mobile.domain.supportsClientFlow
import java.util.UUID

@Composable internal fun CreateClientDialog(state: MobileState, dismiss: () -> Unit, submit: (ClientDraft) -> Unit) {
    val inbounds = state.snapshot?.inbounds.orEmpty().filter { it.optString("protocol") == "vless" }
    var draft by remember { mutableStateOf(ClientDraft()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!state.busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Новый клиент VLESS", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Имя / Email клиента", draft.email, { draft = draft.copy(email = it) }, enabled = !state.busy)
                    SectionTitle("Привязанные входящие")
                    if (inbounds.isEmpty()) Text("В панели нет VLESS-входящих. Сначала создайте подключение в мастере.")
                    inbounds.forEach { inbound ->
                        val id = inbound.getInt("id")
                        FilterChip(id in draft.inboundIds, enabled = !state.busy, onClick = {
                            draft = draft.copy(inboundIds = if (id in draft.inboundIds) draft.inboundIds - id else draft.inboundIds + id)
                        }, label = { Text("${inbound.optString("remark").ifBlank { "Входящее $id" }} · :${inbound.optInt("port")} · ${if (inbound.optBoolean("enable")) "включено" else "выключено"}") })
                    }
                    Text("Можно выбрать несколько VLESS-входящих.")
                    SectionTitle("Flow")
                    val compatible = draft.inboundIds.isNotEmpty() && inbounds.filter { it.optInt("id") in draft.inboundIds }.all(::supportsClientFlow)
                    listOf("" to "Без Flow", "xtls-rprx-vision" to "xtls-rprx-vision", "xtls-rprx-vision-udp443" to "xtls-rprx-vision-udp443").forEach { (value, label) ->
                        FilterChip(draft.flow == value, enabled = !state.busy && (value.isEmpty() || compatible), onClick = { draft = draft.copy(flow = value) }, label = { Text(label) })
                    }
                    if (!compatible) Text("Vision доступен только для совместимых входящих. Если изменили привязки, выберите подходящий Flow.")
                    Field("UUID", draft.uuid, { draft = draft.copy(uuid = it) }, enabled = !state.busy)
                    TextButton(enabled = !state.busy, onClick = { draft = draft.copy(uuid = UUID.randomUUID().toString()) }) { Text("Новый UUID") }
                    Field("ID подписки", draft.subId, { draft = draft.copy(subId = it) }, enabled = !state.busy)
                    Row { Checkbox(draft.enable, enabled = !state.busy, onCheckedChange = { draft = draft.copy(enable = it) }); Text("Доступ включён") }
                    if (!draft.enable) Text("3x-ui сначала создаёт клиента включённым; приложение сразу отправит отдельную команду отключения.")
                    Field("Лимит трафика, ГиБ · 0 = без лимита", draft.limitGiB, { draft = draft.copy(limitGiB = it) }, numeric = true, enabled = !state.busy)
                    Field("Лимит IP · 0 = без лимита", draft.limitIp, { draft = draft.copy(limitIp = it) }, numeric = true, enabled = !state.busy)
                    Field("Лимит устройств HWID · 0 = без лимита", draft.limitHwid, { draft = draft.copy(limitHwid = it) }, numeric = true, enabled = !state.busy)
                    Text("Применение лимитов IP/HWID зависит от настроек и поддержки на сервере.")
                    SectionTitle("Срок доступа")
                    listOf("never" to "Бессрочно", "days" to "Дней от создания", "delayed" to "Дней с первого подключения", "date" to "До даты").forEach { (value, label) ->
                        FilterChip(draft.expiryMode == value, enabled = !state.busy, onClick = { draft = draft.copy(expiryMode = value) }, label = { Text(label) })
                    }
                    if (draft.expiryMode in listOf("days", "delayed")) Field("Количество дней", draft.days, { draft = draft.copy(days = it) }, numeric = true, enabled = !state.busy)
                    if (draft.expiryMode == "date") Field("ГГГГ-ММ-ДД ЧЧ:ММ · время телефона", draft.expiryDate, { draft = draft.copy(expiryDate = it) }, enabled = !state.busy)
                    SectionTitle("Продление и сброс трафика")
                    Field("Автопродление: интервал дней · 0 = нет", draft.reset, { draft = draft.copy(reset = it) }, numeric = true, enabled = !state.busy)
                    Field("Автопродление: день месяца · 0 = по интервалу", draft.resetDay, { draft = draft.copy(resetDay = it) }, numeric = true, enabled = !state.busy)
                    Field("Максимум продлений · 0 = без ограничения", draft.resetMax, { draft = draft.copy(resetMax = it) }, numeric = true, enabled = !state.busy)
                    listOf("never" to "Не сбрасывать трафик", "hourly" to "Каждый час", "daily" to "Каждый день", "weekly" to "Каждую неделю", "monthly" to "Каждый месяц").forEach { (value, label) ->
                        FilterChip(draft.trafficReset == value, enabled = !state.busy, onClick = { draft = draft.copy(trafficReset = value) }, label = { Text(label) })
                    }
                    if (draft.trafficReset == "monthly") Field("День сброса трафика · 1–31", draft.trafficResetDay, { draft = draft.copy(trafficResetDay = it) }, numeric = true, enabled = !state.busy)
                    SectionTitle("Дополнительно")
                    Field("Telegram ID · 0 = не указан", draft.tgId, { draft = draft.copy(tgId = it) }, numeric = true, enabled = !state.busy)
                    Field("Группа", draft.group, { draft = draft.copy(group = it) }, enabled = !state.busy)
                    Field("Комментарий", draft.comment, { draft = draft.copy(comment = it) }, multiline = true, enabled = !state.busy)
                    Field("Reverse tag · необязательно", draft.reverseTag, { draft = draft.copy(reverseTag = it) }, enabled = !state.busy)
                }
                (error ?: state.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Actions {
                    TextButton(enabled = !state.busy, onClick = dismiss) { Text("Отмена") }
                    Button(enabled = !state.busy && state.snapshot?.canWrite == true && inbounds.isNotEmpty(), onClick = {
                        try { draft.payload(inbounds); error = null; submit(draft) }
                        catch (e: Exception) { error = e.message ?: "Проверьте поля клиента" }
                    }) { Text("Создать клиента") }
                }
            }
        }
    }
}
