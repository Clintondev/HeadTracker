package com.r2d2.headtracker

import android.app.Activity
import android.content.Intent
import android.os.*
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button

class DarkScreenActivity : Activity() {
    private val CALIBRATE_HOLD_MS = 1_000L
    private var touchDownTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    // Runnable que dispara a calibração após 1 s de toque contínuo
    private val calibrateRunnable = Runnable {
        startService(
            Intent(this, HeadTrackerService::class.java)
                .setAction("CALIBRATE")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // mantém a tela ligada (mas com brilho zero)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.attributes = window.attributes.apply {
            screenBrightness = 0f
        }

        setContentView(R.layout.activity_dark_screen)
        findViewById<Button>(R.id.buttonRestore).setOnClickListener {
            finish()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = SystemClock.elapsedRealtime()
                // agenda calibrar se mantiver pressionado 1 s
                handler.postDelayed(calibrateRunnable, CALIBRATE_HOLD_MS)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = SystemClock.elapsedRealtime() - touchDownTime
                handler.removeCallbacks(calibrateRunnable)

                if (held < CALIBRATE_HOLD_MS) {
                    // foi um toque curto: alterna PAUSE/RESUME
                    val nextAction =
                        if (HeadTrackerService.isPaused) "RESUME" else "PAUSE"
                    startService(
                        Intent(this, HeadTrackerService::class.java)
                            .setAction(nextAction)
                    )
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(calibrateRunnable)
    }
}
