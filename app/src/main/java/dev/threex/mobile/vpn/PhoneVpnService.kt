package dev.threex.mobile.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dev.threex.mobile.MainActivity
import dev.threex.mobile.data.SecureStore
import dev.threex.mobile.data.VpnProfile
import dev.threex.mobile.data.objects
import dev.threex.mobile.domain.VlessLink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray

data class VpnStatus(val phase: String = "Отключён", val profileId: String? = null, val running: Boolean = false, val delayMs: Long? = null, val uploaded: Long = 0, val downloaded: Long = 0)

class PhoneVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var core: CoreController? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var monitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "VPN-подключение", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            monitor?.cancel()
            scope.launch { lock.withLock { release(); mutable.value = VpnStatus(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        } else {
            startForeground(1, notification("Подготовка VPN"))
            val id = intent?.getStringExtra("profileId")
            scope.launch { lock.withLock {
                var stage = "чтение профиля"
                try {
                    lastFailure = null
                    require(!id.isNullOrBlank()) { "Не выбран профиль" }
                    if (mutable.value.running && mutable.value.profileId == id) return@withLock
                    release()
                    val root = SecureStore(this@PhoneVpnService).read()
                    val profile = (root.optJSONArray("vpn") ?: JSONArray()).objects().map(VpnProfile::from).first { it.id == id }
                    val config = VlessLink.parse(profile.uri).xrayConfig()
                    mutable.value = VpnStatus("Подключение…", id, true)
                    stage = "разрешение Android"
                    check(prepare(this@PhoneVpnService) == null) { "Разрешение VPN отозвано" }
                    val builder = Builder().setSession(profile.name).setMtu(1500)
                        .addAddress("10.77.0.1", 30).addRoute("0.0.0.0", 0)
                        .addAddress("fd77:77::1", 126).addRoute("::", 0)
                        .addDnsServer("1.1.1.1").addDnsServer("2606:4700:4700::1111")
                    // Xray and management sockets must stay outside their own TUN.
                    // All other apps, including their DNS and IPv6 traffic, enter it.
                    builder.addDisallowedApplication(packageName)
                    stage = "создание TUN"
                    tunnel = builder.establish() ?: error("Android не создал VPN-интерфейс")
                    stage = "запуск Xray"
                    go.Seq.setContext(applicationContext)
                    Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                    core = Libv2ray.newCoreController(object : CoreCallbackHandler {
                        override fun startup(): Long = 0
                        override fun shutdown(): Long = 0
                        override fun onEmitStatus(code: Long, message: String?): Long = 0
                    })
                    core!!.startLoop(config, tunnel!!.fd)
                    monitor = scope.launch { monitorConnection(id) }
                } catch (_: CancellationException) { throw CancellationException() }
                catch (e: Exception) {
                    lastFailure = e
                    release()
                    mutable.value = VpnStatus("Не удалось запустить VPN: $stage. Проверьте профиль и разрешение Android.")
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            } }
        }
        return START_NOT_STICKY
    }
    private suspend fun monitorConnection(id: String) {
        while (currentCoroutineContext().isActive) {
            lock.withLock {
                val current = core ?: return
                val delay = runCatching { current.measureDelay("https://www.gstatic.com/generate_204") }.getOrNull()
                currentCoroutineContext().ensureActive()
                var up = mutable.value.uploaded
                var down = mutable.value.downloaded
                runCatching { current.queryAllOutboundTrafficStats() }.getOrDefault("").split(';').forEach {
                    val parts = it.split(',')
                    if (parts.size == 3 && parts[0] == "proxy") {
                        if (parts[1] == "uplink") up += parts[2].toLongOrNull() ?: 0
                        if (parts[1] == "downlink") down += parts[2].toLongOrNull() ?: 0
                    }
                }
                val phase = if (delay != null && delay >= 0) "Подключён · проверка через Xray пройдена" else "Туннель запущен · интернет не подтверждён"
                mutable.value = VpnStatus(phase, id, true, delay, up, down)
                getSystemService(NotificationManager::class.java).notify(1, notification(phase))
            }
            delay(30_000)
        }
    }
    private fun release() {
        monitor?.cancel(); monitor = null
        runCatching { core?.stopLoop() }; core = null
        runCatching { tunnel?.close() }; tunnel = null
    }
    override fun onRevoke() { onStartCommand(Intent(this, PhoneVpnService::class.java).setAction(STOP), 0, 0) }
    override fun onDestroy() {
        monitor?.cancel()
        scope.launch { lock.withLock { release(); if (mutable.value.running) mutable.value = VpnStatus() }; scope.cancel() }
        super.onDestroy()
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("3x Mobile")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
    }
    companion object {
        const val STOP = "dev.threex.mobile.STOP_VPN"
        private const val CHANNEL = "vpn"
        private val mutable = MutableStateFlow(VpnStatus())
        val status = mutable.asStateFlow()
        @Volatile internal var lastFailure: Throwable? = null
    }
}
