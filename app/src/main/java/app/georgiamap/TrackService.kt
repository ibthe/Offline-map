package app.georgiamap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
    private var activeProvider: String? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val id = trackId
            if (id <= 0 || (l.hasAccuracy() && l.accuracy > 75f)) return
            val lat = l.latitude
            val lon = l.longitude
            val ele = if (l.hasAltitude()) l.altitude else Double.NaN
            val timestamp = l.time
            writeHandler?.post {
                try { db?.addPoint(id, lat, lon, ele, timestamp) } catch (_: Exception) { }
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (provider != activeProvider) return
            val manager = lm ?: return
            val fallback = try {
                when {
                    provider != LocationManager.GPS_PROVIDER && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    provider != LocationManager.NETWORK_PROVIDER && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
            } catch (_: Exception) { null }
            if (fallback == null) {
                stopSelf()
                return
            }
            try {
                manager.removeUpdates(this)
                manager.requestLocationUpdates(fallback, 3000L, 5f, this, Looper.getMainLooper())
                activeProvider = fallback
            } catch (_: Exception) {
                stopSelf()
            }
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
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Запись GPS-трека")
            .setContentText("Georgia Offline Map записывает маршрут")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
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

        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = manager
        val provider = try {
            when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
        } catch (_: SecurityException) {
            null
        }
        if (provider == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        activeProvider = provider
        writeThread = HandlerThread("track-db-writer").apply { start() }
        writeHandler = Handler(writeThread!!.looper)
        try {
            manager.removeUpdates(listener)
            manager.requestLocationUpdates(provider, 3000L, 5f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        activeProvider = null
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
