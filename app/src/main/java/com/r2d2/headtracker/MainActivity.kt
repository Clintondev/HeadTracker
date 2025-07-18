package com.r2d2.headtracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast

class MainActivity : Activity() {

    // Componentes da UI
    private lateinit var radioGroupTransport: RadioGroup
    private lateinit var radioUdp: RadioButton
    private lateinit var radioUsb: RadioButton
    private lateinit var startButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var ipInput: EditText
    private lateinit var yawBar: ProgressBar
    private lateinit var pitchBar: ProgressBar
    private lateinit var rollBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Liga componentes
        radioGroupTransport = findViewById(R.id.radioGroupTransport)
        radioUdp            = findViewById(R.id.radioUdp)
        radioUsb            = findViewById(R.id.radioUsb)
        ipInput             = findViewById(R.id.editTextIP)
        startButton         = findViewById(R.id.buttonStart)
        calibrateButton     = findViewById(R.id.buttonCalibrate)
        yawBar              = findViewById(R.id.yawBar)
        pitchBar            = findViewById(R.id.pitchBar)
        rollBar             = findViewById(R.id.rollBar)

        // Valores iniciais
        radioUdp.isChecked = true
        ipInput.setText("192.168.1.9")
        calibrateButton.isEnabled = false

        // Habilita/desabilita o campo de IP conforme seleção
        radioGroupTransport.setOnCheckedChangeListener { _, checkedId ->
            ipInput.isEnabled = (checkedId == R.id.radioUdp)
        }

        startButton.setOnClickListener {
            val transport = if (radioUsb.isChecked) "USB" else "UDP"
            val ip = ipInput.text.toString().trim()
            if (transport == "UDP" && ip.isEmpty()) {
                Toast.makeText(this, "Digite o IP do PC", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Prepara intent para o serviço
            Intent(this, HeadTrackerService::class.java).apply {
                putExtra("EXTRA_TRANSPORT", transport)
                if (transport == "UDP") putExtra("EXTRA_IP", ip)
            }.also { startService(it) }

            // Ajusta UI
            startButton.isEnabled         = false
            radioGroupTransport.isEnabled = false
            radioUdp.isEnabled            = false
            radioUsb.isEnabled            = false
            ipInput.isEnabled             = false
            calibrateButton.isEnabled     = true
            Toast.makeText(this, "HeadTracker iniciado ($transport)", Toast.LENGTH_SHORT).show()
        }

        calibrateButton.setOnClickListener {
            // Envia calibração ao serviço
            Intent(this, HeadTrackerService::class.java).apply {
                action = "CALIBRATE"
            }.also { startService(it) }
            Toast.makeText(this, "Calibração enviada ao serviço", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // Para o serviço ao sair
        stopService(Intent(this, HeadTrackerService::class.java))
        super.onDestroy()
    }
}