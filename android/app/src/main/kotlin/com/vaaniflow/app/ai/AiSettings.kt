package com.vaaniflow.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import com.vaaniflow.app.telecom.CallRegistry
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArraySet

/**
 * When may VaaniFlow AI answer an incoming call? Decided on the phone, so the owner's contacts never
 * leave the device. The decision travels to the server as the "ai" field of the call events:
 * "now", "after:<seconds>" (ring for the owner first) or "never" (a personal call).
 */
object AiSettings {
    enum class Mode(val label: String) {
        RING_FIRST("Ring first, then AI"),
        INSTANT("AI answers instantly"),
        OFF("Off: I answer every call"),
    }

    data class Settings(
        val mode: Mode = Mode.RING_FIRST,
        val ringSeconds: Int = 12,
        /** Calls from saved contacts always ring normally and never go to the AI. */
        val skipContacts: Boolean = true,
        /** PhoneAccountHandle id of the SIM the AI handles; empty = every SIM. */
        val aiSimId: String = "",
        /** Outside these hours the AI answers instantly (closed). */
        val hoursEnabled: Boolean = false,
        val openMinute: Int = 10 * 60,
        val closeMinute: Int = 19 * 60,
        /** Calendar.DAY_OF_WEEK values the business is open. */
        val openDays: Set<Int> = setOf(2, 3, 4, 5, 6, 7),
    )

    data class Decision(val wire: String, val reason: String) {
        val aiMayAnswer: Boolean get() = wire != "never"
        val ringsFirst: Boolean get() = wire.startsWith("after:") || wire == "never"
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun load(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            mode = runCatching { Mode.valueOf(p.getString("mode", Mode.RING_FIRST.name)!!) }.getOrDefault(Mode.RING_FIRST),
            ringSeconds = p.getInt("ringSeconds", 12).coerceIn(3, 60),
            skipContacts = p.getBoolean("skipContacts", true),
            aiSimId = p.getString("aiSimId", "").orEmpty(),
            hoursEnabled = p.getBoolean("hoursEnabled", false),
            openMinute = p.getInt("openMinute", 10 * 60),
            closeMinute = p.getInt("closeMinute", 19 * 60),
            openDays = p.getStringSet("openDays", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(2, 3, 4, 5, 6, 7),
        )
    }

    fun save(context: Context, s: Settings) {
        prefs(context).edit()
            .putString("mode", s.mode.name)
            .putInt("ringSeconds", s.ringSeconds)
            .putBoolean("skipContacts", s.skipContacts)
            .putString("aiSimId", s.aiSimId)
            .putBoolean("hoursEnabled", s.hoursEnabled)
            .putInt("openMinute", s.openMinute)
            .putInt("closeMinute", s.closeMinute)
            .putStringSet("openDays", s.openDays.map { it.toString() }.toSet())
            .apply()
        listeners.forEach { it() }
    }

    /** Quick on/off from the notification: OFF <-> the last mode used. */
    fun toggle(context: Context) {
        val s = load(context)
        val p = prefs(context)
        if (s.mode == Mode.OFF) {
            val last = runCatching { Mode.valueOf(p.getString("lastMode", Mode.RING_FIRST.name)!!) }.getOrDefault(Mode.RING_FIRST)
            save(context, s.copy(mode = if (last == Mode.OFF) Mode.RING_FIRST else last))
        } else {
            p.edit().putString("lastMode", s.mode.name).apply()
            save(context, s.copy(mode = Mode.OFF))
        }
    }

    fun isOpenNow(s: Settings, now: Calendar = Calendar.getInstance()): Boolean {
        if (!s.hoursEnabled) return true
        if (now.get(Calendar.DAY_OF_WEEK) !in s.openDays) return false
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (s.openMinute <= s.closeMinute) minute in s.openMinute until s.closeMinute
        else minute >= s.openMinute || minute < s.closeMinute // open past midnight
    }

    /** The decision for one incoming call. */
    fun decide(context: Context, call: Call): Decision {
        val s = load(context)
        if (s.mode == Mode.OFF) return Decision("never", "AI answering is off")
        val accountId = call.details.accountHandle?.id.orEmpty()
        if (s.aiSimId.isNotBlank() && accountId.isNotBlank() && accountId != s.aiSimId) {
            return Decision("never", "Call on your personal SIM")
        }
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        if (s.skipContacts && number.isNotBlank() && isSavedContact(context, number)) {
            return Decision("never", "Saved contact")
        }
        // Already talking on another call: the AI takes the new one straight away.
        val busy = CallRegistry.snapshot().any { it.call != call && CallRegistry.androidState(it.call) == Call.STATE_ACTIVE }
        if (busy) return Decision("now", "You're on another call")
        if (!isOpenNow(s)) return Decision("now", "Outside business hours")
        return if (s.mode == Mode.INSTANT) Decision("now", "AI answers instantly") else Decision("after:${s.ringSeconds}", "Rings for ${s.ringSeconds} s, then AI")
    }

    fun isSavedContact(context: Context, number: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
}
