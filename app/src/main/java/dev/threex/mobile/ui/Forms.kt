package dev.threex.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.threex.mobile.data.*
import dev.threex.mobile.domain.*
import dev.threex.mobile.ssh.SshCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun ServerDialog(existing: ServerProfile?, busy: Boolean, onDismiss: () -> Unit, submit: (ServerProfile, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }; var url by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }; var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }; var otp by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(existing?.certificatePin.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog("Подключить панель", onDismiss, {
        try {
            panelBaseUrl(url); require(name.isNotBlank()) { "Введите имя сервера" }
            val cleanPin = pin.replace(":", "").trim().lowercase()
            require(cleanPin.isEmpty() || cleanPin.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 сертификата должен содержать 64 шестнадцатеричных символа" }
            submit(ServerProfile(existing?.id ?: java.util.UUID.randomUUID().toString(), name, url, username, password, token, cleanPin), otp)
        } catch (e: Exception) { error = e.message ?: "Проверьте адрес" }
    }, "Войти и сохранить", !busy) {
        Field("Название", name, { name = it }); Field("https://server:port/secret-path/", url, { url = it })
        Field("Логин", username, { username = it }); Field("Пароль", password, { password = it }, secret = true)
        Field("Код 2FA, если включён", otp, { otp = it }, numeric = true)
        Text("Вместо логина и пароля можно использовать API-токен:")
        Field("API-токен", token, { token = it }, secret = true)
        Field("SHA-256 сертификата · необязательно", pin, { pin = it })
        Text("Для самоподписанного сертификата сверенный SHA-256 можно получить через SSH. Оставьте поле пустым для обычной проверки HTTPS.")
        if (existing?.certificatePin?.isNotBlank() == true) Text("Сертификат этой панели закреплён через SSH.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
@Composable internal fun ImportDialog(onDismiss: () -> Unit, submit: (String) -> Unit) {
    var uri by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    FormDialog("Импорт VPN-профиля", onDismiss, { try { VlessLink.parse(uri); submit(uri) } catch (e: Exception) { error = e.message } }, "Сохранить") {
        Field("vless://…", uri, { uri = it }, multiline = true)
        Text("TCP/RAW + REALITY/TLS и XHTTP + REALITY/TLS.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
@Composable internal fun QuickSetupForm(state: MobileState, loadCandidates: (Boolean) -> Unit, submit: (QuickSetup) -> Unit) {
    var preset by rememberSaveable { mutableStateOf(ConnectionPreset.REALITY_VISION) }
    var name by rememberSaveable { mutableStateOf("Мой VLESS") }; var client by rememberSaveable { mutableStateOf("phone") }
    var host by rememberSaveable(state.activeServer?.id, state.activeServer?.baseUrl) { mutableStateOf(suggestedPublicHost(state.activeServer?.baseUrl)) }; var port by rememberSaveable { mutableStateOf("443") }
    var sni by rememberSaveable(state.activeServer?.id, preset) { mutableStateOf("") }; var target by rememberSaveable(state.activeServer?.id, preset) { mutableStateOf("") }
    val candidates = if (state.activeServer != null) state.realityCandidates else emptyList()
    LaunchedEffect(state.activeServer?.id, preset) {
        if (state.activeServer != null && preset != ConnectionPreset.XHTTP_TLS) loadCandidates(false)
    }
    LaunchedEffect(candidates, preset) {
        if (preset != ConnectionPreset.XHTTP_TLS && sni.isBlank() && target.isBlank()) candidates.randomOrNull()?.let { sni = it.sni; target = it.target }
    }
    var cert by rememberSaveable { mutableStateOf("") }; var key by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Сервер: ${state.activeServer?.name ?: "сначала войдите во вкладке «Панель»"}")
    ConnectionPreset.entries.forEach { option -> FilterChip(preset == option, onClick = { preset = option }, label = { Text(option.title) }) }
    Text(preset.description)
    Field("Имя подключения", name, { name = it }); Field("Имя клиента", client, { client = it })
    Field("Публичный IP или домен VPS", host, { host = it }); Field("Порт подключения", port, { port = it }, numeric = true)
    Text("Адрес взят из подключения к панели. Если VPN использует другой адрес, измените его.", style = MaterialTheme.typography.bodySmall)
    Field("SNI", sni, { sni = it })
    if (preset == ConnectionPreset.XHTTP_TLS) {
        Field("Путь сертификата на VPS", cert, { cert = it }); Field("Путь приватного ключа на VPS", key, { key = it })
        Text("Домен должен указывать на VPS. Сертификат и его продление настраиваются отдельно; мастер создаёт inbound.")
    } else {
        Field("REALITY target · domain:443", target, { target = it })
        if (state.realityLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Сервер проверяет REALITY-домены…") }
        state.realityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(enabled = candidates.isNotEmpty(), onClick = { expanded = true }) { Text("Выбрать домен с сервера (${candidates.size})") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                candidates.forEach { candidate -> DropdownMenuItem(text = { Text("${candidate.target} · ${candidate.latencyMs} мс") }, onClick = { sni = candidate.sni; target = candidate.target; expanded = false }) }
            }
        }
        Actions {
            TextButton(enabled = candidates.isNotEmpty(), onClick = { (candidates.filter { it.target != target }.ifEmpty { candidates }).randomOrNull()?.let { sni = it.sni; target = it.target } }) { Text("Случайный домен") }
            TextButton(enabled = state.activeServer != null && !state.realityLoading, onClick = { loadCandidates(true) }) { Text("Проверить заново") }
        }
        Text("Панель проверит пригодность target со стороны VPS. Единого домена, работающего во всех сетях, нет.")
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !state.busy && state.snapshot?.canWrite == true, modifier = Modifier.fillMaxWidth(), onClick = {
        try {
            val input = QuickSetup(preset, name, client, host.trim(), port.toIntOrNull() ?: 0, sni.trim(), target.trim(), cert, key)
            PresetBuilder.validate(input); error = null; submit(input)
        } catch (e: Exception) { error = e.message }
    }) { Text("Проверить и создать") }
    Text("Будут созданы новые ключи и клиент. Порты проверяются по данным панели; firewall и другие службы могут потребовать отдельной настройки.", style = MaterialTheme.typography.bodySmall)
}
@Composable internal fun SshForm(state: MobileState, vm: MobileViewModel, install: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }; var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }; var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }; var phrase by remember { mutableStateOf("") }
    var publicHost by remember { mutableStateOf("") }; var panelPort by remember { mutableStateOf("2053") }
    var checkedInput by remember { mutableStateOf<List<String>?>(null) }
    val inputSignature = listOf(host, port, user, password, privateKey, phrase)
    Field("SSH-хост", host, { host = it }, enabled = !state.busy); Field("SSH-порт", port, { port = it }, numeric = true, enabled = !state.busy)
    Field("SSH-пользователь", user, { user = it }, enabled = !state.busy); Field("Пароль SSH", password, { password = it }, secret = true, enabled = !state.busy)
    Field("Или приватный SSH-ключ (PEM/OpenSSH)", privateKey, { privateKey = it }, secret = true, multiline = true, enabled = !state.busy)
    Field("Пароль ключа, если есть", phrase, { phrase = it }, secret = true, enabled = !state.busy)
    Button(enabled = !state.busy, onClick = { checkedInput = inputSignature; vm.inspect(SshCredentials(host.trim(), port.toIntOrNull() ?: 0, user.trim(), password, privateKey, phrase)) }) { Text("Проверить сервер") }
    Text("SSH-данные используются только в этой сессии. Для установки: Ubuntu 22+ или Debian 12+, systemd, root и x86_64/arm64.", style = MaterialTheme.typography.bodySmall)
    if (checkedInput == inputSignature) state.inspection?.let { report ->
        AppCard("${report.os} · ${report.architecture}") {
            Text("Свободно: ${bytes(report.freeDiskKb * 1024)}")
            if (state.canRecoverInstall) TextButton(enabled = !state.busy, onClick = vm::recoverInstallation) { Text("Восстановить данные прерванной установки") }
            when (val decision = report.inspection.decide()) {
                SetupDecision.ConnectExisting -> Text("3x-ui уже найден. Установка не требуется. Подключите его HTTPS-адрес во вкладке «Панель».")
                SetupDecision.OfferInstallation -> {
                    Text("Проверка завершена: конфликтов не обнаружено. Можно установить панель.")
                    Field("Публичный IPv4 или домен панели", publicHost, { publicHost = it }); Field("HTTPS-порт панели", panelPort, { panelPort = it }, numeric = true)
                    Button(enabled = !state.busy, onClick = { install(publicHost.trim(), panelPort.toIntOrNull() ?: 0) }) { Text("Установить 3x-ui 3.7.0") }
                }
                is SetupDecision.ReviewRequired -> { Text("Автоматическая установка не предлагается:"); decision.reasons.forEach { Text("• $it") } }
            }
        }
    }
}
@Composable internal fun LinksDialog(links: List<String>, busy: Boolean, dismiss: () -> Unit, import: (String) -> Unit) {
    var selected by remember(links) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val link = links[selected]
    val qr by produceState<Bitmap?>(initialValue = null, link) {
        value = withContext(Dispatchers.Default) { runCatching {
            val matrix = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 600, 600)
            Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(600 * 600) { index -> if (matrix[index % 600, index / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, 600, 0, 0, 600, 600)
            }
        }.getOrNull() }
    }
    FormDialog("Ссылка доступа ${selected + 1}/${links.size}", dismiss, {
        val clip = ClipData.newPlainText("VLESS", link)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }, "Копировать") {
        if (links.size > 1) Actions { links.indices.forEach { i -> FilterChip(selected == i, onClick = { selected = i }, label = { Text("${i + 1}") }) } }
        qr?.let { Image(it.asImageBitmap(), "QR-код доступа", modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
        Text(link, style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && link.startsWith("vless://"), onClick = { import(link) }) { Text("Сохранить для VPN") }
        TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Поделиться доступом")) }) { Text("Поделиться…") }
    }
}
