package com.r2d2.headtracker

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    // Componentes da UI
    private lateinit var startButton: Button
    private lateinit var ipInput: EditText
    private lateinit var calibrateButton: Button
    private lateinit var yawBar: ProgressBar
    private lateinit var pitchBar: ProgressBar
    private lateinit var rollBar: ProgressBar

    // Variáveis de rede e estado
    private var sending = false
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val serverPort = 4242

    // Offsets de calibração
    private var yawOffset = 0.0
    private var pitchOffset = 0.0
    private var rollOffset = 0.0

    // Variáveis para Fusão de Sensores
    private val fusedOrientation = floatArrayOf(0f, 0f, 0f) // [Yaw, Pitch, Roll] em radianos
    private var timestamp: Long = 0
    private val NS2S = 1.0f / 1000000000.0f

    // Constante do Filtro Complementar
    private val alpha = 0.98f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Associações de UI
        ipInput = findViewById(R.id.editTextIP)
        startButton = findViewById(R.id.buttonStart)
        calibrateButton = findViewById(R.id.buttonCalibrate)
        yawBar = findViewById(R.id.yawBar)
        pitchBar = findViewById(R.id.pitchBar)
        rollBar = findViewById(R.id.rollBar)
        findViewById<android.widget.SeekBar>(R.id.seekBarSmoothing).visibility = android.widget.SeekBar.GONE
        findViewById<TextView>(R.id.textSmoothing).visibility = TextView.GONE

        ipInput.setText("192.168.1.9")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (gyroscope == null || accelerometer == null) {
            Toast.makeText(this, "Erro: Sensores não encontrados!", Toast.LENGTH_LONG).show()
            startButton.isEnabled = false
            return
        }

        calibrateButton.setOnClickListener {
            yawOffset = Math.toDegrees(fusedOrientation[0].toDouble())
            pitchOffset = Math.toDegrees(fusedOrientation[1].toDouble())
            rollOffset = Math.toDegrees(fusedOrientation[2].toDouble())
            Toast.makeText(this, "Calibrado! Posição zerada.", Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Digite o IP do PC", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                try {
                    serverAddress = InetAddress.getByName(ip)
                    udpSocket = DatagramSocket()
                    sending = true
                    runOnUiThread {
                        timestamp = 0
                        fusedOrientation.fill(0f)
                        yawOffset = 0.0; pitchOffset = 0.0; rollOffset = 0.0
                        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                        startButton.isEnabled = false
                        calibrateButton.isEnabled = true
                        ipInput.isEnabled = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!sending || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // O acelerômetro calcula a referência estável para Pitch e Roll
                val accPitch = atan2(event.values[0], sqrt(event.values[1] * event.values[1] + event.values[2] * event.values[2]))
                val accRoll = atan2(-event.values[1], event.values[2])

                // Ele apenas contribui com sua parte (2%) no filtro e NÃO mexe no timestamp.
                fusedOrientation[1] = fusedOrientation[1] * alpha + accPitch * (1.0f - alpha)
                fusedOrientation[2] = fusedOrientation[2] * alpha + accRoll * (1.0f - alpha)
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Apenas o giroscópio controla o tempo para o cálculo de integração.
                if (timestamp == 0L) {
                    timestamp = event.timestamp
                    return
                }
                val dt = (event.timestamp - timestamp) * NS2S
                timestamp = event.timestamp

                // O giroscópio provê o movimento fluido para os 3 eixos
                fusedOrientation[0] += event.values[2] * dt // Yaw
                fusedOrientation[1] += event.values[0] * dt // Pitch
                fusedOrientation[2] += event.values[1] * dt // Roll

                // E dispara o envio de dados
                processAndSendData()
            }
        }
    }

    private fun processAndSendData() {
        val yawDegrees = Math.toDegrees(fusedOrientation[0].toDouble())
        val pitchDegrees = Math.toDegrees(fusedOrientation[1].toDouble())
        val rollDegrees = Math.toDegrees(fusedOrientation[2].toDouble())

        val finalYaw = yawDegrees - yawOffset
        val finalPitch = pitchDegrees - pitchOffset
        val finalRoll = rollDegrees - rollOffset

        if (!finalYaw.isFinite() || !finalPitch.isFinite() || !finalRoll.isFinite()) {
            return
        }

        sendData(0.0, 0.0, 0.0, finalYaw, finalPitch, finalRoll)

        runOnUiThread {
            yawBar.progress = ((finalYaw % 360 + 360) % 360).toInt()
            pitchBar.progress = (finalPitch + 90).coerceIn(0.0, 180.0).toInt()
            rollBar.progress = (finalRoll + 180).coerceIn(0.0, 360.0).toInt()
        }
    }

    private fun sendData(vararg values: Double) {
        if (values.size != 6) return
        thread {
            try {
                val buffer = ByteBuffer.allocate(48)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (value in values) {
                    buffer.putDouble(value)
                }
                val packet = DatagramPacket(buffer.array(), buffer.capacity(), serverAddress, serverPort)
                udpSocket?.send(packet)
            } catch (ex: Exception) {
                // Silencioso
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        udpSocket?.close()
        sending = false
    }
}