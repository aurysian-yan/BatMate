package com.folio.batterysync

import java.util.concurrent.CopyOnWriteArraySet

// 所有状态更新在主线程完成；查询失败不保留可误认成当前值的电量。
internal object BatteryState {
    var status = "idle"
    var phoneLevel: Int? = null
    var phoneName: String? = null
    var phoneCharging: Boolean? = null
    var wearableName: String? = null
    var wearableLevel: Int? = null
    var wearableCharging: Boolean? = null
    var updatedAt: Long? = null
    var attemptedAt: Long? = null
    var backgroundRunning = false
    var errorCode: String? = null
    var hostPackage: String? = null
    var hostVersion: String? = null
    var sdkApiLevel: Int? = null
    var wearableAppInstalled: Boolean? = null
    var companionCheckError: String? = null
    var queryStage: String? = null
    var computerDevices: List<Map<String, Any?>> = emptyList()
    var computerUpdatedAt: Long? = null
    val observers = CopyOnWriteArraySet<(Map<String, Any?>) -> Unit>()
    @Volatile private var published = currentState()

    private fun currentState(): Map<String, Any?> = mapOf(
        "status" to status, "phoneName" to phoneName, "phoneLevel" to phoneLevel, "phoneCharging" to phoneCharging,
        "wearableName" to wearableName, "wearableLevel" to wearableLevel,
        "wearableCharging" to wearableCharging, "updatedAt" to updatedAt,
        "backgroundRunning" to backgroundRunning, "errorCode" to errorCode,
        "hostPackage" to hostPackage, "hostVersion" to hostVersion, "sdkApiLevel" to sdkApiLevel,
        "wearableAppInstalled" to wearableAppInstalled,
        "attemptedAt" to attemptedAt,
        "companionCheckError" to companionCheckError, "queryStage" to queryStage,
        "computerDevices" to computerDevices, "computerUpdatedAt" to computerUpdatedAt,
    )

    fun snapshot(): Map<String, Any?> = published

    fun publish() { val state = currentState(); published = state; observers.forEach { it(state) } }

    fun clearWearable(nextStatus: String, code: String? = null) {
        status = nextStatus
        wearableLevel = null
        wearableCharging = null
        errorCode = code
        publish()
    }
}
