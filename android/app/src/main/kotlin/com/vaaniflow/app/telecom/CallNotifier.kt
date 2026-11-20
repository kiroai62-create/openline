package com.vaaniflow.app.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.telecom.Call
import com.vaaniflow.app.DialerActivity
import com.vaaniflow.app.InCallActivity
import com.vaaniflow.app.R
import com.vaaniflow.app.data.PhoneData

/** Incoming, ongoing and missed call notifications (Android's call style where available). */
object CallNotifier {
    private const val CH_INCOMING = "calls_incoming"
    private const val CH_ONGOING = "calls_ongoing"
    private const val CH_MISSED = "calls_missed"
    private const val ID_CALL = 7101
    private var missedId = 7200

    const val ACTION_ANSWER = "com.vaaniflow.app.ANSWER"
    const val ACTION_DECLINE = "com.vaaniflow.app.DECLINE"
    const val ACTION_HANGUP = "com.vaaniflow.app.HANGUP"
    const val ACTION_TAKE_OVER = "com.vaaniflow.app.TAKE_OVER"
    const val ACTION_GIVE_AI = "com.vaaniflow.app.GIVE_AI"
    const val ACTION_CALL_BACK = "com.vaaniflow.app.CALL_BACK"
    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_NOTIFICATION_ID = "notificationId"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // VaaniFlow plays the ringtone itself
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Shows the notification for the call that matters most right now, or clears it. */
    fun update(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val calls = CallRegistry.snapshot()
        val ringing = calls.firstOrNull { CallRegistry.androidState(it.call) == Call.STATE_RINGING && !CallRegistry.isAiOwned(it.callId) && CallRegistry.decision(it.callId) != "now" }
        val primary = ringing ?: calls.firstOrNull { CallRegistry.androidState(it.call) !in setOf(Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING) }
        if (primary == null) {
            nm.cancel(ID_CALL)
            return
        }
        val number = primary.call.details.handle?.schemeSpecificPart.orEmpty()
        val name = PhoneData.nameFor(context, number) ?: number.ifBlank { "Unknown caller" }
        val person = Person.Builder().setName(name).setImportant(true).build()
        val open = PendingIntent.getActivity(context, 1, Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ai = CallRegistry.isAiOwned(primary.callId)
        val b = Notification.Builder(context, if (ringing != null) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_vaaniflow)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setColor(context.getColor(R.color.brand))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (ringing != null) {
            b.setFullScreenIntent(open, true)
            val reason = CallRegistry.decisionReason(ringing.callId)
            b.setContentText(if (CallRegistry.decision(ringing.callId)?.startsWith("after:") == true) "Incoming call · AI answers if you don't" else reason ?: "Incoming call")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.setStyle(Notification.CallStyle.forIncomingCall(person, action(context, ACTION_DECLINE, ringing.callId), action(context, ACTION_ANSWER, ringing.callId)))
            } else {
                b.setContentTitle(name)
                b.addAction(Notification.Action.Builder(null as Icon?, "Decline", action(context, ACTION_DECLINE, ringing.callId)).build())
                b.addAction(Notification.Action.Builder(null as Icon?, "Answer", action(context, ACTION_ANSWER, ringing.callId)).build())
            }
            if (CallRegistry.decision(ringing.callId) != "never") {
                b.addAction(Notification.Action.Builder(null as Icon?, "Let AI answer", action(context, ACTION_GIVE_AI, ringing.callId)).build())
            }
        } else {
            val connectAt = primary.call.details.connectTimeMillis
            if (connectAt > 0) b.setWhen(connectAt).setUsesChronometer(true).setShowWhen(true)
            b.setContentText(if (ai) "VaaniFlow AI is talking with the caller" else "On call")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.setStyle(Notification.CallStyle.forOngoingCall(person, action(context, ACTION_HANGUP, primary.callId)))
            } else {
                b.setContentTitle(name)
                b.addAction(Notification.Action.Builder(null as Icon?, "Hang up", action(context, ACTION_HANGUP, primary.callId)).build())
            }
            if (ai) b.addAction(Notification.Action.Builder(null as Icon?, "Take over", action(context, ACTION_TAKE_OVER, primary.callId)).build())
        }
        runCatching { nm.notify(ID_CALL, b.build()) }
    }

    fun missed(context: Context, number: String) {
        val name = PhoneData.nameFor(context, number) ?: number.ifBlank { "Unknown caller" }
        val open = PendingIntent.getActivity(context, 2, Intent(context, DialerActivity::class.java).putExtra(DialerActivity.EXTRA_TAB, "recents").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_vaaniflow)
            .setColor(context.getColor(R.color.brand))
            .setContentTitle("Missed call")
            .setContentText(name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .apply {
                // Like Google Phone: call back or message straight from the notification.
                if (number.isNotBlank()) {
                    // Through our own non-exported receiver, so no other app can make the phone dial.
                    val back = PendingIntent.getBroadcast(context, ("cb" + number).hashCode(), Intent(context, CallActionReceiver::class.java).setAction(ACTION_CALL_BACK).putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_NOTIFICATION_ID, missedId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val sms = PendingIntent.getActivity(context, ("sm" + number).hashCode(), Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${android.net.Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null as Icon?, "Call back", back).build())
                    addAction(Notification.Action.Builder(null as Icon?, "Message", sms).build())
                }
            }
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(missedId++, n) }
        if (missedId > 7290) missedId = 7200
    }

    private fun action(context: Context, action: String, callId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (action + callId).hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CallNotifier.ACTION_CALL_BACK) {
            val number = intent.getStringExtra(CallNotifier.EXTRA_NUMBER).orEmpty()
            context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(CallNotifier.EXTRA_NOTIFICATION_ID, 0))
            if (number.isNotBlank() && context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching { context.getSystemService(android.telecom.TelecomManager::class.java).placeCall(android.net.Uri.fromParts("tel", number, null), android.os.Bundle()) }
            }
            return
        }
        val callId = intent.getStringExtra(CallNotifier.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            CallNotifier.ACTION_ANSWER -> {
                CallActions.answer(callId)
                context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            CallNotifier.ACTION_DECLINE -> CallActions.decline(callId)
            CallNotifier.ACTION_HANGUP -> CallActions.hangup(callId)
            CallNotifier.ACTION_TAKE_OVER -> {
                CallActions.takeOver(callId)
                context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            CallNotifier.ACTION_GIVE_AI -> CallActions.handToAi(callId)
        }
    }
}
