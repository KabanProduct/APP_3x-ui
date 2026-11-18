package dev.threex.mobile.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ClientDraftTest {
    private fun inbound(id: Int, network: String = "tcp") = JSONObject().put("id", id).put("protocol", "vless")
        .put("streamSettings", JSONObject().put("network", network).put("security", "reality").toString())
    private val inputs = listOf(inbound(1), inbound(2))
    private fun draft() = ClientDraft(email = "phone", inboundIds = setOf(2, 1), flow = "xtls-rprx-vision")
    @Test fun createsFullPayloadWithMultipleAttachmentsAndByteLimits() {
        val body = draft().copy(limitGiB = "1,5", limitIp = "2", limitHwid = "3", group = "friends", comment = "test", tgId = "12345",
            reset = "30", resetDay = "15", resetMax = "4", trafficReset = "monthly", trafficResetDay = "10", reverseTag = "reverse").payload(inputs)
        assertEquals("[1,2]", body.getJSONArray("inboundIds").toString())
        val c = body.getJSONObject("client")
        assertEquals(1610612736L, c.getLong("totalGB")); assertEquals("xtls-rprx-vision", c.getString("flow"))
        assertEquals(2, c.getInt("limitIp")); assertEquals(3, c.getInt("limitHwid"))
        assertEquals(15, c.getInt("resetDay")); assertEquals(4, c.getInt("resetMax"))
        assertEquals("monthly", c.getString("trafficReset")); assertEquals(10, c.getInt("trafficResetDay"))
        assertEquals("friends", c.getString("group")); assertEquals(12345L, c.getLong("tgId"))
        assertEquals("reverse", c.getJSONObject("reverse").getString("tag"))
    }
    @Test fun encodesUnlimitedRelativeDelayedAndExactExpiry() {
        val now = 1700000000000L
        fun expiry(d: ClientDraft) = d.payload(inputs, now, ZoneId.of("UTC")).getJSONObject("client").getLong("expiryTime")
        assertEquals(0L, expiry(draft()))
        assertEquals(now + 2 * 86400000L, expiry(draft().copy(expiryMode = "days", days = "2")))
        assertEquals(-2 * 86400000L, expiry(draft().copy(expiryMode = "delayed", days = "2")))
        assertEquals(1893456000000L, expiry(draft().copy(expiryMode = "date", expiryDate = "2030-01-01 00:00")))
        assertThrows(IllegalArgumentException::class.java) { expiry(draft().copy(expiryMode = "date", expiryDate = "2030-02-30 00:00")) }
    }
    @Test fun rejectsMissingInboundInvalidUuidAndUnsafeIdentifiers() {
        for (d in listOf(draft().copy(inboundIds = emptySet()), draft().copy(uuid = "1-1-1-1-1"), draft().copy(email = "a/b"), draft().copy(subId = "bad name"))) {
            assertThrows(IllegalArgumentException::class.java) { d.payload(inputs) }
        }
        assertThrows(IllegalStateException::class.java) { draft().copy(inboundIds = setOf(99)).payload(inputs) }
    }
    @Test fun flowMustMatchAllSelectedTransports() {
        val mixed = listOf(inbound(1), inbound(2, "xhttp"))
        assertThrows(IllegalArgumentException::class.java) { draft().payload(mixed) }
        assertEquals("", draft().copy(flow = "").payload(mixed).getJSONObject("client").getString("flow"))
        assertFalse(supportsClientFlow(inbound(1).put("disableFlow", true)))
        assertTrue(supportsClientFlow(inbound(1, "xhttp").put("settings", JSONObject().put("decryption", "mlkem768x25519plus.native.0rtt.key"))))
    }
    @Test fun rejectsNegativeLimitsAndInvalidResetDays() {
        for (d in listOf(draft().copy(limitGiB = "-1"), draft().copy(limitGiB = "99999999999999999999"), draft().copy(limitIp = "-1"), draft().copy(resetDay = "32"), draft().copy(trafficResetDay = "0"))) {
            assertThrows(IllegalArgumentException::class.java) { d.payload(inputs) }
        }
    }
    @Test fun phoneAcceptsVisionUdp443Links() {
        val link = VlessLink.parse("vless://f47ac10b-58cc-4372-a567-0e02b2c3d479@example.com:443?security=tls&type=tcp&sni=example.com&flow=xtls-rprx-vision-udp443")
        assertTrue(link.xrayConfig().contains("xtls-rprx-vision-udp443"))
    }
}
