package com.folio.batterysync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject

// 仅接受已授权 USB 调试连接回传的电脑配件电量。
class ComputerBatteryProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() !in setOf(0, 2000)) throw SecurityException("USB_SHELL_REQUIRED")
        require(method == "sync" && arg != null && arg.length <= 16_384) { "INVALID_PAYLOAD" }
        val payload = JSONObject(String(Base64.decode(arg, Base64.NO_WRAP), Charsets.UTF_8))
        val devices = payload.getJSONArray("devices")
        require(devices.length() <= 16) { "TOO_MANY_DEVICES" }
        val records = (0 until devices.length()).map { index ->
            val item = devices.getJSONObject(index)
            val name = item.getString("name").trim()
            val level = item.get("level")
            val category = item.optString("category", "accessory")
            require(name.isNotEmpty() && name.length <= 120 && level is Int && level in 0..100) { "INVALID_DEVICE" }
            mapOf<String, Any?>("name" to name, "level" to level,
                "category" to category.takeIf { it in CATEGORIES }.orEmpty().ifEmpty { "accessory" },
                "charging" to (item.opt("charging") as? Boolean))
        }
        Handler(Looper.getMainLooper()).post {
            BatteryState.computerDevices = records
            BatteryState.computerUpdatedAt = System.currentTimeMillis()
            BatteryState.publish()
        }
        return Bundle().apply { putInt("accepted", records.size) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private val CATEGORIES = setOf("computer", "keyboard", "mouse", "trackpad", "headphones", "speaker", "controller", "accessory")
    }
}
