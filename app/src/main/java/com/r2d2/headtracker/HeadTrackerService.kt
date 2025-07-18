package com.r2d2.headtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.sqrt


class HeadTrackerService : Service(), SensorEventListener {
    //–– WakeLock e sensores
    private lateinit var pm: PowerManager.WakeLock
    private lateinit var sensorManager: SensorManager
    private var gyro: Sensor? = null
    private var accel: Sensor? = null

    //–– Fusão de sensores
    private val fusedOrientation = FloatArray(3)
    private var timestamp: Long = 0L
    private val NS2S = 1.0f / 1_000_000_000.0f
    private val alpha = 0.98f

    //–– Offsets de calibração
    private var yawOffset = 0.0
    private var pitchOffset = 0.0
    private var rollOffset = 0.0

    //–– Transporte e controle
    private var transport = "UDP"
    private var paused = false

    //–– UDP
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val serverPort = 4242

    //–– USB-Serial helper (veja UsbSerialHelper.kt)
    private lateinit var usbHelper: UsbSerialHelper

    //–– MediaSession para captura de botões de volume
    private lateinit var mediaSession: MediaSessionCompat
    private val volumePressTimes = mutableListOf<Long>()

    override fun onCreate() {
        super.onCreate()

        // 1) WakeLock parcial para manter CPU + sensores ativos
        val pmgr = getSystemService(POWER_SERVICE) as PowerManager
        pm = pmgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadTracker:Wake")
        pm.acquire()

        // 2) Notificação em foreground
        val chanId = "headtracker_foreground"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(chanId, "HeadTracker", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(this, chanId)
            .setContentTitle("HeadTracker ativo")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(1, notif)

        // 3) Inicializa sensores
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)

        // 4) Prepara socket UDP
        udpSocket = DatagramSocket()

        // 5) Prepara USB-Serial
        usbHelper = UsbSerialHelper(this)

        // 6) MediaSession (continua opcional, para media-buttons)
        mediaSession = MediaSessionCompat(this, "HeadTrackerSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(intent: Intent?): Boolean {
                    // ... mesma lógica de duplo/triplo toque ...
                    return super.onMediaButtonEvent(intent)
                }
            })
            isActive = true
        }
        MediaButtonReceiver.handleIntent(mediaSession, Intent())

        // 7) Inicia a Activity invisível que liga a tela e captura volume
        Intent(this, TouchInterceptorActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }.also { startActivity(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Redireciona os eventos de MEDIA_BUTTON para a MediaSessionCompat
        MediaButtonReceiver.handleIntent(mediaSession, intent)  // ← adicione esta linha

        // --- resto do seu código ---
        intent?.getStringExtra("EXTRA_TRANSPORT")?.let { transport = it }
        if (transport == "USB") usbHelper.open()
        when (intent?.action) {
            "CALIBRATE" -> calibrateOffsets()
            "PAUSE"     -> paused = true
            "RESUME"    -> paused = false
        }
        intent?.getStringExtra("EXTRA_IP")?.let {
            serverAddress = InetAddress.getByName(it)
        }
        return START_STICKY
    }


    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        pm.release()
        udpSocket?.close()
        usbHelper.close()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val accPitch = atan2(
                    event.values[0],
                    sqrt(event.values[1]*event.values[1] + event.values[2]*event.values[2])
                )
                val accRoll = atan2(-event.values[1], event.values[2])
                fusedOrientation[1] = fusedOrientation[1]*alpha + accPitch*(1-alpha)
                fusedOrientation[2] = fusedOrientation[2]*alpha + accRoll*(1-alpha)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (timestamp == 0L) {
                    timestamp = event.timestamp
                    return
                }
                val dt = (event.timestamp - timestamp)*NS2S
                timestamp = event.timestamp
                fusedOrientation[0] += event.values[2]*dt
                fusedOrientation[1] += event.values[0]*dt
                fusedOrientation[2] += event.values[1]*dt
                processAndSendData()
            }
        }
    }

    private fun processAndSendData() {
        if (paused) return
        val yaw   = Math.toDegrees(fusedOrientation[0].toDouble()) - yawOffset
        val pitch = Math.toDegrees(fusedOrientation[1].toDouble()) - pitchOffset
        val roll  = Math.toDegrees(fusedOrientation[2].toDouble()) - rollOffset
        if (!yaw.isFinite() || !pitch.isFinite() || !roll.isFinite()) return

        val buf = ByteBuffer.allocate(6 * Double.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(0.0).putDouble(0.0).putDouble(0.0)
        buf.putDouble(yaw).putDouble(pitch).putDouble(roll)
        val data = buf.array()

        thread {
            try {
                if (transport == "USB") {
                    usbHelper.write(data)
                } else {
                    serverAddress?.let {
                        val pkt = DatagramPacket(data, data.size, it, serverPort)
                        udpSocket?.send(pkt)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun calibrateOffsets() {
        yawOffset   = Math.toDegrees(fusedOrientation[0].toDouble())
        pitchOffset = Math.toDegrees(fusedOrientation[1].toDouble())
        rollOffset  = Math.toDegrees(fusedOrientation[2].toDouble())
    }
}
