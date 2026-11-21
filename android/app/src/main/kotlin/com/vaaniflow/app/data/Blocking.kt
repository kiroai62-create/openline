package com.vaaniflow.app.data

import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers

/**
 * Blocked numbers, using Android's own BlockedNumberContract (only the default dialer/SMS app may
 * write it). Blocked callers are rejected by the system before the app even sees them, exactly like
 * the Google Phone app.
 */
object Blocking {
    fun canUse(context: Context): Boolean =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    fun isBlocked(context: Context, number: String): Boolean {
        if (number.isBlank() || !canUse(context)) return false
        return runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)
    }

    fun list(context: Context): List<String> {
        if (!canUse(context)) return emptyList()
        val out = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out
    }

    fun block(context: Context, number: String) {
        if (number.isBlank() || !canUse(context)) return
        runCatching {
            val values = android.content.ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
            context.contentResolver.insert(BlockedNumbers.CONTENT_URI, values)
        }
    }

    fun unblock(context: Context, number: String) {
        if (number.isBlank() || !canUse(context)) return
        runCatching {
            @Suppress("DEPRECATION")
            val uri: Uri? = BlockedNumbers.CONTENT_URI
            context.contentResolver.delete(uri!!, "${BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?", arrayOf(number))
        }
    }
}
