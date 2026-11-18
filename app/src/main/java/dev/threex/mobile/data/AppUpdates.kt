package dev.threex.mobile.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.threex.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String)

object ReleasePolicy {
    const val REPOSITORY = "KabanProduct/APP_3x-ui"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    fun version(value: String): List<Int> {
        require(Regex("v?\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}").matches(value)) { "Неизвестный формат версии" }
        return value.removePrefix("v").split('.').map(String::toInt)
    }
    fun newer(candidate: String, installed: String): Boolean {
        val a = version(candidate); val b = version(installed)
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
    fun parse(json: JSONObject, abi: String, installed: String): AppRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.getString("tag_name")
        if (!newer(tag, installed)) return null
        require(abi in listOf("arm64-v8a", "x86_64")) { "Нет сборки для архитектуры телефона" }
        val name = "3x-mobile-$abi.apk"
        val assets = json.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == name }
            ?: error("В релизе отсутствует APK для телефона")
        val url = asset.getString("browser_download_url")
        require(url == "https://github.com/$REPOSITORY/releases/download/$tag/$name") { "Неожиданный источник APK" }
        val digest = asset.getString("digest")
        require(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) { "Нет контрольной суммы APK" }
        val size = asset.getLong("size")
        require(size in 1..MAX_APK_BYTES) { "Недопустимый размер APK" }
        return AppRelease(tag.removePrefix("v"), json.optString("body").take(16000), url, size, digest.substring(7).lowercase())
    }
}

class AppUpdates(private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    private val apk = File(context.cacheDir, "updates/update.apk")
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "3x-Mobile/${BuildConfig.VERSION_NAME}").build()
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        client.newCall(request("https://api.github.com/repos/${ReleasePolicy.REPOSITORY}/releases/latest")).execute().use { response ->
            if (response.code == 404) return@withContext null
            check(response.isSuccessful) { "GitHub недоступен (HTTP ${response.code}). Повторите позже." }
            val body = response.body ?: error("Пустой ответ GitHub")
            val bytes = body.byteStream().readBytesLimited(2 * 1024 * 1024)
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it in listOf("arm64-v8a", "x86_64") } ?: error("Архитектура не поддерживается")
            ReleasePolicy.parse(JSONObject(bytes.toString(Charsets.UTF_8)), abi, BuildConfig.VERSION_NAME)
        }
    }
    suspend fun download(release: AppRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        apk.parentFile!!.mkdirs()
        val partial = File(apk.parentFile, "update.part")
        apk.delete(); partial.delete()
        try {
            var url = release.url
            var finished = false
            for (redirect in 0..5) {
                currentCoroutineContext().ensureActive()
                val parsed = url.toHttpUrl()
                require(parsed.scheme == "https" && parsed.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Недопустимое перенаправление загрузки" }
                client.newCall(request(url)).execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        url = parsed.resolve(response.header("Location") ?: error("Нет адреса загрузки"))?.toString() ?: error("Неверный адрес")
                    } else {
                        check(response.isSuccessful) { "Ошибка загрузки APK: HTTP ${response.code}" }
                        val body = response.body ?: error("Пустой APK")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L; var last = -1
                        body.byteStream().use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                count += n
                                require(count <= release.size && count <= ReleasePolicy.MAX_APK_BYTES) { "APK превышает заявленный размер" }
                                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                                val percent = (count * 100 / release.size).toInt()
                                if (percent != last) { progress(percent); last = percent }
                            }
                        } }
                        require(count == release.size && digest.digest().hex() == release.sha256) { "Контрольная сумма APK не совпала" }
                        verify(partial, release.version)
                        check(partial.renameTo(apk)) { "Не удалось сохранить APK" }
                        finished = true
                    }
                }
                if (finished) break
            }
            check(finished) { "Слишком много перенаправлений GitHub" }
            apk
        } finally { partial.delete() }
    }
    @Suppress("DEPRECATION")
    fun verify(file: File, expectedVersion: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Повреждённый APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName) { "APK принадлежит другому приложению" }
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(code > oldCode && candidate.versionName == expectedVersion) { "Номер версии APK не соответствует обновлению" }
        fun signers(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet() ?: emptySet()
        val current = signers(installed)
        require(current.isNotEmpty() && signers(candidate) == current) { "Подпись APK отличается. Debug-версию нужно один раз заменить release-сборкой вручную." }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        require(out.size() + n <= limit) { "Слишком большой ответ GitHub" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
