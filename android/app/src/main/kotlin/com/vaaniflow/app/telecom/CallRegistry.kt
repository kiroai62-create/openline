package com.vaaniflow.app.telecom

import android.os.Build
import android.telecom.Call
import com.vaaniflow.protocol.CallStateMapper
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

object CallRegistry {
    data class Entry(val callId: String, val call: Call)

    private data class PendingOutgoing(val callId: String, val number: String)

    fun interface Listener {
        fun onCallsChanged()
    }

    private val callsById = LinkedHashMap<String, Call>()
    private val idsByCall = IdentityHashMap<Call, String>()
    private val pendingOutgoing = ArrayDeque<PendingOutgoing>()
    private val rejectedCallIds = mutableSetOf<String>()
    private val listeners = CopyOnWriteArraySet<Listener>()
    /** Calls the AI (or an API/console client) handles: their audio goes to the server, silently. */
    private val aiOwned = mutableSetOf<String>()
    /** Server-chosen ids of outgoing calls placed by the API, before Telecom registers them. */
    private val aiOutgoing = mutableSetOf<String>()
    private val decisions = mutableMapOf<String, String>()
    private val decisionReasons = mutableMapOf<String, String>()
    private val everActive = mutableSetOf<String>()

    @Synchronized
    fun reserveOutgoing(callId: String, number: String) {
        pendingOutgoing.removeAll { it.callId == callId }
        pendingOutgoing.addLast(PendingOutgoing(callId, normalize(number)))
    }

    @Synchronized
    fun cancelOutgoingReservation(callId: String) {
        pendingOutgoing.removeAll { it.callId == callId }
    }

    @Synchronized
    fun register(call: Call): Entry {
        idsByCall[call]?.let { return Entry(it, call) }

        val number = normalize(call.details.handle?.schemeSpecificPart.orEmpty())
        val direction = call.details.callDirection
        val reserved = if (direction == Call.Details.DIRECTION_OUTGOING) {
            claimOutgoing(number)
        } else {
            null
        }
        val callId = reserved ?: "call_${UUID.randomUUID()}"

        callsById[callId] = call
        idsByCall[call] = callId
        notifyListeners()
        return Entry(callId, call)
    }

    @Synchronized
    fun remove(call: Call): String? {
        val callId = idsByCall.remove(call) ?: return null
        callsById.remove(callId)
        rejectedCallIds.remove(callId)
        aiOwned.remove(callId)
        aiOutgoing.remove(callId)
        decisions.remove(callId)
        decisionReasons.remove(callId)
        everActive.remove(callId)
        notifyListeners()
        return callId
    }

    @Synchronized
    fun markAiOwned(callId: String, owned: Boolean) {
        if (owned) aiOwned.add(callId) else aiOwned.remove(callId)
        if (callsById.containsKey(callId)) notifyListeners()
    }

    /** An API/console dial: the call that Telecom registers for this id belongs to the AI. */
    @Synchronized
    fun markAiOutgoing(callId: String) {
        aiOutgoing.add(callId)
        aiOwned.add(callId)
    }

    @Synchronized
    fun isAiOwned(callId: String): Boolean = aiOwned.contains(callId)

    @Synchronized
    fun setDecision(callId: String, wire: String, reason: String) {
        decisions[callId] = wire
        decisionReasons[callId] = reason
    }

    @Synchronized
    fun decision(callId: String): String? = decisions[callId]

    @Synchronized
    fun decisionReason(callId: String): String? = decisionReasons[callId]

    @Synchronized
    fun markActive(callId: String) {
        everActive.add(callId)
    }

    @Synchronized
    fun wasActive(callId: String): Boolean = everActive.contains(callId)

    @Synchronized
    fun markRejected(callId: String) {
        rejectedCallIds.add(callId)
    }

    @Synchronized
    fun clearRejected(callId: String) {
        rejectedCallIds.remove(callId)
    }

    @Synchronized
    fun wasRejected(callId: String): Boolean = rejectedCallIds.contains(callId)

    @Synchronized
    fun callIdFor(call: Call): String? = idsByCall[call]

    @Synchronized
    fun get(callId: String): Call? = callsById[callId]

    @Synchronized
    fun snapshot(): List<Entry> = callsById.map { Entry(it.key, it.value) }

    fun stateWireName(call: Call): String = CallStateMapper.fromAndroidState(androidState(call)).wireName

    fun androidState(call: Call): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        call.details.state
    } else {
        @Suppress("DEPRECATION")
        call.state
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyChanged() = notifyListeners()

    private fun claimOutgoing(number: String): String? {
        if (pendingOutgoing.isEmpty()) return null

        val match = pendingOutgoing.firstOrNull { numbersEquivalent(it.number, number) }
            ?: pendingOutgoing.firstOrNull()
            ?: return null
        pendingOutgoing.remove(match)
        return match.callId
    }

    private fun notifyListeners() {
        listeners.forEach { it.onCallsChanged() }
    }

    private fun normalize(value: String): String = value.filter { it.isDigit() || it == '+' }

    private fun numbersEquivalent(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val aDigits = a.filter(Char::isDigit)
        val bDigits = b.filter(Char::isDigit)
        return aDigits == bDigits || aDigits.takeLast(10) == bDigits.takeLast(10)
    }
}
