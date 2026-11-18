package dev.threex.mobile.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PanelException(message: String) : IOException(message)

fun panelBaseUrl(raw: String, allowLocalHttp: Boolean = false): HttpUrl {
    val url = raw.trim().toHttpUrl()
    require(url.isHttps || (allowLocalHttp && url.host in listOf("localhost", "127.0.0.1", "::1"))) { "Укажите HTTPS-адрес панели" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Адрес не должен содержать пароль, параметры или #" }
    val path = url.encodedPath.trimEnd('/').removeSuffix("/panel")
    return url.newBuilder().encodedPath("$path/").build()
}

class PanelApi(private val profile: ServerProfile, allowLocalHttp: Boolean = false) {
    private val base = panelBaseUrl(profile.baseUrl, allowLocalHttp)
    private val cookies = mutableListOf<Cookie>()
    private var csrf = ""
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .apply {
            if (profile.certificatePin.isNotEmpty()) {
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client certificates not supported")
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        val cert = chain.firstOrNull() ?: throw java.security.cert.CertificateException("Missing certificate")
                        cert.checkValidity()
                        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
                        if (fingerprint != profile.certificatePin) throw java.security.cert.CertificateException("Certificate changed")
                    }
                }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                sslSocketFactory(ssl.socketFactory, trust)
                // OkHttp's normal hostname verification remains enabled.
            }
        }
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) = synchronized(cookies) {
                incoming.forEach { cookie ->
                    cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    cookies.add(cookie)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
                cookies.filter { it.matches(url) }
            }
        }).build()

    private suspend fun request(path: List<String>, body: RequestBody? = null): Any? {
        val url = base.newBuilder().apply { path.forEach(::addPathSegment) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (profile.token.isNotBlank()) request.header("Authorization", "Bearer ${profile.token}")
        if (csrf.isNotEmpty()) request.header("X-CSRF-Token", csrf)
        if (body != null) request.post(body)
        val response = client.newCall(request.build()).await()
        response.use {
            when (it.code) {
                301, 302, 303, 307, 308 -> throw PanelException("Панель перенаправляет запрос. Проверьте полный адрес и base path; при необходимости войдите снова.")
                401 -> throw PanelException("Сессия истекла или токен неверен. Выполните вход снова.")
                403 -> throw PanelException("Недостаточно прав или истёк CSRF-токен. Выполните вход снова.")
            }
            if (!it.isSuccessful) throw PanelException("Ошибка панели: HTTP ${it.code}")
            val envelope = try { JSONObject(it.body?.string().orEmpty()) } catch (_: Exception) {
                throw PanelException("Вместо API получен другой ответ. Проверьте адрес панели и base path.")
            }
            if (!envelope.optBoolean("success")) throw PanelException("Панель отклонила запрос: ${envelope.optString("msg").take(250)}")
            return envelope.opt("obj").takeUnless { value -> value == JSONObject.NULL }
        }
    }
    suspend fun login(twoFactorCode: String = "") {
        if (profile.token.isNotBlank()) return
        csrf = request(listOf("csrf-token")) as? String ?: throw PanelException("Панель не выдала CSRF-токен")
        request(listOf("login"), FormBody.Builder().add("username", profile.username).add("password", profile.password)
            .add("twoFactorCode", twoFactorCode).build())
        csrf = request(listOf("csrf-token")) as? String ?: throw PanelException("Не удалось обновить CSRF-токен")
    }
    suspend fun snapshot(): PanelSnapshot {
        val status = get("server", "status") as? JSONObject ?: throw PanelException("Неизвестный формат статуса")
        val inbounds = (get("inbounds", "list") as? JSONArray ?: JSONArray()).objects()
        val clients = (get("clients", "list") as? JSONArray ?: JSONArray()).objects()
        return PanelSnapshot(status, inbounds, clients)
    }
    suspend fun get(vararg segments: String): Any? = request(listOf("panel", "api") + segments)
    suspend fun post(vararg segments: String, body: JSONObject = JSONObject()): Any? = request(
        listOf("panel", "api") + segments, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    suspend fun form(vararg segments: String, values: Map<String, String>): Any? = request(
        listOf("panel", "api") + segments, FormBody.Builder().apply { values.forEach { (k,v) -> add(k,v) } }.build())
    suspend fun links(email: String): List<String> {
        val links = get("clients", "links", email) as? JSONArray ?: JSONArray()
        return (0 until links.length()).map { links.getString(it) }
    }
    suspend fun setClientEnabled(email: String, enabled: Boolean) = post("clients", if (enabled) "bulkEnable" else "bulkDisable", body = JSONObject().put("emails", JSONArray(listOf(email))))
    suspend fun setInboundEnabled(id: Int, enabled: Boolean) = post("inbounds", "setEnable", id.toString(), body = JSONObject().put("enable", enabled))
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
