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
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
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

    //–– Transporte
    private var transport = "UDP"

    companion object {
        /** Estado atual: true = pausado, false = rodando */
        @Volatile
        var isPaused = false

        // Constantes para comunicação com a Activity
        const val ACTION_UPDATE_UI = "com.r2d2.headtracker.UPDATE_UI"
        const val EXTRA_YAW = "EXTRA_YAW"
        const val EXTRA_PITCH = "EXTRA_PITCH"
        const val EXTRA_ROLL = "EXTRA_ROLL"
    }

    //–– UDP
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val serverPort = 4242

    //–– USB-Serial helper (veja UsbSerialHelper.kt)
    private lateinit var usbHelper: UsbSerialHelper

    //–– MediaSession para captura de botões de volume
    private lateinit var mediaSession: MediaSessionCompat

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
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Certifique-se que este drawable existe
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
        // Supondo que você tenha a classe UsbSerialHelper no seu projeto
        // usbHelper = UsbSerialHelper(this)

        // 6) MediaSession
        mediaSession = MediaSessionCompat(this, "HeadTrackerSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(intent: Intent?): Boolean {
                    return super.onMediaButtonEvent(intent)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        intent?.getStringExtra("EXTRA_TRANSPORT")?.let { transport = it }
        // if (transport == "USB") usbHelper.open()

        when (intent?.action) {
            "CALIBRATE" -> calibrateOffsets()
            "PAUSE"     -> isPaused = true
            "RESUME"    -> isPaused = false
        }
        intent?.getStringExtra("EXTRA_IP")?.let {
            // Rodar em uma thread para evitar NetworkOnMainThreadException
            thread {
                try {
                    serverAddress = InetAddress.getByName(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return START_STICKY
    }


    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        pm.release()
        udpSocket?.close()
        // usbHelper.close()
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (isPaused) return // Economiza processamento se estiver pausado

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()

                // Cálculo de Pitch e Roll baseado no acelerômetro
                val accPitch = atan2(event.values[0].toDouble(), sqrt(y * y + z * z)).toFloat()
                val accRoll = atan2(-y, z).toFloat()

                // Fusão complementar: dá mais peso ao valor antigo
                fusedOrientation[1] = fusedOrientation[1] * alpha + accPitch * (1 - alpha)
                fusedOrientation[2] = fusedOrientation[2] * alpha + accRoll * (1 - alpha)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (timestamp == 0L) {
                    timestamp = event.timestamp
                    return
                }
                val dt = (event.timestamp - timestamp) * NS2S
                timestamp = event.timestamp

                // Integração do giroscópio para obter a variação angular
                fusedOrientation[0] += event.values[2] * dt // Yaw
                fusedOrientation[1] += event.values[0] * dt // Pitch
                fusedOrientation[2] += event.values[1] * dt // Roll

                // Após integrar o giroscópio, processa e envia os dados
                processAndSendData()
            }
        }
    }

    private fun processAndSendData() {
        val yaw = Math.toDegrees(fusedOrientation[0].toDouble()) - yawOffset
        val pitch = Math.toDegrees(fusedOrientation[1].toDouble()) - pitchOffset
        val roll = Math.toDegrees(fusedOrientation[2].toDouble()) - rollOffset
        if (!yaw.isFinite() || !pitch.isFinite() || !roll.isFinite()) return

        // ✨ AQUI A MÁGICA ACONTECE ✨
        // Envia os dados para a MainActivity via Broadcast
        sendUpdateBroadcast(yaw.toFloat(), pitch.toFloat(), roll.toFloat())

        val buf = ByteBuffer.allocate(6 * Double.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(0.0).putDouble(0.0).putDouble(0.0)
        buf.putDouble(yaw).putDouble(pitch).putDouble(roll)
        val data = buf.array()

        thread {
            try {
                if (transport == "USB") {
                    // usbHelper.write(data)
                } else {
                    serverAddress?.let {
                        val pkt = DatagramPacket(data, data.size, it, serverPort)
                        udpSocket?.send(pkt)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Envia os dados de orientação via broadcast para a MainActivity.
     */
    private fun sendUpdateBroadcast(yaw: Float, pitch: Float, roll: Float) {
        val intent = Intent(ACTION_UPDATE_UI).apply {
            putExtra(EXTRA_YAW, yaw)
            putExtra(EXTRA_PITCH, pitch)
            putExtra(EXTRA_ROLL, roll)
        }
        sendBroadcast(intent)
    }

    private fun calibrateOffsets() {
        yawOffset   = Math.toDegrees(fusedOrientation[0].toDouble())
        pitchOffset = Math.toDegrees(fusedOrientation[1].toDouble())
        rollOffset  = Math.toDegrees(fusedOrientation[2].toDouble())
    }
}