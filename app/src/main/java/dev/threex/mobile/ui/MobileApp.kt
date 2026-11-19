package dev.threex.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.threex.mobile.data.*
import dev.threex.mobile.vpn.PhoneVpnService
import org.json.JSONObject

@Composable
fun MobileApp(vm: MobileViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val updatesVm: UpdatesViewModel = viewModel()
    val updates by updatesVm.state.collectAsStateWithLifecycle()
    val vpn by PhoneVpnService.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var serverForm by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }
    var vpnImport by remember { mutableStateOf(false) }
    var newClient by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var pendingVpn by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startVpn(id: String) {
        context.startForegroundService(Intent(context, PhoneVpnService::class.java).putExtra("profileId", id))
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) pendingVpn?.let { id -> startVpn(id) }
        pendingVpn = null
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF71DFC2), onPrimary = Color(0xFF08251D), secondaryContainer = Color(0xFF25473F), onSecondaryContainer = Color(0xFFADEDDC), background = Color(0xFF0C1419), surface = Color(0xFF121F26), surfaceContainer = Color(0xFF192A33), surfaceContainerHighest = Color(0xFF20323C), onSurface = Color(0xFFE6F0F3))) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("VPN", "Панель", "Мастер", "VPS", "Версия").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "▤", "+", ">_", "↓")[index]) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("3x Mobile", style = MaterialTheme.typography.headlineLarge)
                Text(listOf("Личный VPN", "Управление сервером", "Быстрая настройка VLESS", "Подготовка сервера", "Обновления приложения")[tab], color = MaterialTheme.colorScheme.primary)
                if (tab != 4 && updates.release != null) TextButton(onClick = { tab = 4 }) { Text("Доступно обновление ${updates.release?.version}") }
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.activity) }
                state.message?.let { message -> AppCard("Результат") { Text(message); TextButton(onClick = vm::clearMessage) { Text("Закрыть") } } }
                when (tab) {
                    4 -> UpdatesScreen(updatesVm, updates)
                    0 -> {
                        AppCard(vpn.phase) {
                            Text("↑ ${bytes(vpn.uploaded)}    ↓ ${bytes(vpn.downloaded)}")
                            vpn.delayMs?.takeIf { it >= 0 }?.let { Text("Проверка через Xray: $it мс") }
                            if (vpn.running) Button(onClick = { context.startService(Intent(context, PhoneVpnService::class.java).setAction(PhoneVpnService.STOP)) }) { Text("Отключить") }
                        }
                        Button(onClick = { vpnImport = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Импортировать VLESS-ссылку") }
                        if (state.vpnProfiles.isEmpty()) AppCard("Профилей пока нет") { Text("Создайте клиента во вкладке «Панель» и сохраните его ссылку для VPN или импортируйте готовую ссылку.") }
                        state.vpnProfiles.forEach { profile ->
                            AppCard(profile.name) {
                                Text(if (vpn.profileId == profile.id && vpn.running) "Используется сейчас" else "VLESS · профиль сохранён на телефоне")
                                Actions {
                                    Button(enabled = !vpn.running, onClick = {
                                        val prepare = VpnService.prepare(context)
                                        if (prepare == null) startVpn(profile.id) else { pendingVpn = profile.id; vpnPermission.launch(prepare) }
                                    }) { Text("Подключить") }
                                    TextButton(enabled = !state.busy && !(vpn.running && vpn.profileId == profile.id), onClick = { confirmation = "Удалить VPN-профиль «${profile.name}»?" to { vm.removeVpn(profile.id) } }) { Text("Удалить") }
                                }
                            }
                        }
                        Text("Трафик приложений, DNS и IPv6 направляются в VPN. Управление сервером из 3x Mobile работает напрямую. Проверка интернета выполняется через Xray на gstatic.com.", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Actions {
                            Button(enabled = !state.busy, onClick = { selectedServer = null; serverForm = true }) { Text("Добавить сервер") }
                            if (state.activeServer != null) OutlinedButton(enabled = !state.busy, onClick = vm::refresh) { Text("Обновить") }
                        }
                        state.servers.forEach { server ->
                            AppCard(server.name) {
                                Text(if (state.activeServer?.id == server.id) "Панель подключена" else "Сохранённый сервер")
                                Actions {
                                    TextButton(enabled = !state.busy, onClick = { selectedServer = server; serverForm = true }) { Text("Войти / изменить") }
                                    TextButton(enabled = !state.busy, onClick = { confirmation = "Удалить данные «${server.name}» с телефона? Сервер продолжит работать." to { vm.forgetServer(server) } }) { Text("Забыть") }
                                }
                            }
                        }
                        state.snapshot?.let { snapshot ->
                            AppCard("${state.activeServer?.name} · 3x-ui ${snapshot.version}") {
                                val xray = snapshot.status.optJSONObject("xray")
                                Text("Xray ${xray?.optString("version").orEmpty()} · ${xray?.optString("state").orEmpty()}")
                                Text("Подключений: ${snapshot.inbounds.size} · клиентов: ${snapshot.clients.size}")
                                if (!snapshot.canWrite) Text("Эта версия доступна только для просмотра. Изменения поддерживаются для 3.7.0.")
                                TextButton(enabled = !state.busy, onClick = vm::disconnectPanel) { Text("Закрыть сессию в приложении") }
                            }
                            SectionTitle("Подключения")
                            snapshot.inbounds.forEach { inbound ->
                                val id = inbound.getInt("id")
                                val name = inbound.optString("remark").ifBlank { "Подключение $id" }
                                AppCard(name) {
                                    Text("${inbound.optString("protocol").uppercase()} · порт ${inbound.optInt("port")} · ${if (inbound.optBoolean("enable")) "включено" else "выключено"}")
                                    Text("↑ ${bytes(inbound.optLong("up"))}    ↓ ${bytes(inbound.optLong("down"))}")
                                    Actions {
                                        TextButton(enabled = !state.busy && snapshot.canWrite, onClick = { vm.toggleInbound(id, !inbound.optBoolean("enable")) }) { Text(if (inbound.optBoolean("enable")) "Выключить" else "Включить") }
                                        TextButton(enabled = !state.busy && snapshot.canWrite, onClick = { vm.openInboundEditor(id) }) { Text("Настройки") }
                                        TextButton(enabled = !state.busy && snapshot.canWrite, onClick = { confirmation = "Удалить подключение «$name»? Пользователи потеряют этот способ доступа." to { vm.deleteInbound(id) } }) { Text("Удалить") }
                                    }
                                }
                            }
                            SectionTitle("Клиенты")
                            Button(enabled = !state.busy && snapshot.canWrite && snapshot.inbounds.any { it.optString("protocol") == "vless" }, onClick = { vm.clearMessage(); newClient = true }) { Text("Создать клиента") }
                            snapshot.clients.forEach { client ->
                                val email = client.getString("email")
                                AppCard(email) {
                                    val traffic = client.optJSONObject("traffic")
                                    Text(if (client.optBoolean("enable")) "Доступ включён" else "Доступ выключен")
                                    Text("Использовано: ${bytes((traffic?.optLong("up") ?: 0) + (traffic?.optLong("down") ?: 0))} · лимит: ${if (client.optLong("totalGB") == 0L) "без лимита" else bytes(client.optLong("totalGB"))}")
                                    val expiry = client.optLong("expiryTime")
                                    Text(if (expiry == 0L) "Без срока действия" else if (expiry < 0) "Срок активируется при подключении" else "До ${java.text.DateFormat.getDateInstance().format(java.util.Date(expiry))}")
                                    Actions {
                                        TextButton(enabled = !state.busy, onClick = { vm.clientLinks(email) }) { Text("Ссылки / QR") }
                                        TextButton(enabled = !state.busy && snapshot.canWrite, onClick = { vm.toggleClient(email, !client.optBoolean("enable")) }) { Text(if (client.optBoolean("enable")) "Отключить" else "Включить") }


                                    }
                                    OutlinedButton(enabled = !state.busy && snapshot.canWrite && client.optString("uuid").isNotBlank(), onClick = { vm.openClientEditor(email) }) { Text("Настройки клиента") }
                                    TextButton(enabled = !state.busy && snapshot.canWrite, onClick = { confirmation = "Удалить клиента «$email» из всех входящих? Его доступ будет прекращён." to { vm.deleteClient(email) } }) { Text("Удалить клиента", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        if (state.servers.isEmpty()) Text("Укажите полный HTTPS-адрес панели, включая секретный путь. Если панель не установлена, откройте вкладку VPS.")
                    }
                    2 -> QuickSetupForm(state, vm::loadRealityCandidates) { input -> confirmation = "Создать ${input.preset.title} на ${input.publicHost}:${input.port} и клиента «${input.clientName}»?" to { vm.quickSetup(input) } }
                    3 -> SshForm(state, vm) { host, port -> confirmation = "Установить 3x-ui 3.7.0 на проверенный VPS? Будут добавлены пакеты curl, tar, OpenSSL, служба x-ui, SQLite и HTTPS-панель на порту $port. Сертификат на 365 дней будет закреплён через SSH. Firewall автоматически не меняется." to { vm.install(host, port) } }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (serverForm) ServerDialog(selectedServer, state.busy, { serverForm = false }) { profile, otp -> vm.connect(profile, otp); serverForm = false }
        if (vpnImport) ImportDialog({ vpnImport = false }) { vm.importVpn(it); vpnImport = false }
        if (newClient) CreateClientDialog(state, { newClient = false }) { draft -> vm.addClient(draft) { newClient = false } }
        state.clientEditorEmail?.let { email ->
            val edit = state.clientEditor
            if (edit == null) EditorLoadingDialog("Настройки клиента", state.busy, state.message, vm::closeClientEditor) { vm.openClientEditor(email) }
            else CreateClientDialog(state, vm::closeClientEditor, editing = edit, delete = {
                confirmation = "Удалить клиента «${edit.email}» из всех входящих? Его доступ будет прекращён." to { vm.deleteClient(edit.email) }
            }, submit = vm::saveClient)
        }
        state.hostTrust?.let { offered ->
            AlertDialog(onDismissRequest = vm::dismissTrust, title = { Text(if (offered.changed) "SSH-ключ изменился" else "Первое подключение по SSH") },
                text = { Text("${offered.endpoint}\n\n${offered.fingerprint}\n\nСверьте отпечаток с консолью VPS. ${if (offered.changed) "Ранее был сохранён другой ключ. Проверьте причину изменения." else "Приложение запомнит подтверждённый ключ."}") },
                confirmButton = { TextButton(onClick = vm::trustHost) { Text("Отпечаток проверен") } }, dismissButton = { TextButton(onClick = vm::dismissTrust) { Text("Отмена") } })
        }
        confirmation?.let { (text, action) -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Подтвердите действие") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirmation = null; action() }) { Text("Продолжить") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Отмена") } }) }
        state.inboundEditorId?.let { id ->
            val inbound = state.editor
            if (inbound == null) EditorLoadingDialog("Настройки подключения", state.busy, state.message, vm::closeEditor) { vm.openInboundEditor(id) }
            else InboundEditorDialog(inbound, state.busy, state.message, vm::closeEditor, vm::saveInbound)
        }
        if (state.links.isNotEmpty()) LinksDialog(state.links, state.busy, vm::clearLinks, vm::importVpn)
    }
}
