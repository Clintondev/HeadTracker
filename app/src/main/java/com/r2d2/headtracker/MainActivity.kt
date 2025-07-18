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

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    companion object {
        init {
            Utils.init()
        }
    }

    // --- Componentes da UI (sem alterações) ---
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

    // --- Variáveis de estado da rotação ---
    // Armazena a transformação "base" do modelo (que inclui escala, translação e rotação calibrada)
    private var baseTransform: Mat4 = Mat4()
    // Armazena a rotação que vem do sensor
    private var sensorDeltaRotation: Mat4 = Mat4()

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HeadTrackerService.ACTION_UPDATE_UI) {
                val yaw = intent.getFloatExtra(HeadTrackerService.EXTRA_YAW, 0f)
                // CORREÇÃO DE BUG: Estava lendo YAW duas vezes
                val pitch = intent.getFloatExtra(HeadTrackerService.EXTRA_PITCH, 0f)
                val roll = intent.getFloatExtra(HeadTrackerService.EXTRA_ROLL, 0f)

                // Converte os ângulos do sensor em uma matriz de rotação
                sensorDeltaRotation = eulerToQuaternion(
                    Math.toRadians(pitch.toDouble()),
                    Math.toRadians(yaw.toDouble()),
                    Math.toRadians(roll.toDouble())
                ).toMatrix()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Inicialização (sem alterações) ---
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

        // --- Inicialização 3D ---
        surfaceView = findViewById(R.id.surface_view)
        choreographer = Choreographer.getInstance()
        modelViewer = ModelViewer(surfaceView)
        surfaceView.setOnTouchListener(modelViewer)
        loadGlb("head.glb")

        // --- Listeners ---
        calibrateButton.setOnClickListener {
            // Define a transformação ATUAL como a nova base
            modelViewer.asset?.let { asset ->
                val tm = modelViewer.engine.transformManager
                val root = tm.getInstance(asset.root)
                val currentTransform = FloatArray(16)
                tm.getTransform(root, currentTransform)
                baseTransform = Mat4.of(*currentTransform)
            }

            // Manda o serviço zerar os offsets dele também
            Intent(this, HeadTrackerService::class.java).setAction("CALIBRATE").also { startService(it) }
            Toast.makeText(this, "Posição neutra calibrada", Toast.LENGTH_SHORT).show()
        }

        // --- Outros Listeners (sem alterações) ---
        radioGroupTransport.setOnCheckedChangeListener { _, checkedId -> ipInput.isEnabled = (checkedId == R.id.radioUdp) }
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
        darkenButton.setOnClickListener { startActivity(Intent(this, DarkScreenActivity::class.java)) }
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

    // O loop de renderização agora aplica a rotação do sensor sobre a transformação base
    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        modelViewer.asset?.let { asset ->
            val tm = modelViewer.engine.transformManager
            val root = tm.getInstance(asset.root)
            val finalTransform = baseTransform * sensorDeltaRotation
            tm.setTransform(root, finalTransform.toFloatArray())
        }
        modelViewer.render(frameTimeNanos)
    }

    // --- Funções Auxiliares ---
    private fun loadGlb(name: String) {
        try {
            val buffer = readAsset(name)
            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube()

            // Define a transformação inicial (com escala e correção de rotação)
            modelViewer.asset?.let { asset ->
                val tm = modelViewer.engine.transformManager
                val root = tm.getInstance(asset.root)
                val transform = FloatArray(16)
                tm.getTransform(root, transform)
                val initialMatrix = Mat4.of(*transform)
                val correction = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 90f).toMatrix()
                baseTransform = initialMatrix * correction
            }

            // Afasta a câmera
            val camera = modelViewer.camera
            camera.lookAt(0.0, 0.0, 8.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro: $name não encontrado", Toast.LENGTH_LONG).show()
        }
    }

    private fun readAsset(assetName: String): ByteBuffer {
        val input = assets.open(assetName.substringAfter("assets/"))
        val bytes = ByteArray(input.available())
        input.read(bytes)
        return ByteBuffer.wrap(bytes)
    }

    private fun eulerToQuaternion(pitch: Double, yaw: Double, roll: Double): Quaternion {
        return Quaternion.fromEuler(
            Math.toDegrees(pitch).toFloat(),
            Math.toDegrees(yaw).toFloat(),
            Math.toDegrees(roll).toFloat()
        )
    }
}