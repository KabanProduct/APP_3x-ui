package dev.threex.mobile.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.threex.mobile.BuildConfig
import dev.threex.mobile.data.AppRelease
import dev.threex.mobile.data.AppUpdates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class UpdateState(val busy: Boolean = false, val release: AppRelease? = null, val progress: Int? = null, val apk: File? = null, val message: String = "")
class UpdatesViewModel(app: Application) : AndroidViewModel(app) {
    private val updater = AppUpdates(app)
    private val preferences = app.getSharedPreferences("updates", 0)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    init { if (System.currentTimeMillis() - preferences.getLong("checked", 0) > 24 * 60 * 60 * 1000L) check() }
    private fun task(work: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else "Не удалось обратиться к GitHub. Проверьте интернет и повторите.") } }
            finally { mutable.update { it.copy(busy = false, progress = null) } }
        }
    }
    fun check() = task {
        val release = updater.check()
        preferences.edit().putLong("checked", System.currentTimeMillis()).apply()
        mutable.value = UpdateState(busy = true, release = release, message = if (release == null) "Новых опубликованных версий нет" else "Доступна версия ${release.version}")
    }
    fun download() {
        val release = mutable.value.release ?: return
        task {
            mutable.update { it.copy(apk = null, progress = 0) }
            val file = updater.download(release) { percent -> mutable.update { it.copy(progress = percent) } }
            mutable.update { it.copy(apk = file, message = "APK проверен. Можно установить обновление.") }
        }
    }
    fun install() {
        val current = mutable.value
        val file = current.apk ?: return
        val version = current.release?.version ?: return
        task {
            val context = getApplication<Application>()
            withContext(Dispatchers.IO) { updater.verify(file, version) }
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mutable.update { it.copy(message = "Разрешите установку из 3x Mobile, вернитесь и нажмите «Установить» ещё раз.") }
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

@Composable
fun UpdatesScreen(vm: UpdatesViewModel, state: UpdateState) {
    val context = LocalContext.current
    AppCard("Версия ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " · debug" else ""}") {
        Text("Источник: KabanProduct/APP_3x-ui · GitHub Releases")
        Text("Проверка при запуске — не чаще раза в сутки. Загрузка и установка — по вашему нажатию.")
        if (state.message.isNotBlank()) Text(state.message)
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress?.let { "Загружено $it%" } ?: "Проверка…")
        }
        OutlinedButton(onClick = vm::check, enabled = !state.busy) { Text("Проверить обновления") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/KabanProduct/APP_3x-ui/releases"))) }) { Text("Открыть релизы на GitHub") }
    }
    state.release?.let { release ->
        AppCard("Что нового в ${release.version}") {
            Text(release.notes.ifBlank { "Описание изменений не добавлено" })
            Text("APK: ${release.size / 1024 / 1024} МБ. Настройки сохраняются при обновлении с той же подписью.")
            if (state.apk == null) Button(onClick = vm::download, enabled = !state.busy) { Text("Скачать обновление") }
            else Button(onClick = vm::install, enabled = !state.busy) { Text("Установить") }
        }
    }
}
