package dev.threex.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vault.bin"))
    private val alias = "three-x-vault-v1"
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Неизвестный формат хранилища" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized private fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun saveServer(profile: ServerProfile) {
        val root = read()
        val items = (root.optJSONArray("servers") ?: JSONArray()).objects().filter { it.optString("id") != profile.id }
        write(root.put("servers", JSONArray(items + profile.json())))
    }
    @Synchronized fun removeServer(id: String) {
        val root = read()
        write(root.put("servers", JSONArray((root.optJSONArray("servers") ?: JSONArray()).objects().filter { it.optString("id") != id })))
    }
    @Synchronized fun saveVpn(profile: VpnProfile) {
        val root = read()
        val items = (root.optJSONArray("vpn") ?: JSONArray()).objects().filter { it.optString("id") != profile.id }
        write(root.put("vpn", JSONArray(items + profile.json())))
    }
    @Synchronized fun removeVpn(id: String) {
        val root = read()
        write(root.put("vpn", JSONArray((root.optJSONArray("vpn") ?: JSONArray()).objects().filter { it.optString("id") != id })))
    }
    @Synchronized fun hostKey(host: String): String? = read().optJSONObject("hostKeys")?.optString(host)?.takeIf { it.isNotBlank() }
    @Synchronized fun trustHost(host: String, fingerprint: String) {
        val root = read()
        write(root.put("hostKeys", (root.optJSONObject("hostKeys") ?: JSONObject()).put(host, fingerprint)))
    }
    @Synchronized fun prepareInstallation(endpoint: String, profile: ServerProfile) {
        val root = read()
        write(root.put("pendingInstallations", (root.optJSONObject("pendingInstallations") ?: JSONObject()).put(endpoint, profile.json())))
    }
    @Synchronized fun pendingInstallation(endpoint: String): ServerProfile? = read().optJSONObject("pendingInstallations")?.optJSONObject(endpoint)?.let(ServerProfile::from)
    @Synchronized fun finishInstallation(endpoint: String, profile: ServerProfile) {
        saveServer(profile)
        val root = read()
        root.optJSONObject("pendingInstallations")?.remove(endpoint)
        write(root)
    }
}
