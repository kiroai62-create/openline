package com.vaaniflow.app.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * As the default phone app, VaaniFlow rings for incoming calls itself. It follows the phone's
 * ringer mode: normal rings (and vibrates if the phone is set to), vibrate only vibrates, silent
 * stays silent. A call waiting during another call only gets a short vibration.
 */
class Ringer(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    @Synchronized
    fun start(callWaiting: Boolean) {
        if (ringtone?.isPlaying == true || vibrating) return
        val mode = audio.ringerMode
        if (callWaiting) {
            vibrate(repeat = false)
            return
        }
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                    ?: Settings.System.DEFAULT_RINGTONE_URI
                ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }
        }
        val vibrateWhenRinging = runCatching { Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 1) == 1 }.getOrDefault(true)
        if (mode == AudioManager.RINGER_MODE_VIBRATE || (mode == AudioManager.RINGER_MODE_NORMAL && vibrateWhenRinging)) vibrate(repeat = true)
    }

    @Synchronized
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) runCatching { vibrator?.cancel() }
        vibrating = false
    }

    private fun vibrate(repeat: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 900, 700)
        runCatching {
            v.vibrate(
                VibrationEffect.createWaveform(pattern, if (repeat) 1 else -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            )
            vibrating = repeat
        }
    }
}
