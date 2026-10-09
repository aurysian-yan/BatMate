package com.folio.batterysync

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class WirelessPeer(val id: String, val port: Int, val addresses: List<String>, val fingerprint: String,
    val token: String, val pending: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("port", port).put("addresses", JSONArray(addresses))
        .put("fingerprint", fingerprint).put("token", token)

    companion object {
        fun parse(value: JSONObject, pending: Boolean = false): WirelessPeer {
            val id = value.getString("id")
            val port = value.get("port")
            val fingerprint = value.getString("fingerprint")
            val token = value.getString("token")
            val list = value.getJSONArray("addresses")
            require(id.matches(Regex("[A-Za-z0-9-]{1,64}")) && port is Int && port in 1..65535)
            require(fingerprint.matches(Regex("[0-9a-f]{64}")) && token.length <= 64 && Base64.decode(token, Base64.NO_WRAP).size == 32)
            require(list.length() <= 16)
            val addresses = (0 until list.length()).map { list.getString(it) }
            require(addresses.all { it.length <= 128 && it.matches(Regex("[0-9a-fA-F:.%]+")) })
            return WirelessPeer(id, port, addresses, fingerprint, token, pending)
        }
        fun fromQR(code: String): WirelessPeer {
            require(code.length <= 8192)
            val uri = Uri.parse(code)
            require(uri.scheme == "batmate" && uri.host == "pair")
            val payload = uri.getQueryParameter("data") ?: error("INVALID_PAIRING")
            val value = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            require(value.get("v") == 1)
            return parse(value, pending = true)
        }
    }
}

// 凭据由 Keystore 密钥加密，备份中的密文不能在其他安装中使用。
internal class WirelessCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("wireless-pairing", Context.MODE_PRIVATE)
    val phoneID: String = preferences.getString("phoneID", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("phoneID", it).apply()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun read(): WirelessPeer? {
        val value = preferences.getString("credential", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            WirelessPeer.parse(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)))
        }.getOrNull()
    }
    fun save(peer: WirelessPeer) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(peer.json().toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("credential", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun clear() { check(preferences.edit().remove("credential").commit()) }
    companion object { private const val ALIAS = "app.batmate.wireless.credentials" }
}
