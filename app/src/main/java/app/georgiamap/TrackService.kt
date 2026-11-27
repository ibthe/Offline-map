package app.georgiamap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service that records a GPS track into the database while the screen is off. */
class TrackService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL = "track"
    }

    private var db: UserDb? = null
    private var lm: LocationManager? = null
    private var trackId = 0L
    private var writeThread: HandlerThread? = null
    private var writeHandler: Handler? = null
    private val activeProviders = LinkedHashSet<String>()
    private var lastAcceptedLocationTime = 0L
    private var lastAcceptedAccuracy = Float.POSITIVE_INFINITY
    private var lastRecordedLocationTime = 0L
    private var currentSegment = 0
    private val segmentGapMs = 5 * 60 * 1000L
    private var batteryReceiverRegistered = false
    private var batteryLow = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> {
                    batteryLow = true
                    updateRecordingNotification()
                }
                Intent.ACTION_BATTERY_OKAY -> {
                    batteryLow = false
                    updateRecordingNotification()
                }
            }
        }
    }

    private fun buildRecordingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (batteryLow) "Низкий заряд · запись трека" else "Запись GPS-трека")
            .setContentText(if (batteryLow) "Подключите питание: телефон может отключиться до сохранения экспедиции" else "Georgia Offline Map записывает маршрут")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateRecordingNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildRecordingNotification())
    }

    private fun monitorBattery() {
        if (batteryReceiverRegistered) return
        val sticky = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        batteryLow = level >= 0 && scale > 0 && level * 100 / scale <= 15
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, batteryReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        batteryReceiverRegistered = true
        if (batteryLow) updateRecordingNotification()
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val id = trackId
            val accuracy = l.accuracy.takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY
            val now = System.currentTimeMillis()
            if (id <= 0 || (accuracy.isFinite() && accuracy > 75f) ||
                !LocationQuality.shouldAccept(l.time, accuracy, lastAcceptedLocationTime, lastAcceptedAccuracy, now)) return
            if (lastRecordedLocationTime > 0L && l.time - lastRecordedLocationTime > segmentGapMs) {
                currentSegment++
            }
            lastAcceptedLocationTime = l.time
            lastAcceptedAccuracy = accuracy
            lastRecordedLocationTime = l.time
            val segment = currentSegment
            val lat = l.latitude
            val lon = l.longitude
            val ele = if (l.hasAltitude()) l.altitude else Double.NaN
            val timestamp = l.time
            writeHandler?.post {
                try { db?.addPoint(id, lat, lon, ele, timestamp, segment) } catch (_: Exception) { }
            }
        }

        override fun onProviderEnabled(provider: String) {
            val manager = lm ?: return
            val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val allowed = if (provider == LocationManager.GPS_PROVIDER) fine else provider == LocationManager.NETWORK_PROVIDER && (fine || coarse)
            if (!allowed || provider in activeProviders) return
            try {
                manager.requestLocationUpdates(provider, 3000L, 5f, this, Looper.getMainLooper())
                activeProviders.add(provider)
            } catch (_: Exception) { }
        }

        override fun onProviderDisabled(provider: String) {
            if (activeProviders.remove(provider) && activeProviders.isEmpty()) stopSelf()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        if (db == null) db = UserDb(this)
        trackId = getSharedPreferences("settings", MODE_PRIVATE).getLong("rec_track", 0L)
        db?.lastTrackPoint(trackId)?.let { last ->
            lastRecordedLocationTime = last.ts
            lastAcceptedLocationTime = last.ts
            currentSegment = last.segment
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val n: Notification = buildRecordingNotification()
        // startForegroundService() requires startForeground() even if we stop right away (else ANR/crash)
        try {
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (trackId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        monitorBattery()

        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = manager
        val finePermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarsePermission = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { provider ->
                    val permissionAllowed = if (provider == LocationManager.GPS_PROVIDER) finePermission else finePermission || coarsePermission
                    permissionAllowed && manager.isProviderEnabled(provider)
                }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (providers.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        writeThread = HandlerThread("track-db-writer").apply { start() }
        writeHandler = Handler(writeThread!!.looper)
        try {
            manager.removeUpdates(listener)
            activeProviders.clear()
            for (provider in providers) {
                try {
                    manager.requestLocationUpdates(provider, 3000L, 5f, listener, Looper.getMainLooper())
                    activeProviders.add(provider)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (activeProviders.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
            batteryReceiverRegistered = false
        }
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        activeProviders.clear()
        running = false
        val writer = writeHandler
        val thread = writeThread
        if (writer != null && thread != null) {
            writer.post {
                db?.close()
                db = null
                thread.quitSafely()
            }
        } else {
            db?.close()
            db = null
        }
        writeHandler = null
        writeThread = null
        super.onDestroy()
    }
}
