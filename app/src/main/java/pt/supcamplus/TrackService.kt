package pt.supcamplus

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

// Regista velocidade (GPS) e inclinação (sensores do telemóvel) num CSV, mesmo com o ecrã desligado.
// Linhas: t_ms,lat,lon,speed_kmh,pitch,roll,event  (event = REC_START / REC_STOP em linhas próprias)
class TrackService : Service(), SensorEventListener, LocationListener {

    companion object {
        @Volatile var running = false
        @Volatile var speed = 0f
        @Volatile var pitch = 0f
        @Volatile var roll = 0f
        @Volatile var hasFix = false
        @Volatile var flat = false      // false = telemóvel na vertical (retrato); true = deitado, ecrã para cima
        @Volatile var invert = false
        @Volatile private var rawPitch = 0f
        @Volatile private var rawRoll = 0f
        @Volatile private var zeroPitch = 0f
        @Volatile private var zeroRoll = 0f
        @Volatile private var inst: TrackService? = null

        fun tracksDir(c: Context): File {
            val d = File(c.getExternalFilesDir(null) ?: c.filesDir, "tracks")
            d.mkdirs()
            return d
        }

        fun start(c: Context) {
            c.startForegroundService(Intent(c, TrackService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, TrackService::class.java))
        }

        fun calibrate() {
            zeroPitch = rawPitch
            zeroRoll = rawRoll
        }

        fun event(name: String) {
            inst?.writeLine("${System.currentTimeMillis()},,,,,,$name")
        }
    }

    private var writer: FileWriter? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lat = 0.0
    private var lon = 0.0
    private val ticker = object : Runnable {
        override fun run() {
            val sg = if (invert) -1f else 1f
            pitch = (rawPitch - zeroPitch) * sg
            roll = (rawRoll - zeroRoll) * sg
            writeLine("${System.currentTimeMillis()},$lat,$lon,${"%.1f".format(Locale.US, speed)},${"%.1f".format(Locale.US, pitch)},${"%.1f".format(Locale.US, roll)},")
            handler?.postDelayed(this, 200)
        }
    }

    @Synchronized
    fun writeLine(l: String) {
        try {
            writer?.write(l + "\n")
            writer?.flush()
        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("track", "Registo GPS", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "track")
            .setContentTitle("SupCam Plus")
            .setContentText("A registar velocidade e inclinação")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        val name = "track_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        writer = FileWriter(File(tracksDir(this), name))
        writeLine("t_ms,lat,lon,speed_kmh,pitch,roll,event")
        inst = this
        running = true
        hasFix = false
        speed = 0f

        thread = HandlerThread("track").also { it.start() }
        handler = Handler(thread!!.looper)

        val sm = getSystemService(SensorManager::class.java)
        val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (g != null) sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME, handler)

        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread!!.looper)
        } catch (e: Exception) {
        }
        handler?.post(ticker)
    }

    override fun onDestroy() {
        running = false
        inst = null
        handler?.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        try {
            getSystemService(LocationManager::class.java).removeUpdates(this)
        } catch (e: Exception) {
        }
        synchronized(this) {
            try {
                writer?.close()
            } catch (e: Exception) {
            }
            writer = null
        }
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onLocationChanged(l: Location) {
        lat = l.latitude
        lon = l.longitude
        speed = if (l.hasSpeed()) l.speed * 3.6f else 0f
        hasFix = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val d = 180.0 / Math.PI
        if (flat) {
            rawRoll = (atan2(x.toDouble(), z.toDouble()) * d).toFloat()
            rawPitch = (atan2(y.toDouble(), z.toDouble()) * d).toFloat()
        } else {
            rawRoll = (atan2(x.toDouble(), y.toDouble()) * d).toFloat()
            rawPitch = (atan2(-z.toDouble(), sqrt((x * x + y * y).toDouble())) * d).toFloat()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    @Deprecated("compat")
    override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}

    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}
