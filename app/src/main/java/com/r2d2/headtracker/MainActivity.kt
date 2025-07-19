package com.r2d2.headtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.filament.utils.*
import java.nio.ByteBuffer
import kotlin.math.*
class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    companion object {
        init {
            Utils.init()
        }
    }


    // --- Componentes da UI de controle ---
    private lateinit var radioGroupTransport: RadioGroup
    private lateinit var radioUdp: RadioButton
    private lateinit var radioUsb: RadioButton
    private lateinit var startStopSwitch: MaterialSwitch
    private lateinit var calibrateButton: MaterialButton
    private lateinit var darkenButton: MaterialButton
    private lateinit var ipInput: TextInputEditText

    // --- Componentes para o 3D ---
    private lateinit var surfaceView: SurfaceView
    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: ModelViewer

    // --- Variáveis para suavização de movimento ---
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var smoothedRoll = 0f
    private val smoothingFactor = 0.1f // Valor entre 0.0 e 1.0. Menor = mais suave.

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HeadTrackerService.ACTION_UPDATE_UI) {
                val yaw = intent.getFloatExtra(HeadTrackerService.EXTRA_YAW, 0f)
                val pitch = intent.getFloatExtra(HeadTrackerService.EXTRA_PITCH, 0f)
                val roll = intent.getFloatExtra(HeadTrackerService.EXTRA_ROLL, 0f)

                // AJUSTE DE SENSIBILIDADE: Suaviza os valores do sensor
                smoothedYaw += (yaw - smoothedYaw) * smoothingFactor
                smoothedPitch += (pitch - smoothedPitch) * smoothingFactor
                smoothedRoll += (roll - smoothedRoll) * smoothingFactor

                // Usa os valores suavizados para a rotação
                val quaternion = eulerToQuaternion(
                    Math.toRadians(smoothedPitch.toDouble()),
                    Math.toRadians(smoothedYaw.toDouble()),
                    Math.toRadians(smoothedRoll.toDouble())
                )

                modelViewer.asset?.let { asset ->
                    val transformManager = modelViewer.engine.transformManager
                    val rootInstance = transformManager.getInstance(asset.root)

// 1) Correção de rotação: combina rotação inicial + rotação do sensor
                    val initialRotation = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 90f).toMatrix()
                    val sensorRotation  = quaternion.toMatrix()
                    val finalRotation   = initialRotation * sensorRotation

// 2) Cria Mat4 de escala uniforme (50%)
                    val scale       = 0.2f
                    val scaleMatrix = Mat4.of(
                        scale, 0f,    0f,    0f,
                        0f,    scale, 0f,    0f,
                        0f,    0f,    scale, 0f,
                        0f,    0f,    0f,    1f
                    )

// 3) Junta escala + rotação
                    val finalTransform = scaleMatrix * finalRotation

// 4) Aplica ao modelo
                    transformManager.setTransform(rootInstance, finalTransform.toFloatArray())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Inicialização dos componentes (sem alterações) ---
        radioGroupTransport = findViewById(R.id.radioGroupTransport)
        radioUdp = findViewById(R.id.radioUdp)
        radioUsb = findViewById(R.id.radioUsb)
        ipInput = findViewById(R.id.editTextIP)
        startStopSwitch = findViewById(R.id.startStopSwitch)
        calibrateButton = findViewById(R.id.calibrateButton)
        darkenButton = findViewById(R.id.darkenButton)
        ipInput.setText("192.168.1.9")
        calibrateButton.isEnabled = false
        darkenButton.isEnabled = false

        // --- Inicialização da visualização 3D ---
        surfaceView = findViewById(R.id.surface_view)
        choreographer = Choreographer.getInstance()
        modelViewer = ModelViewer(surfaceView)
        surfaceView.setOnTouchListener(modelViewer)
        loadGlb("head.glb")

        // --- Listeners de controle (sem alterações) ---
        radioGroupTransport.setOnCheckedChangeListener { _, checkedId ->
            ipInput.isEnabled = (checkedId == R.id.radioUdp)
        }
        startStopSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val transport = if (radioUsb.isChecked) "USB" else "UDP"
                val ip = ipInput.text.toString().trim()
                if (transport == "UDP" && ip.isEmpty()) {
                    Toast.makeText(this, "Digite o IP do PC", Toast.LENGTH_SHORT).show()
                    startStopSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                Intent(this, HeadTrackerService::class.java).apply {
                    putExtra("EXTRA_TRANSPORT", transport)
                    if (transport == "UDP") putExtra("EXTRA_IP", ip)
                }.also { startService(it) }
                calibrateButton.isEnabled = true
                radioGroupTransport.isEnabled = false
                ipInput.isEnabled = false
                darkenButton.isEnabled = true
            } else {
                stopService(Intent(this, HeadTrackerService::class.java))
                calibrateButton.isEnabled = false
                radioGroupTransport.isEnabled = true
                ipInput.isEnabled = radioUdp.isChecked
                darkenButton.isEnabled = false
            }
        }
        calibrateButton.setOnClickListener {
            Intent(this, HeadTrackerService::class.java)
                .setAction("CALIBRATE")
                .also { startService(it) }
            Toast.makeText(this, "Posição neutra calibrada", Toast.LENGTH_SHORT).show()
        }
        darkenButton.setOnClickListener {
            startActivity(Intent(this, DarkScreenActivity::class.java))
        }
    }

    // --- Funções de ciclo de vida (sem alterações) ---
    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(this)
        val intentFilter = IntentFilter(HeadTrackerService.ACTION_UPDATE_UI)
        ContextCompat.registerReceiver(this, uiUpdateReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(this)
        unregisterReceiver(uiUpdateReceiver)
    }
    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(this)
    }
    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        modelViewer.render(frameTimeNanos)
    }

    // --- Funções auxiliares ---
    private fun loadGlb(name: String) {
        try {
            val buffer = readAsset(name)
            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube()

            // AJUSTE DE ZOOM: Afasta a câmera para o modelo não ficar tão grande na tela.
            val camera = modelViewer.camera
            camera.lookAt(0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        } catch (e: Exception) {
            Toast.makeText(this, "Erro: $name não encontrado na pasta assets", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun readAsset(assetName: String): ByteBuffer {
        val input = assets.open(assetName.substringAfter("assets/"))
        val bytes = ByteArray(input.available())
        input.read(bytes)
        return ByteBuffer.wrap(bytes)
    }

    // Função mantida para converter ângulos em Quaternion
    private fun eulerToQuaternion(pitch: Double, yaw: Double, roll: Double): Quaternion {
        val cy = cos(yaw * 0.5)
        val sy = sin(yaw * 0.5)
        val cp = cos(pitch * 0.5)
        val sp = sin(pitch * 0.5)
        val cr = cos(roll * 0.5)
        val sr = sin(roll * 0.5)
        return Quaternion(
            (cy * cp * sr - sy * sp * cr).toFloat(),
            (sy * cp * sr + cy * sp * cr).toFloat(),
            (sy * cp * cr - cy * sp * sr).toFloat(),
            (cy * cp * cr + sy * sp * sr).toFloat()
        )
    }
}