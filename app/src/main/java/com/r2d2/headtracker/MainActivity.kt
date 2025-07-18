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

    private lateinit var radioGroupTransport: RadioGroup
    private lateinit var radioUdp: RadioButton
    private lateinit var radioUsb: RadioButton
    private lateinit var startStopButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var darkenButton: Button
    private lateinit var ipInput: EditText
    private lateinit var yawBar: ProgressBar
    private lateinit var pitchBar: ProgressBar
    private lateinit var rollBar: ProgressBar

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // liga componentes
        radioGroupTransport = findViewById(R.id.radioGroupTransport)
        radioUdp            = findViewById(R.id.radioUdp)
        radioUsb            = findViewById(R.id.radioUsb)
        ipInput             = findViewById(R.id.editTextIP)
        startStopButton     = findViewById(R.id.buttonStartStop)
        calibrateButton     = findViewById(R.id.buttonCalibrate)
        darkenButton        = findViewById(R.id.buttonDarken)
        yawBar              = findViewById(R.id.yawBar)
        pitchBar            = findViewById(R.id.pitchBar)
        rollBar             = findViewById(R.id.rollBar)

        // inicial
        radioUdp.isChecked = true
        ipInput.setText("192.168.1.9")
        calibrateButton.isEnabled = false
        startStopButton.text = "Iniciar"

        radioGroupTransport.setOnCheckedChangeListener { _, checkedId ->
            ipInput.isEnabled = (checkedId == R.id.radioUdp)
        }

        startStopButton.setOnClickListener {
            if (!isRunning) {
                // inicia serviço
                val transport = if (radioUsb.isChecked) "USB" else "UDP"
                val ip = ipInput.text.toString().trim()
                if (transport == "UDP" && ip.isEmpty()) {
                    Toast.makeText(this, "Digite o IP do PC", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Intent(this, HeadTrackerService::class.java).apply {
                    putExtra("EXTRA_TRANSPORT", transport)
                    if (transport == "UDP") putExtra("EXTRA_IP", ip)
                }.also { startService(it) }

                isRunning = true
                startStopButton.text = "Parar"
                calibrateButton.isEnabled = true
                radioGroupTransport.isEnabled = false
                ipInput.isEnabled = false
            } else {
                // para serviço
                stopService(Intent(this, HeadTrackerService::class.java))
                isRunning = false
                startStopButton.text = "Iniciar"
                calibrateButton.isEnabled = false
                radioGroupTransport.isEnabled = true
                ipInput.isEnabled = true
            }
        }

        calibrateButton.setOnClickListener {
            Intent(this, HeadTrackerService::class.java)
                .setAction("CALIBRATE")
                .also { startService(it) }
            Toast.makeText(this, "Calibrando…", Toast.LENGTH_SHORT).show()
        }

        darkenButton.setOnClickListener {
            startActivity(Intent(this, DarkScreenActivity::class.java))
        }
    }
}
