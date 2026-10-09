package com.folio.batterysync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import com.xiaomi.xms.wearable.Status
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.DataSubscribeResult
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.OnDataChangedListener
import com.xiaomi.xms.wearable.node.IDataCallback
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener

// 只通过官方应用的服务读取状态，不建立或接管手环蓝牙连接。
internal class BatteryReader private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val nodeApi by lazy { Wearable.getNodeApi(context) }
    private val authApi by lazy { Wearable.getAuthApi(context) }
    private val serviceApi by lazy { Wearable.getServiceApi(context) }
    private var observing = false
    private var generation = 0L
    private var inFlight = false
    private var authorizing = false
    private var subscribedNode: String? = null
    private var timeout: Runnable? = null
    var foreground = true

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updatePhone(intent) }
    }

    private val serviceListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() = dispatch {
            if (!inFlight && (foreground || BatteryState.backgroundRunning)) refresh()
        }
        override fun onServiceDisconnected() = dispatch {
            invalidate()
            subscribedNode = null
            BatteryState.clearWearable("serviceDisconnected", "HOST_SERVICE_DISCONNECTED")
        }
    }

    private val dataListener = OnDataChangedListener { nodeId, item, data ->
        dispatch {
            if (nodeId != subscribedNode) return@dispatch
            if (item.type == DataItem.ITEM_CONNECTION.type &&
                data.connectedStatus != DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                invalidate()
                BatteryState.clearWearable("deviceDisconnected", "DEVICE_DISCONNECTED")
            } else if (foreground || BatteryState.backgroundRunning) {
                refresh()
            }
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun refresh() = dispatch { begin(false) }
    fun authorize() = dispatch { begin(true) }

    private fun begin(authorize: Boolean) {
        if (inFlight) return
        updatePhone(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        val host = listOf("com.xiaomi.wearable", "com.mi.health").firstNotNullOfOrNull { name ->
            runCatching { context.packageManager.getPackageInfo(name, 0) }.getOrNull()?.let { name to it }
        }
        if (host == null) {
            BatteryState.hostPackage = null
            BatteryState.hostVersion = null
            BatteryState.wearableName = null
            BatteryState.clearWearable("appMissing", "HOST_APP_MISSING")
            return
        }
        BatteryState.hostPackage = host.first
        BatteryState.hostVersion = host.second.versionName
        BatteryState.attemptedAt = System.currentTimeMillis()
        BatteryState.wearableAppInstalled = null
        BatteryState.companionCheckError = null
        BatteryState.queryStage = "connectedNodes"
        inFlight = true
        authorizing = authorize
        val request = ++generation
        BatteryState.clearWearable("reading")
        timeout = Runnable {
            if (request == generation && inFlight) {
                fail("timeout", "QUERY_TIMEOUT")
            }
        }.also { main.postDelayed(it, if (authorize) 60_000 else 15_000) }
        try {
            observe()
            serviceApi.serviceApiLevel.addOnSuccessListener { level ->
                dispatch { if (request == generation) { BatteryState.sdkApiLevel = level; BatteryState.publish() } }
            }
            nodeApi.connectedNodes
                .addOnSuccessListener { nodes -> result(request) {
                    when (nodes.size) {
                        0 -> { BatteryState.wearableName = null; fail("deviceDisconnected", "NO_CONNECTED_DEVICE") }
                        1 -> {
                            val node = nodes[0]
                            BatteryState.wearableName = node.name
                            inspectCompanion(node, request, authorize)
                        }
                        else -> fail("multipleDevices", "MULTIPLE_CONNECTED_DEVICES")
                    }
                } }
                .addOnFailureListener { error -> result(request) { fail(error) } }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun observe() {
        if (observing) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(phoneReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(phoneReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        observing = true
        serviceApi.registerServiceConnectionListener(serviceListener)
    }

    private fun inspectCompanion(node: Node, request: Long, authorize: Boolean) {
        BatteryState.queryStage = "companionCheck"
        nodeApi.isWearAppInstalled(node.id)
            .addOnSuccessListener { installed -> result(request) {
                BatteryState.wearableAppInstalled = installed
                if (authorize) requestAuthorization(node, request) else query(node, request)
            } }
            .addOnFailureListener { error -> result(request) {
                BatteryState.companionCheckError = error.javaClass.simpleName
                if (authorize) requestAuthorization(node, request) else query(node, request)
            } }
    }

    private fun requestAuthorization(node: Node, request: Long) {
        BatteryState.queryStage = "authorization"
        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { permissions -> result(request) {
                if (permissions.any { it.name == Permission.DEVICE_MANAGER.name }) query(node, request)
                else fail("permissionDenied", "DEVICE_MANAGER_DENIED")
            } }
            .addOnFailureListener { error -> result(request) { fail(error) } }
    }

    private fun query(node: Node, request: Long) {
        BatteryState.queryStage = "connectionQuery"
        nodeApi.query(node.id, DataItem.ITEM_CONNECTION)
            .addOnSuccessListener { connection -> result(request) {
                if (!connection.isConnected) { fail("deviceDisconnected", "DEVICE_DISCONNECTED"); return@result }
                BatteryState.queryStage = "batteryQuery"
                queryBattery(node, request) { level ->
                        BatteryState.queryStage = "chargingQuery"
                        nodeApi.query(node.id, DataItem.ITEM_CHARGING)
                            .addOnSuccessListener { charging -> result(request) { complete(node, level, charging.isCharging) } }
                            .addOnFailureListener { error -> result(request) {
                                when (error.javaClass.simpleName) {
                                    "DeviceDisconnectedException", "PermissionDeniedException", "SignatureVerifyFailedException" -> fail(error)
                                    else -> complete(node, level, null)
                                }
                            } }
                }
            } }
            .addOnFailureListener { error -> result(request) { fail(error) } }
    }

    private fun queryBattery(node: Node, request: Long, success: (Int) -> Unit) {
        // 固定 SDK 的默认解码会把缺失字段当成零，先核验原始返回项。
        nodeApi.apiClient.g.a(node.id, DataItem.ITEM_BATTERY, object : IDataCallback.Stub() {
            override fun onResult(item: DataItem, data: Bundle) = result(request) {
                val level = data.getInt("battery_status", -1)
                when {
                    item.type != DataItem.ITEM_BATTERY.type -> fail("unavailable", "UNEXPECTED_BATTERY_ITEM")
                    !data.containsKey("battery_status") -> fail("unavailable", "BATTERY_VALUE_MISSING")
                    BatteryState.hostPackage == MiFitnessBatteryModule.HOST_PACKAGE &&
                        BatteryState.hostVersion == MiFitnessBatteryModule.HOST_VERSION &&
                        data.getString(MiFitnessBatteryModule.SOURCE_KEY) != MiFitnessBatteryModule.SOURCE_VALUE ->
                        fail("moduleRequired", "HOST_CACHE_ADAPTER_REQUIRED")
                    level !in 0..100 -> fail("unavailable", "INVALID_BATTERY")
                    else -> success(level)
                }
            }

            override fun onFailure(status: Status) = result(request) {
                val error = com.xiaomi.xms.wearable.exception.ExceptionUtil.convertStatusToException(status)
                fail(error ?: IllegalStateException("Battery query failed"))
            }
        })
    }

    private fun complete(node: Node, level: Int, charging: Boolean?) {
        finish()
        BatteryState.status = "ready"
        BatteryState.wearableLevel = level
        BatteryState.wearableCharging = charging
        BatteryState.updatedAt = System.currentTimeMillis()
        BatteryState.errorCode = null
        BatteryState.publish()
        subscribe(node.id)
        if (!foreground && !BatteryState.backgroundRunning) pause()
    }

    private fun subscribe(nodeId: String) {
        if (subscribedNode == nodeId) return
        unsubscribe()
        subscribedNode = nodeId
        for (item in listOf(DataItem.ITEM_CONNECTION, DataItem.ITEM_CHARGING)) {
            runCatching { nodeApi.subscribe(nodeId, item, dataListener) }
        }
    }

    private fun unsubscribe() {
        val old = subscribedNode ?: return
        subscribedNode = null
        for (item in listOf(DataItem.ITEM_CONNECTION, DataItem.ITEM_CHARGING)) {
            runCatching { nodeApi.unsubscribe(old, item) }
        }
    }

    private fun result(request: Long, block: () -> Unit) = dispatch {
        if (request == generation && inFlight) {
            try { block() } catch (error: Exception) { fail(error) }
        }
    }

    private fun fail(error: Exception) {
        val status = when (error.javaClass.simpleName) {
            "AppNotInstalledException" -> if (BatteryState.wearableAppInstalled == false) "companionMissing" else "unavailable"
            "DeviceDisconnectedException" -> "deviceDisconnected"
            "PermissionDeniedException" -> if (authorizing) "permissionDenied" else "permissionRequired"
            "SignatureVerifyFailedException" -> "signatureRejected"
            else -> "unavailable"
        }
        fail(status, error.javaClass.simpleName)
    }

    private fun fail(status: String, code: String) {
        invalidate()
        BatteryState.clearWearable(status, code)
        if (!foreground && !BatteryState.backgroundRunning) pause()
    }
    private fun finish() { timeout?.let(main::removeCallbacks); timeout = null; inFlight = false; authorizing = false }
    private fun invalidate() { generation++; finish() }

    private fun updatePhone(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        BatteryState.phoneLevel = BatteryValues.percentage(level, scale)
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        BatteryState.phoneCharging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        BatteryState.publish()
    }

    fun pause() = dispatch {
        // 保留授权请求，允许用户跳转到官方授权界面。
        if (BatteryState.backgroundRunning || authorizing) return@dispatch
        invalidate()
        unsubscribe()
        if (observing) {
            runCatching { context.unregisterReceiver(phoneReceiver) }
            runCatching { serviceApi.unregisterServiceConnectionListener(serviceListener) }
            observing = false
        }
        if (BatteryState.status == "reading") BatteryState.clearWearable("idle")
    }

    companion object {
        @Volatile private var instance: BatteryReader? = null
        fun get(context: Context): BatteryReader = instance ?: synchronized(this) {
            instance ?: BatteryReader(context.applicationContext).also { instance = it }
        }
    }
}
