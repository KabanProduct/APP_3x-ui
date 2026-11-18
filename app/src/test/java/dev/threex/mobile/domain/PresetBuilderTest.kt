package dev.threex.mobile.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PresetBuilderTest {
    private val input = QuickSetup(ConnectionPreset.REALITY_VISION, "phone", "alice", "203.0.113.7", 443, "target.example", "target.example:443")
    private val keys = JSONObject().put("privateKey", "private").put("publicKey", "public")
    @Test fun everyRealityCreationGetsNewIdentityAndShortId() {
        val a = PresetBuilder.inbound(input, keys)
        val b = PresetBuilder.inbound(input, keys)
        fun client(j: JSONObject) = j.getJSONObject("settings").getJSONArray("clients").getJSONObject(0)
        assertNotEquals(client(a).getString("id"), client(b).getString("id"))
        assertNotEquals(client(a).getString("subId"), client(b).getString("subId"))
        assertEquals("xtls-rprx-vision", client(a).getString("flow"))
        assertEquals("custom", a.getString("shareAddrStrategy"))
    }
    @Test fun xhttpHasNoVisionAndTlsUsesServerFilePaths() {
        val j = PresetBuilder.inbound(input.copy(preset=ConnectionPreset.XHTTP_TLS, certificate="/etc/cert.pem", certificateKey="/etc/key.pem"))
        assertEquals("", j.getJSONObject("settings").getJSONArray("clients").getJSONObject(0).getString("flow"))
        val stream = j.getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals("/etc/cert.pem", stream.getJSONObject("tlsSettings").getJSONArray("certificates").getJSONObject(0).getString("certificateFile"))
    }
    @Test fun limitsUseBytesAndExpiryUsesMilliseconds() {
        val now = System.currentTimeMillis()
        val c = PresetBuilder.client("alice", limitGiB=5, days=7)
        assertEquals(5L * 1073741824, c.getLong("totalGB"))
        assertTrue(c.getLong("expiryTime") in now + 7*86400000L..System.currentTimeMillis() + 7*86400000L)
        assertEquals(0L, PresetBuilder.client("bob").getLong("expiryTime"))
    }
    @Test fun missingKeysAndInvalidPortAreRejected() {
        try { PresetBuilder.inbound(input); fail() } catch (_: IllegalArgumentException) { }
        try { PresetBuilder.validate(input.copy(port=0)); fail() } catch (_: IllegalArgumentException) { }
    }
}
