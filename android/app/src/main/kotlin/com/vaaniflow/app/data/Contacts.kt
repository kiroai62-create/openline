package com.vaaniflow.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** Contacts, favourites and call history from Android's own providers (nothing leaves the phone). */
object PhoneData {
    data class Contact(val id: Long, val lookupKey: String, val name: String, val photo: Uri?, val starred: Boolean, val numbers: List<Number>)
    data class Number(val number: String, val label: String)
    data class CallEntry(
        val id: Long,
        val number: String,
        val name: String?,
        val type: Int,
        val date: Long,
        val duration: Long,
        val accountId: String?,
        val photo: Uri?,
    ) {
        val missed get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
        val outgoing get() = type == CallLog.Calls.OUTGOING_TYPE
    }

    fun has(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun contacts(context: Context): List<Contact> {
        if (!has(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val numbers = mutableMapOf<Long, MutableList<Number>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.getString(1) ?: continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    val list = numbers.getOrPut(id) { mutableListOf() }
                    if (list.none { digits(it.number) == digits(number) }) list.add(Number(number, label))
                }
            }
        }
        val out = mutableListOf<Contact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ContactsContract.Contacts.STARRED),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1", null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val list = numbers[id] ?: continue
                    out.add(Contact(id, c.getString(1).orEmpty(), c.getString(2) ?: list.first().number, c.getString(3)?.let(Uri::parse), c.getInt(4) == 1, list))
                }
            }
        }
        return out
    }

    fun recents(context: Context, limit: Int = 300): List<CallEntry> {
        if (!has(context, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<CallEntry>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString()).build(),
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.CACHED_PHOTO_URI),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(CallEntry(c.getLong(0), c.getString(1).orEmpty(), c.getString(2)?.takeIf { it.isNotBlank() }, c.getInt(3), c.getLong(4), c.getLong(5), c.getString(6), c.getString(7)?.let(Uri::parse)))
                }
            }
        }
        return out
    }

    /** Display name for a number, or null. */
    fun nameFor(context: Context, number: String): String? {
        if (number.isBlank() || !has(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun contactUri(contact: Contact): Uri = ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)
        ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.id)

    /** SIMs that can place calls, with a short label ("SIM 1 · Jio"). */
    fun callAccounts(context: Context): List<Pair<PhoneAccountHandle, String>> {
        if (!has(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val telecom = context.getSystemService(TelecomManager::class.java)
        return runCatching {
            telecom.callCapablePhoneAccounts.mapIndexed { index, handle ->
                val label = telecom.getPhoneAccount(handle)?.label?.toString()?.takeIf { it.isNotBlank() }
                handle to listOfNotNull("SIM ${index + 1}", label).joinToString(" · ")
            }
        }.getOrDefault(emptyList())
    }

    fun digits(value: String) = value.filter(Char::isDigit).takeLast(10)

    /** Classic keypad letters, for T9 search on the dialpad. */
    private val T9 = mapOf('a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3', 'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5', 'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7', 't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9')
    fun t9(name: String) = name.lowercase().map { T9[it] ?: if (it.isDigit()) it else ' ' }.joinToString("")
}
