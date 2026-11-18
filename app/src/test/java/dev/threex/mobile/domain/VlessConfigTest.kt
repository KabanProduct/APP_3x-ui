package dev.threex.mobile.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VlessConfigTest {
    private val base = "vless://f47ac10b-58cc-4372-a567-0e02b2c3d479@example.com:443?security=reality&sni=target.example&pbk=${"A".repeat(43)}&sid=ab12"
    @Test fun realityVisionKeepsClientParametersAndHasNoDirectFallback() {
        val config = JSONObject(VlessLink.parse("$base&type=tcp&flow=xtls-rprx-vision#Phone").xrayConfig())
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals(1, outbounds.length())
        val outbound = outbounds.getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        val reality = outbound.getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("target.example", reality.getString("serverName"))
        assertEquals("ab12", reality.getString("shortId"))
        assertFalse(config.toString().contains("privateKey"))
    }
    @Test fun xhttpPreservesEncodedPathAndIpv6Address() {
        val uri = base.replace("example.com:443", "[2001:db8::1]:443") + "&type=xhttp&path=%2Fa%2Bb&mode=auto#My%20phone"
        val link = VlessLink.parse(uri)
        assertEquals("2001:db8::1", link.host)
        assertEquals("My phone", link.name)
        val stream = JSONObject(link.xrayConfig()).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("/a+b", stream.getJSONObject("xhttpSettings").getString("path"))
    }
    @Test fun unsafeOrUnsupportedVariantsFailInsteadOfSilentlyChangingMeaning() {
        val bad = listOf(base + "&type=ws", base + "&type=xhttp&flow=xtls-rprx-vision", base + "&allowInsecure=1", base + "&security=tls", base.replace("sid=ab12", "sid=xyz"), base.replace("security=reality", "security=none"), base + "&extra=%7B%7D")
        bad.forEach { try { VlessLink.parse(it); fail("Must reject unsupported input") } catch (_: IllegalArgumentException) { } }
    }
    @Test fun tlsAlwaysChecksCertificates() {
        val link = VlessLink.parse("vless://f47ac10b-58cc-4372-a567-0e02b2c3d479@example.com:443?security=tls&type=xhttp&sni=example.com")
        val tls = JSONObject(link.xrayConfig()).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertFalse(tls.getBoolean("allowInsecure"))
    }
    @Test fun panelGeneratedXhttpExtraIsPreserved() {
        val link = VlessLink.parse(base + "&type=xhttp&extra=%7B%22mode%22%3A%22auto%22%2C%22xPaddingBytes%22%3A%22100-1000%22%7D")
        val xhttp = JSONObject(link.xrayConfig()).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        assertEquals("auto", xhttp.getString("mode"))
        assertEquals("100-1000", xhttp.getJSONObject("extra").getString("xPaddingBytes"))
    }
}
