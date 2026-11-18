package dev.threex.mobile.ssh

import android.content.Context
import com.jcraft.jsch.*
import dev.threex.mobile.data.ServerProfile
import dev.threex.mobile.domain.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class SshCredentials(val host: String, val port: Int, val username: String, val password: String, val privateKey: String = "", val passphrase: String = "") {
    val endpoint: String get() = "[$host]:$port"
    fun validate() {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/?#@" }) { "Укажите SSH-хост без схемы и порта" }
        require(port in 1..65535 && username.isNotBlank()) { "Укажите SSH-порт и пользователя" }
        require(password.isNotEmpty() || privateKey.isNotBlank()) { "Укажите пароль или приватный SSH-ключ" }
    }
}
class HostTrustRequired(val endpoint: String, val fingerprint: String, val changed: Boolean) : Exception("Подтвердите SSH host key")
class SshOperationException(message: String) : Exception(message)
data class InspectionReport(val inspection: ServerInspection, val os: String, val architecture: String, val freeDiskKb: Long) {
    companion object {
        fun parse(output: String): InspectionReport {
            val lines = output.lineSequence().filter { it.startsWith("__3XM_") }.map { it.removePrefix("__3XM_").split('=', limit=2) }.filter { it.size == 2 }.toList()
            fun values(key: String) = lines.filter { it[0] == key }.map { it[1] }
            fun last(key: String) = values(key).lastOrNull().orEmpty()
            val panel = if ("PRESENT" in values("PANEL")) PanelPresence.PRESENT else if (last("PANEL") == "ABSENT") PanelPresence.ABSENT else PanelPresence.UNKNOWN
            val disk = last("DISK_KB").toLongOrNull() ?: 0
            val os = last("OS")
            val version = last("OSVERSION").substringBefore('.').toIntOrNull() ?: 0
            val arch = last("ARCH")
            val supported = ((os == "ubuntu" && version >= 22) || (os == "debian" && version >= 12)) && arch in listOf("x86_64", "aarch64") && last("SYSTEMD") == "1"
            val conflicts = values("PORT").distinct().map { "Порт $it уже используется" }.toMutableList()
            if (disk < 2L * 1024 * 1024) conflicts += "Для установки требуется минимум 2 ГиБ свободного места"
            return InspectionReport(ServerInspection(panel, last("COMPLETE") == "1" && last("UID").isNotBlank() && os.isNotBlank() && arch.isNotBlank(), supported, last("UID") == "0", conflicts, values("WORKLOAD").distinct()), "$os ${last("OSVERSION")}", arch, disk)
        }
    }
}

