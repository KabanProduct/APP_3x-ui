package dev.threex.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PanelApiTest {
    private fun server() = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    private fun MockWebServer.localUrl(path: String) = url(path).newBuilder().host("127.0.0.1").build().toString()
    private fun response(obj: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"success\":true,\"obj\":$obj}")

    @Test fun loginKeepsBasePathCookiesAndRefreshesCsrf() = runBlocking {
        server().use { server ->
            server.enqueue(response("\"before\"").setHeader("Set-Cookie", "session=one; Path=/secret/; HttpOnly"))
            server.enqueue(response("null").setHeader("Set-Cookie", "session=two; Path=/secret/; HttpOnly"))
            server.enqueue(response("\"after\""))
            server.enqueue(response("null"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/secret/"), username="admin", password="p&=secret"), true)
            api.login("123456")
            api.setInboundEnabled(7, false)
            val csrf = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/csrf-token", csrf.path)
            val login = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/login", login.path)
            assertEquals("before", login.getHeader("X-CSRF-Token"))
            assertEquals("session=one", login.getHeader("Cookie"))
            assertTrue(login.body.readUtf8().contains("twoFactorCode=123456"))
            assertEquals("session=two", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie"))
            val mutation = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/secret/panel/api/inbounds/setEnable/7", mutation.path)
            assertEquals("after", mutation.getHeader("X-CSRF-Token"))
            assertFalse(JSONObject(mutation.body.readUtf8()).getBoolean("enable"))
        }
    }
    @Test fun bearerAuthSkipsLoginAndEscapesClientIdentifier() = runBlocking {
        server().use { server ->
            server.enqueue(response("[]"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/base/"), token="private-token"), true)
            api.login(); api.links("alice+phone@example.com")
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer private-token", request.getHeader("Authorization"))
            assertEquals("/base/panel/api/clients/links/alice+phone@example.com", request.path)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun redirectCannotLeakAuthenticationToAnotherServer() = runBlocking {
        server().use { source -> server().use { target ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/collect")))
            val api = PanelApi(ServerProfile(name="test", baseUrl=source.localUrl("/"), token="private-token"), true)
            try { api.get("server", "status"); fail("Redirect must fail") } catch (_: PanelException) { }
            assertEquals(0, target.requestCount)
        } }
    }
    @Test fun apiFailureOnHttp200IsNotSuccess() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":false,\"msg\":\"denied\"}"))
            val api = PanelApi(ServerProfile(name="test", baseUrl=server.localUrl("/"), token="t"), true)
            try { api.setClientEnabled("alice", true); fail("Must reject failure envelope") } catch (e: PanelException) { assertTrue(e.message!!.contains("denied")) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun unsafePanelAddressesAreRejected() {
        listOf("http://example.com/", "https://u:p@example.com/", "https://example.com/?secret=a", "https://example.com/#a").forEach {
            try { panelBaseUrl(it); fail("Must reject $it") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://example.com/secret/", panelBaseUrl("https://example.com/secret/panel/").toString())
    }
    private fun certificateHash(c: HeldCertificate) = java.security.MessageDigest.getInstance("SHA-256").digest(c.certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun sshPinnedCertificateIsAcceptedWithMatchingHostname() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        server().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.enqueue(response("{\"panelVersion\":\"3.7.0\"}"))
            val api = PanelApi(ServerProfile(name="pinned", baseUrl=server.localUrl("/"), token="t", certificatePin=certificateHash(cert)))
            assertEquals("3.7.0", (api.get("server", "status") as JSONObject).getString("panelVersion"))
        }
    }
    @Test fun wrongPinAndWrongHostnameAreRejectedBeforeSendingToken() = runBlocking {
        for (wrongPin in listOf(true, false)) {
            val cert = HeldCertificate.Builder().addSubjectAlternativeName(if (wrongPin) "127.0.0.1" else "different.example").build()
            server().use { server ->
                server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
                val api = PanelApi(ServerProfile(name="pinned", baseUrl=server.localUrl("/"), token="secret", certificatePin=if (wrongPin) "0".repeat(64) else certificateHash(cert)))
                try { api.get("server", "status"); fail("Must reject TLS") } catch (_: javax.net.ssl.SSLException) { }
                assertEquals(0, server.requestCount)
            }
        }
    }
}
