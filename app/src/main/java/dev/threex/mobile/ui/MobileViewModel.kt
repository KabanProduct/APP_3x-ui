package dev.threex.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.threex.mobile.data.*
import dev.threex.mobile.domain.*
import dev.threex.mobile.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class MobileState(
    val servers: List<ServerProfile> = emptyList(),
    val vpnProfiles: List<VpnProfile> = emptyList(),
    val activeServer: ServerProfile? = null,
    val snapshot: PanelSnapshot? = null,
    val busy: Boolean = false,
    val activity: String = "",
    val message: String? = null,
    val links: List<String> = emptyList(),
    val inspection: InspectionReport? = null,
    val hostTrust: HostTrustRequired? = null,
    val editor: JSONObject? = null,
    val canRecoverInstall: Boolean = false,
    val realityCandidates: List<RealityCandidate> = emptyList(),
    val realityLoading: Boolean = false,
    val realityLoaded: Boolean = false,
    val realityError: String? = null,
)

class MobileViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val ssh = SshProvisioner(application)
    private val mutable = MutableStateFlow(MobileState())
    val state = mutable.asStateFlow()
    private var api: PanelApi? = null
    private var lastSsh: SshCredentials? = null
    init { run("Чтение защищённого хранилища") { reloadStored() } }

    private fun run(label: String, work: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, activity = label, message = null)
        viewModelScope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: HostTrustRequired) { mutable.value = mutable.value.copy(hostTrust = e) }
            catch (e: Exception) {
                val message = when (e) {
                    is PanelException, is SshOperationException, is IllegalArgumentException, is IllegalStateException -> e.message ?: "Операция не выполнена"
                    is javax.net.ssl.SSLException -> "Сертификат HTTPS не прошёл проверку. Проверьте адрес, сертификат и срок его действия."
                    is IOException -> "Сетевая ошибка. Проверьте доступ к серверу. После операции записи обновите данные перед повтором: она могла примениться."
                    else -> "Операция не завершена. Проверьте параметры и доступность сервера."
                }
                mutable.value = mutable.value.copy(message = message)
            } finally { mutable.value = mutable.value.copy(busy = false, activity = "") }
        }
    }
    private suspend fun reloadStored() {
        val root = withContext(Dispatchers.IO) { store.read() }
        mutable.value = mutable.value.copy(
            servers = (root.optJSONArray("servers") ?: JSONArray()).objects().map(ServerProfile::from),
            vpnProfiles = (root.optJSONArray("vpn") ?: JSONArray()).objects().map(VpnProfile::from))
    }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    fun clearLinks() { mutable.value = mutable.value.copy(links = emptyList()) }
    fun closeEditor() { mutable.value = mutable.value.copy(editor = null) }
    fun dismissTrust() { mutable.value = mutable.value.copy(hostTrust = null) }
    fun connect(profile: ServerProfile, otp: String) = run("Вход в панель и загрузка данных") {
        panelBaseUrl(profile.baseUrl)
        require(profile.name.isNotBlank()) { "Введите имя сервера" }
        require(profile.token.isNotBlank() || (profile.username.isNotBlank() && profile.password.isNotBlank())) { "Введите логин и пароль либо API-токен" }
        val candidate = PanelApi(profile)
        candidate.login(otp)
        val snapshot = candidate.snapshot()
        withContext(Dispatchers.IO) { store.saveServer(profile) }
        api = candidate
        mutable.value = mutable.value.copy(activeServer = profile, snapshot = snapshot, realityCandidates = emptyList(), realityLoaded = false, realityLoading = false, realityError = null)
        reloadStored()
    }
    fun disconnectPanel() {
        if (mutable.value.busy) return
        api = null
        mutable.value = mutable.value.copy(activeServer = null, snapshot = null, realityCandidates = emptyList(), realityLoading = false, realityLoaded = false, realityError = null)
    }
    fun forgetServer(profile: ServerProfile) = run("Удаление сохранённого сервера") {
        withContext(Dispatchers.IO) { store.removeServer(profile.id) }
        if (mutable.value.activeServer?.id == profile.id) { api = null; mutable.value = mutable.value.copy(activeServer = null, snapshot = null, realityCandidates = emptyList(), realityLoading = false, realityLoaded = false, realityError = null) }
        reloadStored()
    }
    fun refresh() = run("Обновление панели") { refreshInternal() }
    private suspend fun refreshInternal() { mutable.value = mutable.value.copy(snapshot = requireApi().snapshot()) }
    private fun requireApi(): PanelApi = api ?: error("Сначала подключите панель")
    private fun writable(): PanelApi {
        check(mutable.value.snapshot?.canWrite == true) { "Изменения поддерживаются для 3x-ui 3.7.0. Эта версия доступна только для просмотра." }
        return requireApi()
    }
    private suspend fun mutation(work: suspend (PanelApi) -> Unit) {
        work(writable())
        try { refreshInternal(); mutable.value = mutable.value.copy(message = "Изменения сохранены") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Изменения применены, но обновить список не удалось. Нажмите «Обновить»; не повторяйте создание.") }
    }
    fun toggleInbound(id: Int, enabled: Boolean) = run("Изменение подключения") { mutation { it.setInboundEnabled(id, enabled) } }
    fun deleteInbound(id: Int) = run("Удаление подключения") { mutation { it.post("inbounds", "del", id.toString()) } }
    fun toggleClient(email: String, enabled: Boolean) = run("Изменение доступа клиента") { mutation { it.setClientEnabled(email, enabled) } }
    fun deleteClient(email: String) = run("Удаление клиента") { mutation { it.post("clients", "del", email) } }
    fun clientLinks(email: String) = run("Получение ссылок") {
        val links = requireApi().links(email)
        mutable.value = mutable.value.copy(links = links, message = if (links.isEmpty()) "У клиента пока нет ссылок" else null)
    }
    fun addClient(draft: ClientDraft, onCreated: () -> Unit) = run("Создание клиента") {
        val payload = draft.payload(mutable.value.snapshot?.inbounds.orEmpty())
        val email = payload.getJSONObject("client").getString("email")
        require(mutable.value.snapshot?.clients.orEmpty().none { it.optString("email") == email }) { "Клиент с таким именем уже существует" }
        var disableFailed = false
        mutation { api ->
            api.createClient(payload)
            // 3.7.0 Create forces enable=true. Disable separately after confirmed creation.
            if (!draft.enable) try { api.setClientEnabled(email, false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { disableFailed = true }
        }
        if (disableFailed) mutable.value = mutable.value.copy(message = "Клиент создан, но отключить доступ не удалось. Обновите список и отключите клиента; повторять создание не нужно.")
        onCreated()
    }
    fun loadRealityCandidates(force: Boolean = false) {
        val currentApi = api ?: return
        if (mutable.value.realityLoading || (!force && mutable.value.realityLoaded)) return
        mutable.value = mutable.value.copy(realityLoading = true, realityError = null)
        viewModelScope.launch {
            try {
                val candidates = currentApi.realitySuggestions()
                if (api === currentApi) mutable.value = mutable.value.copy(realityCandidates = candidates, realityLoaded = true,
                    realityError = if (candidates.isEmpty()) "Сервер не нашёл подходящие домены. Повторите проверку или введите свой target/SNI." else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (api === currentApi) mutable.value = mutable.value.copy(realityLoaded = true, realityError =
                    (e as? PanelException)?.message ?: "Не удалось получить домены от панели. Повторите запрос или заполните поля вручную.")
            } finally { if (api === currentApi) mutable.value = mutable.value.copy(realityLoading = false) }
        }
    }
    fun editClientLimits(email: String, limit: Long, days: Int) = run("Изменение лимитов клиента") {
        require(limit in 0..1_000_000 && days in 0..36500) { "Некорректный лимит или срок" }
        mutation {
            val hydrated = it.get("clients", "get", email) as? JSONObject ?: error("Не удалось получить клиента")
            val client = JSONObject(hydrated.getJSONObject("client").toString())
            require(client.optString("uuid").isNotBlank()) { "Редактор лимитов поддерживает клиентов VLESS" }
            client.put("id", client.getString("uuid"))
            client.put("totalGB", Math.multiplyExact(limit, 1073741824L))
            if (days > 0) client.put("expiryTime", System.currentTimeMillis() + days * 86400000L)
            // Zero days means keep expiry when editing, unlike new-client creation.
            it.post("clients", "update", email, body = client)
        }
    }
    fun openInboundEditor(id: Int) = run("Загрузка настроек подключения") {
        mutable.value = mutable.value.copy(editor = writable().get("inbounds", "get", id.toString()) as? JSONObject ?: error("Неизвестный формат подключения"))
    }
    fun saveInbound(id: Int, source: String) = run("Сохранение настроек") {
        val inbound = try { JSONObject(source) } catch (_: Exception) { error("Некорректный JSON") }
        require(inbound.optInt("id") == id) { "Идентификатор подключения изменять нельзя" }
        mutation { it.post("inbounds", "update", id.toString(), body = inbound) }
        mutable.value = mutable.value.copy(editor = null)
    }
    fun quickSetup(input: QuickSetup) = run("Проверка и создание VLESS") {
        PresetBuilder.validate(input)
        val api = writable()
        refreshInternal()
        require(mutable.value.snapshot!!.inbounds.none { it.optInt("port") == input.port }) { "Этот порт уже используется подключением панели" }
        require(mutable.value.snapshot!!.clients.none { it.optString("email") == input.clientName }) { "Клиент с таким именем уже существует" }
        var keys: JSONObject? = null
        if (input.preset != ConnectionPreset.XHTTP_TLS) {
            val scan = api.form("server", "scanRealityTarget", values = mapOf("target" to input.target, "sni" to input.sni, "xver" to "0", "allowPrivate" to "false")) as? JSONObject
            require(scan?.optBoolean("feasible") == true) { "Сервер не подтвердил пригодность REALITY target. Выберите другой домен/SNI." }
            keys = api.get("server", "getNewX25519Cert") as? JSONObject
        }
        val inbound = PresetBuilder.inbound(input, keys)
        mutation { it.post("inbounds", "add", body = inbound) }
    }
    fun importVpn(source: String) = run("Сохранение VPN-профиля") {
        val parsed = VlessLink.parse(source)
        withContext(Dispatchers.IO) { store.saveVpn(VpnProfile(name = parsed.name, uri = source.trim())) }
        reloadStored()
        mutable.value = mutable.value.copy(message = "VPN-профиль сохранён. Откройте вкладку VPN для подключения.")
    }
    fun removeVpn(id: String) = run("Удаление VPN-профиля") { withContext(Dispatchers.IO) { store.removeVpn(id) }; reloadStored() }
    fun inspect(c: SshCredentials) = run("Проверка VPS через SSH") {
        lastSsh = c
        mutable.value = mutable.value.copy(inspection = null)
        val pin = withContext(Dispatchers.IO) { store.hostKey(c.endpoint) }
        val report = ssh.inspect(c, pin)
        mutable.value = mutable.value.copy(inspection = report, canRecoverInstall = withContext(Dispatchers.IO) { store.pendingInstallation(c.endpoint) != null })
    }
    fun trustHost() = run("Сохранение SSH host key") {
        val offered = mutable.value.hostTrust ?: return@run
        withContext(Dispatchers.IO) { store.trustHost(offered.endpoint, offered.fingerprint) }
        mutable.value = mutable.value.copy(hostTrust = null)
        val c = lastSsh ?: return@run
        mutable.value = mutable.value.copy(inspection = ssh.inspect(c, offered.fingerprint), canRecoverInstall = withContext(Dispatchers.IO) { store.pendingInstallation(c.endpoint) != null })
    }
    fun install(publicHost: String, port: Int) = run("Установка 3x-ui 3.7.0 · не закрывайте приложение") {
        val c = lastSsh ?: error("Сначала выполните проверку VPS")
        val pin = withContext(Dispatchers.IO) { store.hostKey(c.endpoint) }
        val profile = ssh.install(c, pin, publicHost, port) { draft -> withContext(Dispatchers.IO) { store.prepareInstallation(c.endpoint, draft) } }
        withContext(Dispatchers.IO) { store.finishInstallation(c.endpoint, profile) }
        reloadStored()
        mutable.value = mutable.value.copy(inspection = null, message = "3x-ui установлен, данные входа сохранены. Откройте сервер во вкладке «Панель». Если он недоступен, разрешите входящий TCP-порт $port в firewall/VPS-панели.")
    }
    fun recoverInstallation() = run("Восстановление данных после прерванной установки") {
        val c = lastSsh ?: error("Повторите SSH-проверку")
        val draft = withContext(Dispatchers.IO) { store.pendingInstallation(c.endpoint) } ?: error("Нет незавершённой установки")
        val pin = withContext(Dispatchers.IO) { store.hostKey(c.endpoint) }
        val profile = ssh.recover(c, pin, draft)
        withContext(Dispatchers.IO) { store.finishInstallation(c.endpoint, profile) }
        reloadStored()
        mutable.value = mutable.value.copy(canRecoverInstall = false, message = "Данные входа восстановлены. Подключите панель из списка серверов. Если установка не завершилась, потребуется диагностика через SSH.")
    }
}
