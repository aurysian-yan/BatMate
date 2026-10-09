package com.folio.batterysync

import org.json.JSONArray

// USB 与无线复用校验和更新入口，整条消息通过后才替换列表。
internal object ComputerBatteryStore {
    fun parse(devices: JSONArray): List<Map<String, Any?>> {
        require(devices.length() <= 16) { "TOO_MANY_DEVICES" }
        return (0 until devices.length()).map { index ->
            val item = devices.getJSONObject(index)
            val name = item.getString("name").trim()
            val level = item.get("level")
            val category = item.optString("category", "accessory")
            require(name.isNotEmpty() && name.length <= 120 && level is Int && level in 0..100) { "INVALID_DEVICE" }
            val charging = item.opt("charging")
            require(charging == null || charging == org.json.JSONObject.NULL || charging is Boolean) { "INVALID_DEVICE" }
            mapOf<String, Any?>("name" to name, "level" to level,
                "category" to (category.takeIf { it in CATEGORIES } ?: "accessory"), "charging" to (charging as? Boolean))
        }
    }
    fun update(records: List<Map<String, Any?>>) {
        BatteryState.computerDevices = records
        BatteryState.computerUpdatedAt = System.currentTimeMillis()
        BatteryState.publish()
    }
    private val CATEGORIES = setOf("computer", "keyboard", "mouse", "trackpad", "headphones", "speaker", "controller", "accessory")
}
