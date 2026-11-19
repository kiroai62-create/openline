package com.vaaniflow.app.telecom

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile

/** What the person holding the phone can do with a call (in-call screen and notifications). */
object CallActions {
    private val main = Handler(Looper.getMainLooper())

    fun answer(callId: String) = withCall(callId) { it.answer(VideoProfile.STATE_AUDIO_ONLY) }

    fun decline(callId: String) = withCall(callId) {
        CallRegistry.markRejected(callId)
        it.reject(false, null)
    }

    fun hangup(callId: String) = withCall(callId) { it.disconnect() }

    fun toggleHold(callId: String) = withCall(callId) {
        if (CallRegistry.androidState(it) == Call.STATE_HOLDING) it.unhold() else it.hold()
    }

    fun dtmf(callId: String, digit: Char) = withCall(callId) {
        it.playDtmfTone(digit)
        main.postDelayed({ it.stopDtmfTone() }, 150)
    }

    /** Two calls: put the active one on hold and resume the other. */
    fun swap() {
        val calls = CallRegistry.snapshot().map { it.call }
        val held = calls.firstOrNull { CallRegistry.androidState(it) == Call.STATE_HOLDING } ?: return
        held.unhold() // Telecom holds the active call automatically
    }

    fun merge() {
        val calls = CallRegistry.snapshot().map { it.call }
        val active = calls.firstOrNull { CallRegistry.androidState(it) == Call.STATE_ACTIVE } ?: return
        val other = calls.firstOrNull { it != active && CallRegistry.androidState(it) == Call.STATE_HOLDING }
        runCatching {
            if (other != null && active.conferenceableCalls.contains(other)) active.conference(other) else active.mergeConference()
        }
    }

    fun selectAccount(callId: String, handle: PhoneAccountHandle) = withCall(callId) { it.phoneAccountSelected(handle, false) }

    fun setMuted(muted: Boolean) { GatewayInCallService.instance?.setMuted(muted) }

    fun setRoute(route: Int) { GatewayInCallService.instance?.setAudioRoute(route) }

    /** Speaker on/off, or cycles earpiece -> speaker -> Bluetooth when a headset is connected. */
    fun cycleRoute() {
        val state = GatewayInCallService.audioState.value ?: return
        val supported = state.supportedRouteMask
        val next = when (state.route) {
            CallAudioState.ROUTE_SPEAKER -> if (supported and CallAudioState.ROUTE_BLUETOOTH != 0) CallAudioState.ROUTE_BLUETOOTH else earpieceOrHeadset(supported)
            CallAudioState.ROUTE_BLUETOOTH -> earpieceOrHeadset(supported)
            else -> CallAudioState.ROUTE_SPEAKER
        }
        setRoute(next)
    }

    private fun earpieceOrHeadset(mask: Int) =
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) CallAudioState.ROUTE_WIRED_HEADSET else CallAudioState.ROUTE_EARPIECE

    fun handToAi(callId: String) { GatewayInCallService.instance?.handToAi(callId) }

    fun takeOver(callId: String) { GatewayInCallService.instance?.takeOver(callId) }

    private inline fun withCall(callId: String, action: (Call) -> Unit) {
        val call = CallRegistry.get(callId) ?: return
        runCatching { action(call) }
    }
}
