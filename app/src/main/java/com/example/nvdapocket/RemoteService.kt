package com.example.nvdapocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import kotlin.math.sqrt

/** Keeps the process alive so the connection keeps running in the background. */
class RemoteService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private var sm: SensorManager? = null
    private var shakeOn = false

    private val shake = object : SensorEventListener {
        private var last = 0L
        private var first = 0L
        private var count = 0
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
            if (g > 2.7f) {
                val now = System.currentTimeMillis()
                if (now - last > 200) {
                    if (now - first > 1500) { count = 0; first = now }
                    count++
                    last = now
                    if (count >= 3) { count = 0; Remote.toggleMute() }
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun act(a: String, id: Int, label: String): Notification.Action {
            val pi = PendingIntent.getService(this, id, Intent(this, RemoteService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_info_details), label, pi).build()
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "remote") else Notification.Builder(this)
        b.setContentTitle(App.name(this))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .addAction(act("MUTE", 2, if (Remote.speak) "Mute" else "Unmute"))
            .addAction(act("SAS", 3, "Ctrl+Alt+Del"))
            .addAction(act("STOP", 1, "Disconnect"))
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { Remote.stop(this); stopSelf(); return START_NOT_STICKY }
            "MUTE" -> { Remote.toggleMute(); return START_NOT_STICKY }
            "SAS" -> { Remote.sendJson(JSONObject().put("type", "send_SAS")); return START_NOT_STICKY }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("remote", "Connection", NotificationManager.IMPORTANCE_LOW))
        }
        val n = notif(Remote.lastStatus)
        // Android 15 limits dataSync services to about 6 hours, so use specialUse where it exists (API 34+).
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        Remote.serviceListener = { t -> nm.notify(1, notif(t)) }
        if (wl == null) {
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nvdapocket:conn")
            wl?.acquire()
        }
        // Without this Wi-Fi may go to sleep with the screen off, which shows up as the relay suddenly not answering.
        if (wifi == null) try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "nvdapocket:wifi").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { CrashLog.log("wifilock", e) }
        if (!shakeOn && Store.prefs(this).getBoolean("shake", true)) {
            sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (acc != null) { sm?.registerListener(shake, acc, SensorManager.SENSOR_DELAY_UI); shakeOn = true }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Remote.serviceListener = null
        try { sm?.unregisterListener(shake) } catch (_: Exception) {}
        try { wl?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