class SshProvisioner(private val context: Context) {
    suspend fun inspect(credentials: SshCredentials, trusted: String?): InspectionReport = withContext(Dispatchers.IO) {
        connect(credentials, trusted).useSession { session -> inspect(session, credentials.port) }
    }
    private suspend fun inspect(session: Session, sshPort: Int): InspectionReport {
        val script = context.assets.open("server-inspect.sh").bufferedReader().use { it.readText() }
        return InspectionReport.parse(exec(session, "sh -s -- $sshPort", script, 45_000))
    }
    suspend fun install(credentials: SshCredentials, trusted: String?, publicHost: String, panelPort: Int, prepared: suspend (ServerProfile) -> Unit): ServerProfile = withContext(Dispatchers.IO) {
        require(publicHost.matches(Regex("[A-Za-z0-9.-]+"))) { "Для установки укажите IPv4 или домен (IPv6 поддерживается для существующей панели)" }
        require(panelPort in 1024..65535 && panelPort != credentials.port) { "Укажите отдельный порт панели от 1024 до 65535" }
        connect(credentials, trusted).useSession { session ->
            val report = inspect(session, credentials.port)
            check(report.inspection.decide() == SetupDecision.OfferInstallation) { "Повторная проверка: сервер не подходит для чистой установки" }
            val username = "admin_${random().take(8)}"
            val password = random() + random().take(8)
            val basePath = random().take(24)
            val draft = ServerProfile(name = publicHost, baseUrl = "https://$publicHost:$panelPort/$basePath/", username = username, password = password)
            // Persist before any server mutation, so an SSH drop cannot lose access credentials.
            prepared(draft)
            val arch = if (report.architecture == "x86_64") "amd64" else "arm64"
            val checksum = if (arch == "amd64") "0f8dd7baef3458f6591574e24814f322cf7f5e1e27f0a594683745e50be84ec5" else "3caf1db1e8b10bb1fa1324c945522690bcf01c533ee75b377268f1c01a3ce896"
            val san = if (publicHost.matches(Regex("[0-9.]+"))) "IP:$publicHost" else "DNS:$publicHost"
            val script = context.assets.open("server-install.sh").bufferedReader().use { it.readText() }
                .replace("@@ARCH@@", arch).replace("@@SHA256@@", checksum).replace("@@USER@@", username)
                .replace("@@PASSWORD@@", password).replace("@@BASE@@", basePath).replace("@@HOST@@", publicHost)
                .replace("@@SAN@@", san).replace("@@PORT@@", panelPort.toString())
            val output = exec(session, "bash -s", script, 600_000)
            val pin = output.lineSequence().firstOrNull { it.startsWith("__3XM_CERT=") }?.substringAfter('=')
                ?: throw SshOperationException("Нет подтверждения установки. Повторите проверку сервера; не переустанавливайте панель.")
            require(pin.matches(Regex("[0-9a-f]{64}"))) { "Некорректный отпечаток сертификата" }
            ServerProfile(id = draft.id, name = publicHost, baseUrl = draft.baseUrl, username = username, password = password, certificatePin = pin)
        }
    }
    suspend fun recover(credentials: SshCredentials, trusted: String?, draft: ServerProfile): ServerProfile = withContext(Dispatchers.IO) {
        connect(credentials, trusted).useSession { session ->
            val output = exec(session, "bash -s", "set -euo pipefail\nopenssl x509 -in /etc/x-ui/mobile-panel.crt -outform DER | sha256sum | cut -d' ' -f1\n", 20_000).trim()
            require(output.matches(Regex("[0-9a-f]{64}"))) { "Сертификат установки ещё не найден" }
            ServerProfile(draft.id, draft.name, draft.baseUrl, draft.username, draft.password, draft.token, output)
        }
    }
    private fun connect(c: SshCredentials, trusted: String?): Session {
        c.validate()
        var offered: String? = null
        val jsch = JSch()
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray): Int {
                val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                offered = fingerprint
                return if (fingerprint == trusted) HostKeyRepository.OK else if (trusted == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "3x Mobile"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        if (c.privateKey.isNotBlank()) jsch.addIdentity("mobile", c.privateKey.toByteArray(), null, c.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
        val session = jsch.getSession(c.username, c.host, c.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (c.privateKey.isNotBlank()) "publickey" else "password")
        session.setPassword(c.password)
        session.timeout = 20_000
        session.serverAliveInterval = 15_000
        try { session.connect(20_000); return session }
        catch (_: JSchException) {
            session.disconnect()
            if (offered != null && offered != trusted) throw HostTrustRequired(c.endpoint, offered!!, trusted != null)
            throw SshOperationException("Не удалось войти по SSH. Проверьте адрес, доступность порта и учётные данные.")
        }
    }
    private suspend fun exec(session: Session, command: String, stdin: String, timeout: Long): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        val output = ByteArrayOutputStream()
        val input = channel.inputStream
        channel.setErrStream(object : java.io.OutputStream() { override fun write(value: Int) = Unit })
        try {
            channel.connect(15_000)
            withTimeout(timeout) {
                val buffer = ByteArray(8192)
                while (true) {
                    while (input.available() > 0) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 2_000_000) throw SshOperationException("Ответ SSH превысил допустимый размер")
                        output.write(buffer, 0, count)
                    }
                    if (channel.isClosed && input.available() == 0) break
                    delay(50)
                }
            }
            if (channel.exitStatus != 0) throw SshOperationException("Команда на сервере завершилась с ошибкой (${channel.exitStatus}). Повторите диагностику; изменения могли примениться частично.")
            return output.toString(Charsets.UTF_8.name())
        } finally { channel.disconnect() }
    }
    private suspend fun <T> Session.useSession(block: suspend (Session) -> T): T = try { block(this) } finally { disconnect() }
    private fun random() = UUID.randomUUID().toString().replace("-", "")
}
