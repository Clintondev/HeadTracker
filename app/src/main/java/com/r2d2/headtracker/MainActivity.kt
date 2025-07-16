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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var accelSensor: Sensor? = null

    private lateinit var startButton: Button
    private lateinit var ipInput: EditText
    private lateinit var calibrateButton: Button
    private lateinit var smoothingSeekBar: SeekBar
    private lateinit var smoothingLabel: TextView
    private lateinit var yawBar: ProgressBar
    private lateinit var pitchBar: ProgressBar
    private lateinit var rollBar: ProgressBar

    private var sending = false
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val serverPort = 4242

    // valores brutos
    private var rawYaw = 0.0
    private var rawPitch = 0.0
    private var rawRoll = 0.0
    private var accX = 0f
    private var accY = 0f
    private var accZ = 0f

    // offsets de calibração
    private var yawOffset = 0.0
    private var pitchOffset = 0.0
    private var rollOffset = 0.0

    // smoothing
    private var smoothingFactor = 0.1  // 0 = sem smoothing, 1 = totalmente responsivo
    private var smoothYaw = 0.0
    private var smoothPitch = 0.0
    private var smoothRoll = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.editTextIP)
        startButton = findViewById(R.id.buttonStart)
        calibrateButton = findViewById(R.id.buttonCalibrate)
        smoothingSeekBar = findViewById(R.id.seekBarSmoothing)
        smoothingLabel = findViewById(R.id.textSmoothing)
        yawBar = findViewById(R.id.yawBar)
        pitchBar = findViewById(R.id.pitchBar)
        rollBar = findViewById(R.id.rollBar)

        ipInput.setText("192.168.1.9")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (rotationSensor == null) {
            Toast.makeText(this, "Nenhum sensor de rotação disponível", Toast.LENGTH_LONG).show()
            startButton.isEnabled = false
            return
        }
        if (accelSensor == null) {
            Toast.makeText(this, "Nenhum sensor de aceleração disponível", Toast.LENGTH_SHORT).show()
        }

        // configura SeekBar de smoothing
        smoothingSeekBar.max = 100
        smoothingSeekBar.progress = (smoothingFactor * 100).toInt()
        smoothingLabel.text = "Smoothing: ${smoothingSeekBar.progress}%"
        smoothingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                smoothingFactor = progress / 100.0
                smoothingLabel.text = "Smoothing: $progress%"
            }
            override fun onStartTrackingTouch(seek: SeekBar) {}
            override fun onStopTrackingTouch(seek: SeekBar) {}
        })

        // botão calibrar offset
        calibrateButton.setOnClickListener {
            yawOffset = rawYaw
            pitchOffset = rawPitch
            rollOffset = rawRoll
            Toast.makeText(this, "Calibrado! Offset aplicado.", Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Digite o IP do PC antes de iniciar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                try {
                    serverAddress = InetAddress.getByName(ip)
                    udpSocket = DatagramSocket()
                    sending = true
                    runOnUiThread {
                        sensorManager.registerListener(
                            this,
                            rotationSensor,
                            SensorManager.SENSOR_DELAY_GAME
                        )
                        accelSensor?.let {
                            sensorManager.registerListener(
                                this, it, SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        startButton.isEnabled = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this, "Erro ao configurar socket UDP", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!sending || event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                val ori = FloatArray(3)
                SensorManager.getOrientation(rotMat, ori)
                rawYaw = Math.toDegrees(ori[0].toDouble())
                rawPitch = Math.toDegrees(ori[1].toDouble())
                rawRoll = Math.toDegrees(ori[2].toDouble())

                // aplica offsets
                val offYaw = rawYaw - yawOffset
                val offPitch = rawPitch - pitchOffset
                val offRoll = rawRoll - rollOffset

                // smoothing exponencial
                smoothYaw = smoothYaw + smoothingFactor * (offYaw - smoothYaw)
                smoothPitch = smoothPitch + smoothingFactor * (offPitch - smoothPitch)
                smoothRoll = smoothRoll + smoothingFactor * (offRoll - smoothRoll)

                // atualiza UI das barras
                val yawProg = (smoothYaw + 180).coerceIn(0.0, 360.0).toInt()
                val pitchProg = (smoothPitch + 90).coerceIn(0.0, 180.0).toInt()
                val rollProg = (smoothRoll + 180).coerceIn(0.0, 360.0).toInt()
                runOnUiThread {
                    yawBar.progress = yawProg
                    pitchBar.progress = pitchProg
                    rollBar.progress = rollProg
                }

                sendData(smoothYaw, smoothPitch, smoothRoll, accX.toDouble(), accY.toDouble(), accZ.toDouble())
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accX = event.values[0]
                accY = event.values[1]
                accZ = event.values[2]
            }
        }
    }

    private fun sendData(vararg values: Double) {
        val data = values.joinToString(",") { String.format("%.2f", it) }
        thread {
            try {
                val packet = DatagramPacket(data.toByteArray(), data.length, serverAddress, serverPort)
                udpSocket?.send(packet)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        udpSocket?.close()
    }
}