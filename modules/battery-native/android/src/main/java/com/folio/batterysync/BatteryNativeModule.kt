package com.folio.batterysync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject

class BatteryNativeModule : Module() {
    private val observer: (Map<String, Any?>) -> Unit = { sendEvent("onState", it) }

    override fun definition() = ModuleDefinition {
        Name("BatteryNative")
        Events("onState")
        View(BatteryDashboardView::class) {
            Events("onAction")
            Prop("model") { view: BatteryDashboardView, model: DashboardModel -> view.model = model }
            Prop("labels") { view: BatteryDashboardView, labels: Map<String, String> -> view.labels = labels }
        }
        OnCreate { BatteryState.observers.add(observer) }
        OnDestroy { BatteryState.observers.remove(observer) }
        OnActivityEntersForeground {
            WirelessSync.get(context()).start()
            BatteryReader.get(context()).apply { foreground = true; refresh() }
        }
        OnActivityEntersBackground {
            BatteryReader.get(context()).apply { foreground = false; pause() }
            if (!BatteryState.backgroundRunning) WirelessSync.get(context()).stop()
        }
        AsyncFunction("pairWireless") { code: String -> WirelessSync.get(context()).pair(code) }.runOnQueue(Queues.MAIN)
        AsyncFunction("forgetWireless") { WirelessSync.get(context()).forget() }.runOnQueue(Queues.MAIN)
        Function("snapshot") { BatteryState.snapshot() }
        Function("refresh") { BatteryReader.get(context()).refresh() }
        Function("authorize") { BatteryReader.get(context()).authorize() }
        Function("startBackground") {
            val context = context()
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("BLUETOOTH_CONNECT_REQUIRED")
            }
            context.getSharedPreferences(BatteryMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(BatteryMonitorService.ENABLED, true).apply()
            try { context.startForegroundService(Intent(context, BatteryMonitorService::class.java)) }
            catch (error: Exception) {
                context.getSharedPreferences(BatteryMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(BatteryMonitorService.ENABLED, false).apply()
                throw error
            }
        }
        Function("stopBackground") {
            val context = context()
            context.getSharedPreferences(BatteryMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(BatteryMonitorService.ENABLED, false).apply()
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
        AsyncFunction("requestBackgroundPermissions") { promise: Promise ->
            val required = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) required.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
            if (required.isEmpty()) promise.resolve(mapOf("granted" to true))
            else Permissions.askForPermissionsWithPermissionsManager(appContext.permissions, promise, *required.toTypedArray())
        }
        Function("openHost") {
            val context = context()
            val intent = listOf("com.xiaomi.wearable", "com.mi.health").firstNotNullOfOrNull {
                context.packageManager.getLaunchIntentForPackage(it)
            } ?: throw IllegalStateException("HOST_APP_MISSING")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Function("openSettings") {
            val context = context()
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Function("diagnostics") {
            JSONObject(BatteryState.snapshot()).apply {
                remove("wearableName")
                remove("phoneName")
                remove("computerDevices")
                put("appVersion", "0.1.0")
                put("deviceModel", Build.MODEL)
                put("androidVersion", Build.VERSION.RELEASE)
                put("androidApi", Build.VERSION.SDK_INT)
            }.toString(2)
        }
    }

    private fun context(): Context = requireNotNull(appContext.reactContext)
}
