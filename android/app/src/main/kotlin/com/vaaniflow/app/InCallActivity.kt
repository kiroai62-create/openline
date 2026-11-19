package com.vaaniflow.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vaaniflow.app.telecom.CallRegistry
import com.vaaniflow.app.telecom.GatewayInCallService
import com.vaaniflow.app.ui.InCallScreen
import com.vaaniflow.app.ui.VaaniTheme

/** Full-screen call UI: incoming, outgoing, active, conference and AI-handled calls. */
class InCallActivity : ComponentActivity(), CallRegistry.Listener {
    private var version by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private var proximity: PowerManager.WakeLock? = null
    private val finishIfIdle = Runnable { if (CallRegistry.snapshot().isEmpty()) finish() }
    private val ticker = object : Runnable {
        override fun run() {
            version++
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        proximity = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "vaaniflow:incall")
        }.getOrNull()
        setContent {
            VaaniTheme(dark = true) {
                @Suppress("UNUSED_EXPRESSION") version // recompose on every call change and every second
                InCallScreen(
                    calls = CallRegistry.snapshot(),
                    audio = GatewayInCallService.audioState.value,
                    onAddCall = { startActivity(Intent(this, DialerActivity::class.java).setAction(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onMinimise = { moveTaskToBack(true) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CallRegistry.addListener(this)
        handler.post(ticker)
        onCallsChanged()
    }

    override fun onStop() {
        CallRegistry.removeListener(this)
        handler.removeCallbacks(ticker)
        releaseProximity()
        super.onStop()
    }

    override fun onCallsChanged() {
        runOnUiThread {
            version++
            val calls = CallRegistry.snapshot()
            if (calls.isEmpty()) {
                handler.removeCallbacks(finishIfIdle)
                handler.postDelayed(finishIfIdle, 1_200)
            }
            // Screen off at the ear on a personal call using the earpiece.
            val personalActive = calls.any { CallRegistry.androidState(it.call) == Call.STATE_ACTIVE && !CallRegistry.isAiOwned(it.callId) }
            val earpiece = GatewayInCallService.audioState.value?.route == CallAudioState.ROUTE_EARPIECE
            if (personalActive && earpiece) acquireProximity() else releaseProximity()
        }
    }

    private fun acquireProximity() {
        val lock = proximity ?: return
        if (!lock.isHeld) runCatching { lock.acquire(60 * 60_000L) }
    }

    private fun releaseProximity() {
        val lock = proximity ?: return
        if (lock.isHeld) runCatching { lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }
}
