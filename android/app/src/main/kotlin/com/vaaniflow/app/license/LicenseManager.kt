package com.httpgateway.dialer.license

import android.content.Context
import android.os.Build
import com.httpgateway.dialer.config.GatewayConfigStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The app's licence. The real enforcement is on the server (an unlicensed phone's calls are not
 * answered), but the app also stores its licence so it can show which customer it belongs to and
 * refuse to place calls when the licence is clearly inactive.
 *
 * The licence key arrives inside the pairing QR from the customer portal (`&lic=`), so the user
 * never types it. The status is fetched from POST /v1/license/activate and cached for offline use.
 */
object LicenseManager {
    data class Status(
        val valid: Boolean,
        val customerName: String?,
        val plan: String?,
        val statusText: String?,
        val expiresAt: String?,
        val message: String?,
        val checkedAt: Long,
    )

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    fun key(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY, key.trim().uppercase()).apply()
    }

    fun hasKey(context: Context): Boolean = key(context) != null

    /** Last known status (for showing a label without a network call). */
    fun cached(context: Context): Status? {
        val json = prefs(context).getString(CACHE, null) ?: return null
        return runCatching { parse(JSONObject(json), prefs(context).getLong(CACHE_AT, 0L)) }.getOrNull()
    }

    /**
     * True unless the licence is known to be inactive. A phone that has never reached the server is
     * allowed, so a network blip never bricks a paired phone; the server still gates the calls.
     */
    fun allowsCalls(context: Context): Boolean {
        if (!hasKey(context)) return true
        return cached(context)?.valid ?: true
    }

    /** Checks the licence with the server. Blocking; call off the main thread. */
    fun refresh(context: Context): Status? {
        val key = key(context) ?: return null
        val base = serverBase(context) ?: return cached(context)
        val body = JSONObject()
            .put("key", key)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/v1/license/activate").post(body).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // 404 = unknown/revoked licence: remember it as invalid.
                    val invalid = Status(false, null, null, "invalid", null, "Licence not found.", System.currentTimeMillis())
                    if (response.code == 404) store(context, JSONObject().put("valid", false).put("message", "Licence not found."))
                    return invalid
                }
                val json = JSONObject(text)
                store(context, json)
                parse(json, System.currentTimeMillis())
            }
        }.getOrDefault(cached(context))
    }

    private fun store(context: Context, json: JSONObject) {
        prefs(context).edit().putString(CACHE, json.toString()).putLong(CACHE_AT, System.currentTimeMillis()).apply()
    }

    private fun parse(json: JSONObject, at: Long): Status = Status(
        valid = json.optBoolean("valid", false),
        customerName = json.optString("customerName").takeIf { it.isNotBlank() },
        plan = json.optString("plan").takeIf { it.isNotBlank() && it != "null" },
        statusText = json.optString("status").takeIf { it.isNotBlank() },
        expiresAt = json.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" },
        message = json.optString("message").takeIf { it.isNotBlank() && it != "null" },
        checkedAt = at,
    )

    private fun serverBase(context: Context): String? {
        val wsUrl = GatewayConfigStore(context).load().wsUrl.ifBlank { return null }
        return wsUrl.replaceFirst(Regex("^ws"), "http").substringBefore("/v1").trimEnd('/')
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("license", Context.MODE_PRIVATE)

    private const val KEY = "key"
    private const val CACHE = "status"
    private const val CACHE_AT = "status_at"
}
