package dev.threex.mobile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ActivityScenario
import dev.threex.mobile.data.SecureStore
import dev.threex.mobile.data.ServerProfile
import dev.threex.mobile.data.VpnProfile
import dev.threex.mobile.data.objects
import dev.threex.mobile.domain.VlessLink
import dev.threex.mobile.vpn.PhoneVpnService
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keystoreVaultRoundTripDoesNotStorePlaintext() {
        val store = SecureStore(context)
        val server = ServerProfile(name="instrumentation", baseUrl="https://example.com/", password="test-secret-93b61")
        store.saveServer(server)
        try {
            val read = store.read().getJSONArray("servers").objects().first { it.getString("id") == server.id }
            assertEquals(server.password, read.getString("password"))
            val raw = File(context.noBackupFilesDir, "vault.bin").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains(server.password))
            store.prepareInstallation("test:22", server)
            assertEquals(server.password, SecureStore(context).pendingInstallation("test:22")!!.password)
            store.finishInstallation("test:22", server)
            assertNull(store.pendingInstallation("test:22"))
        } finally { store.removeServer(server.id) }
    }
    @Test fun nativeCoreLoadsAndAcceptsSupportedOutboundConfigurations() {
        go.Seq.setContext(context)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        assertTrue(Libv2ray.checkVersionX().contains("Xray-core"))
        val base = "vless://f47ac10b-58cc-4372-a567-0e02b2c3d479@127.0.0.1:8443?"
        val queries = listOf(
            "security=reality&type=tcp&sni=example.com&pbk=${"A".repeat(43)}&sid=ab12&flow=xtls-rprx-vision",
            "security=reality&type=xhttp&sni=example.com&pbk=${"A".repeat(43)}&sid=ab12&path=%2Ftest",
            "security=tls&type=xhttp&sni=example.com&path=%2Ftest",
        )
        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String?): Long = 0
        })
        queries.forEach { query ->
            // No network connections and no TUN: exercise the real Android native parser/lifecycle.
            val config = JSONObject(VlessLink.parse(base + query).xrayConfig()).put("inbounds", JSONArray())
            try { controller.startLoop(config.toString(), 0); assertTrue(controller.isRunning) }
            finally { controller.stopLoop() }
            assertFalse(controller.isRunning)
        }
    }
    @Test fun appLaunchesAndAllPrimaryTabsOpen() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Личный VPN")), 15_000))
        // Screenshots only in this empty, test-owned emulator; production keeps FLAG_SECURE.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                    it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
        }
        for ((tab, heading) in listOf("Панель" to "Управление сервером", "Мастер" to "Быстрая настройка VLESS", "VPS" to "Подготовка сервера", "Версия" to "Обновления приложения", "VPN" to "Личный VPN")) {
            device.findObject(By.text(tab)).click()
            assertTrue("Tab $tab did not open", device.wait(Until.hasObject(By.text(heading)), 5_000))
            device.waitForIdle()
            device.takeScreenshot(File(context.getExternalFilesDir(null), "screen-${listOf("VPN", "Панель", "Мастер", "VPS", "Версия").indexOf(tab)}.png"))
        }
    }
    @Test fun vpnPermissionTunAndStopWorkOnAndroid() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(device.wait(Until.hasObject(By.text("Личный VPN")), 15_000))
        android.net.VpnService.prepare(context)?.let { permission ->
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.startActivityForResult(permission, 7001)
            }
            val accept = device.wait(Until.findObject(By.res("android:id/button1")), 10_000)
            assertNotNull("VPN consent dialog missing", accept)
            accept.click()
        }
        // A click is asynchronous; the real UI waits for ActivityResult before starting.
        val consentDeadline = System.currentTimeMillis() + 5_000
        while (android.net.VpnService.prepare(context) != null && System.currentTimeMillis() < consentDeadline) Thread.sleep(100)
        assertNull("Android has not granted VPN consent", android.net.VpnService.prepare(context))
        val fixture = InstrumentationRegistry.getArguments().getString("vlessUri")
            ?: "vless://f47ac10b-58cc-4372-a567-0e02b2c3d479@127.0.0.1:9?security=tls&type=tcp&sni=example.com"
        val profile = VpnProfile(name="Device test", uri=fixture)
        val store = SecureStore(context)
        store.saveVpn(profile)
        try {
            context.startForegroundService(Intent(context, PhoneVpnService::class.java).putExtra("profileId", profile.id))
            val deadline = System.currentTimeMillis() + 35_000
            while (System.currentTimeMillis() < deadline && !PhoneVpnService.status.value.phase.let { it.startsWith("Туннель") || it.startsWith("Подключён") }) {
                if (PhoneVpnService.lastFailure != null) break
                Thread.sleep(200)
            }
            assertTrue("TUN failed: ${PhoneVpnService.status.value.phase}; ${PhoneVpnService.lastFailure?.stackTraceToString()}", PhoneVpnService.status.value.running)
            if (InstrumentationRegistry.getArguments().getString("requireConnectivity") == "true") {
                assertTrue("Xray proxy connectivity failed", PhoneVpnService.status.value.phase.startsWith("Подключён"))
            }
        } finally {
            context.startService(Intent(context, PhoneVpnService::class.java).setAction(PhoneVpnService.STOP))
            val deadline = System.currentTimeMillis() + 20_000
            while (PhoneVpnService.status.value.running && System.currentTimeMillis() < deadline) Thread.sleep(100)
            store.removeVpn(profile.id)
        }
        assertFalse("VPN did not stop", PhoneVpnService.status.value.running)
        scenario.close()
    }
}
