package com.folio.batterysync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.FileDescriptor
import java.io.PrintWriter
import org.json.JSONObject

// 前台服务每分钟查询一次，不使用唤醒锁，不在后台弹出授权。
class BatteryMonitorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!BatteryState.backgroundRunning) return
            BatteryReader.get(this@BatteryMonitorService).refresh()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.battery_notification_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(getString(R.string.battery_notification_title))
                .setContentText(getString(R.string.battery_notification_body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (launch != null) notification.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            startForeground(1, notification.build())
            BatteryState.backgroundRunning = true
            BatteryState.publish()
            main.removeCallbacks(poll)
            main.post(poll)
        } catch (error: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
            BatteryState.backgroundRunning = false
            BatteryState.clearWearable("unavailable", error.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        BatteryState.backgroundRunning = false
        val reader = BatteryReader.get(this)
        if (!reader.foreground) reader.pause()
        BatteryState.publish()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // USB 接收端显式请求名称；默认诊断仍省略设备名称。
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val includeNames = args.contains("--device-names")
        val snapshot = JSONObject(BatteryState.snapshot().filterKeys {
            includeNames || it !in setOf("wearableName", "phoneName", "computerDevices")
        })
        writer.println(snapshot)
    }

    companion object {
        const val PREFS = "battery-monitor"
        const val ENABLED = "enabled"
        private const val CHANNEL = "battery-monitor"
    }
}
