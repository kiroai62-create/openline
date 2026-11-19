package com.vaaniflow.app.telecom

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.vaaniflow.app.InCallActivity
import com.vaaniflow.app.ai.AiSettings
import com.vaaniflow.app.audio.CallAudioBridge
import com.vaaniflow.app.transport.GatewayRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * VaaniFlow is the phone's default phone app. Every call passes through here:
 *
 *  - Personal calls (the owner answers or dials them) are ordinary calls: the phone rings, audio
 *    uses the earpiece/speaker/headset and the owner talks.
 *  - AI calls (answered or placed by the server, or handed over by the owner) move into background
 *    audio processing, so the phone stays silent and the audio streams to the server's AI.
 *
 * Whether the AI may answer an incoming call is decided on the phone (see [AiSettings]) and sent
 * to the server with the call's events.
 */
class GatewayInCallService : InCallService() {
    companion object {
        private const val TAG = "GatewayInCallService"
        @Volatile var instance: GatewayInCallService? = null
            private set
        val audioState = MutableStateFlow<CallAudioState?>(null)
    }

    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private lateinit var ringer: Ringer

    override fun onCreate() {
        super.onCreate()
        instance = this
        ringer = Ringer(this)
        CallNotifier.channels(this)
    }

    override fun onDestroy() {
        ringer.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val entry = CallRegistry.register(call)
        if (call.details.callDirection == Call.Details.DIRECTION_INCOMING && CallRegistry.androidState(call) == Call.STATE_RINGING) {
            var decision = AiSettings.decide(this, call)
            // Nobody can answer for the owner while the phone is offline or unlicensed: just ring.
            if (decision.aiMayAnswer && (!GatewayRuntime.isConnected() || !com.vaaniflow.app.license.LicenseManager.allowsCalls(this))) {
                decision = AiSettings.Decision("never", "AI offline: ringing normally")
            }
            CallRegistry.setDecision(entry.callId, decision.wire, decision.reason)
            if (decision.aiMayAnswer) scheduleFallback(entry.callId, decision.wire)
        }
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (CallRegistry.androidState(call) == Call.STATE_ACTIVE) CallRegistry.markActive(entry.callId)
                GatewayRuntime.emitCallState(entry.callId, call)
                syncCallAudio(entry.callId, call)
                refresh()
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                GatewayRuntime.emitCallState(entry.callId, call)
                refresh()
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)

        GatewayRuntime.emitCallAdded(entry.callId, call)
        syncCallAudio(entry.callId, call)
        refresh()
        // The AI answers this one at once (closed hours, busy...) or it's an API call: no screen.
        val silent = CallRegistry.isAiOwned(entry.callId) || CallRegistry.decision(entry.callId) == "now"
        if (!silent) showInCallUi()
    }

    override fun onCallRemoved(call: Call) {
        val callId = CallRegistry.callIdFor(call)
        callbacks.remove(call)?.let(call::unregisterCallback)
        if (callId != null) {
            CallAudioBridge.onCallEnded(callId)
            GatewayRuntime.emitCallRemoved(callId, call)
            val missed = call.details.callDirection == Call.Details.DIRECTION_INCOMING &&
                !CallRegistry.wasActive(callId) && !CallRegistry.wasRejected(callId) && !CallRegistry.isAiOwned(callId) &&
                call.details.disconnectCause.code != android.telecom.DisconnectCause.REJECTED
            if (missed) CallNotifier.missed(this, call.details.handle?.schemeSpecificPart.orEmpty())
        }
        CallRegistry.remove(call)
        super.onCallRemoved(call)
        refresh()
    }

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        super.onCallAudioStateChanged(state)
        audioState.value = state
        CallRegistry.notifyChanged()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** If the AI hasn't picked up a few seconds after it should have, ring for the owner after all. */
    private fun scheduleFallback(callId: String, wire: String) {
        val wait = (if (wire.startsWith("after:")) wire.substringAfter(':').toLongOrNull() ?: 12 else 1) + 7
        main.postDelayed({
            val call = CallRegistry.get(callId) ?: return@postDelayed
            if (CallRegistry.androidState(call) != Call.STATE_RINGING || CallRegistry.isAiOwned(callId)) return@postDelayed
            CallRegistry.setDecision(callId, "never", "AI didn't answer: ringing for you")
            refresh()
            showInCallUi()
        }, wait * 1000)
    }

    /** The owner gives a ringing or active call to the AI. */
    fun handToAi(callId: String) {
        val call = CallRegistry.get(callId) ?: return
        CallRegistry.markAiOwned(callId, true)
        CallRegistry.setDecision(callId, "now", "Handed to AI")
        GatewayRuntime.emitAiRequest(callId, true)
        syncCallAudio(callId, call)
        refresh()
    }

    /** The owner takes a call back from the AI and talks on the phone. */
    fun takeOver(callId: String) {
        val call = CallRegistry.get(callId) ?: return
        CallRegistry.markAiOwned(callId, false)
        CallRegistry.setDecision(callId, "never", "You took over")
        GatewayRuntime.emitAiRequest(callId, false)
        CallAudioBridge.onCallEnded(callId)
        when (CallRegistry.androidState(call)) {
            Call.STATE_AUDIO_PROCESSING -> runCatching { exitBackgroundAudioProcessing(call) }
                .onFailure { Log.w(TAG, "could not bring $callId back to the phone", it) }
            Call.STATE_RINGING -> call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
        }
        refresh()
    }

    private fun refresh() {
        updateRinging()
        CallNotifier.update(this)
        CallRegistry.notifyChanged()
    }

    /** Rings while an incoming call waits for the owner (not while the AI takes it). */
    private fun updateRinging() {
        val calls = CallRegistry.snapshot()
        val ringingForOwner = calls.any {
            CallRegistry.androidState(it.call) == Call.STATE_RINGING && !CallRegistry.isAiOwned(it.callId) && CallRegistry.decision(it.callId) != "now"
        }
        if (!ringingForOwner) {
            ringer.stop()
            return
        }
        val inCall = calls.any { CallRegistry.androidState(it.call) in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING) }
        ringer.start(callWaiting = inCall)
    }

    /**
     * AI calls: once active, move them into background audio processing (default dialer only), so
     * Telecom neither plays them on the phone nor uses the phone's mic; the audio server streams
     * them to the gateway. Personal calls are left alone.
     */
    private fun syncCallAudio(callId: String, call: Call) {
        val state = CallRegistry.androidState(call)
        if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
            CallAudioBridge.onCallEnded(callId)
            return
        }
        if (!CallRegistry.isAiOwned(callId)) return
        when (state) {
            Call.STATE_ACTIVE -> {
                val routed = runCatching { enterBackgroundAudioProcessing(call) }
                    .onFailure { Log.w(TAG, "background audio processing refused for $callId", it) }
                    .isSuccess
                if (!routed) CallAudioBridge.onCallActive(callId)
            }
            Call.STATE_AUDIO_PROCESSING -> CallAudioBridge.onCallActive(callId)
        }
    }

    /** `Call.enterBackgroundAudioProcessing()` is @SystemApi; Telecom only requires the default dialer. */
    private fun enterBackgroundAudioProcessing(call: Call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/telecom/Call;")
        Call::class.java.getMethod("enterBackgroundAudioProcessing").invoke(call)
    }

    private fun exitBackgroundAudioProcessing(call: Call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/telecom/Call;")
        Call::class.java.getMethod("exitBackgroundAudioProcessing", Boolean::class.javaPrimitiveType).invoke(call, false)
    }

    private fun showInCallUi() {
        runCatching {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
