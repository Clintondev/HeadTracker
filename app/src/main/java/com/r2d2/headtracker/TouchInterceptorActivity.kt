package com.r2d2.headtracker

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager.LayoutParams

class TouchInterceptorActivity : Activity() {
    // timestamps dos últimos três toques
    private val lastTimes = LongArray(3) { 0L }
    // handler para o delay
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android O MR1+ — liga tela e dispensa keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        }

        // mantém Activity ativa e sobre a lockscreen
        window.addFlags(
            LayoutParams.FLAG_KEEP_SCREEN_ON or
                    LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    LayoutParams.FLAG_TURN_SCREEN_ON or
                    LayoutParams.FLAG_DISMISS_KEYGUARD or
                    LayoutParams.FLAG_NOT_TOUCHABLE
        )

        // ─── Delay de 30s antes de “apagar” o backlight ───
        handler.postDelayed({
            window.attributes = window.attributes.apply {
                screenBrightness = 0f  // 0.0 = escuro total
            }
            window.setAttributes(window.attributes)

        }, 5_000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {

            val now = SystemClock.elapsedRealtime()
            lastTimes[2] = lastTimes[1]
            lastTimes[1] = lastTimes[0]
            lastTimes[0] = now

            val dt1 = lastTimes[0] - lastTimes[1]
            val dt2 = lastTimes[1] - lastTimes[2]

            when {
                // triple tap rápido → PAUSE/RESUME
                dt1 < 500 && dt2 < 500 -> {
                    Intent(this, HeadTrackerService::class.java).apply {
                        action = if (dt2 < dt1) "RESUME" else "PAUSE"
                    }.also { startService(it) }
                }
                // double tap rápido → CALIBRATE
                dt1 < 500 -> {
                    Intent(this, HeadTrackerService::class.java).apply {
                        action = "CALIBRATE"
                    }.also { startService(it) }
                }
            }
            return true  // consome o evento, sem overlay de volume
        }
        return super.dispatchKeyEvent(event)
    }
}
