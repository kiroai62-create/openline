package com.vaaniflow.app

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.vaaniflow.app.ai.AiSettings
import com.vaaniflow.app.audio.CallAudioBridge
import com.vaaniflow.app.config.GatewayConfig
import com.vaaniflow.app.config.GatewayConfigStore
import com.vaaniflow.app.config.GatewayPairing
import com.vaaniflow.app.data.PhoneData
import com.vaaniflow.app.license.LicenseManager
import com.vaaniflow.app.shell.PairingService
import com.vaaniflow.app.shell.ShellLauncher
import com.vaaniflow.app.telecom.CallCommandController
import com.vaaniflow.app.transport.DeviceHealth
import com.vaaniflow.app.transport.GatewayConnectionService
import com.vaaniflow.app.transport.GatewayRuntime
import com.vaaniflow.app.ui.HomeActions
import com.vaaniflow.app.ui.HomeScreen
import com.vaaniflow.app.ui.HomeState
import com.vaaniflow.app.ui.HomeTab
import com.vaaniflow.app.ui.SetupActions
import com.vaaniflow.app.ui.SetupState
import com.vaaniflow.app.ui.VaaniTheme
import com.vaaniflow.protocol.CommandAction
import com.vaaniflow.protocol.GatewayCommand
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The phone app: favourites, recents, contacts and keypad like the Google dialer, plus the
 * VaaniFlow AI tab (when the AI answers, personal-call rules and the one-time setup).
 */
class DialerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        private const val REQUEST_DIALER_ROLE = 1001
        private const val REQUEST_PERMISSIONS = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
        private const val EXTRA_DEBUG_WS_URL = "debug_gateway_ws_url"
        private const val EXTRA_DEBUG_DEVICE_ID = "debug_gateway_device_id"
        private const val EXTRA_DEBUG_DEVICE_TOKEN = "debug_gateway_device_token"
        private val PHONE_PERMISSIONS = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private lateinit var configStore: GatewayConfigStore
    private lateinit var controller: CallCommandController
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var home by mutableStateOf(HomeState())
    private var dialNumber by mutableStateOf<String?>(null)
    private var initialTab = HomeTab.RECENTS
    private var pairingMessage: String? = null
    private var audioStatus: CallAudioBridge.Status? = null
    private var needsWifi = false
    private var wifiDialog: AlertDialog? = null
    private var wifiPromptShown = false
    private var pendingDial: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            refreshSetup()
            handler.postDelayed(this, 1_500)
        }
    }

    private val dataObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = loadPhoneData()
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                wifiDialog?.dismiss()
                if (audioStatus == CallAudioBridge.Status.STOPPED) refreshAudioStatus() else refreshSetup()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        configStore = GatewayConfigStore(this)
        controller = CallCommandController(this)
        applyDebugGatewayConfig(intent)
        initialTab = when (intent.getStringExtra(EXTRA_TAB)) {
            "assistant" -> HomeTab.ASSISTANT
            "contacts" -> HomeTab.CONTACTS
            "favourites" -> HomeTab.FAVOURITES
            else -> if (!configStore.load().deviceToken.isNotBlank() && !isDefaultDialer()) HomeTab.ASSISTANT else HomeTab.RECENTS
        }
        applyDialIntent(intent)
        applyPairingIntent(intent)
        setContent {
            VaaniTheme {
                HomeScreen(home, homeActions(), initialTab, dialNumber) { dialNumber = null }
            }
        }
        if (PHONE_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(PHONE_PERMISSIONS.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugGatewayConfig(intent)
        applyDialIntent(intent)
        applyPairingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        wifiPromptShown = false
        loadPhoneData()
        refreshSetup()
        refreshAudioStatus()
        if (LicenseManager.hasKey(this)) io.execute { LicenseManager.refresh(this); runOnUiThread(::refreshSetup) }
        handler.post(ticker)
        runCatching {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, dataObserver)
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, dataObserver)
        }
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback,
            )
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        runCatching { contentResolver.unregisterContentObserver(dataObserver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        wifiDialog?.dismiss()
        super.onPause()
    }

    private fun homeActions() = HomeActions(
        call = ::requestOrDial,
        message = { n -> runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(n)}"))) } },
        addContact = { n ->
            runCatching {
                startActivity(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).apply { if (n.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, n) })
            }.onFailure { toast("No contacts app found") }
        },
        openContact = { c -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, PhoneData.contactUri(c))) }.onFailure { requestOrDial(c.numbers.first().number) } },
        requestPermissions = { requestPermissions(PHONE_PERMISSIONS.toTypedArray(), REQUEST_PERMISSIONS) },
        setDefaultDialer = ::requestDialerRole,
        setup = SetupActions(
            setDefault = ::requestDialerRole,
            scanQr = ::scanPairingQr,
            audio = { if (audioStatus == CallAudioBridge.Status.STOPPED) restartAudio() else startAudioSetup() },
            battery = ::requestBackgroundRunning,
            saveAdvanced = { ws, id, token ->
                configStore.save(GatewayConfig(wsUrl = ws, deviceId = id, deviceToken = token))
                GatewayRuntime.reconfigure()
                toast("Saved. Reconnecting.")
            },
            restartAudio = ::restartAudio,
            pickTime = { minute, done -> TimePickerDialog(this, { _, h, m -> done(h * 60 + m) }, minute / 60, minute % 60, false).show() },
        ),
        saveAi = { AiSettings.save(this, it); home = home.copy(ai = it); GatewayConnectionService.start(this) },
    )

    // ---- data -----------------------------------------------------------------------------

    private fun loadPhoneData() {
        io.execute {
            val contacts = PhoneData.contacts(this)
            val recents = PhoneData.recents(this)
            val accounts = PhoneData.callAccounts(this).map { it.first.id to it.second }
            val missing = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG).any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            runOnUiThread { home = home.copy(contacts = contacts, recents = recents, accounts = accounts, missingPermissions = missing, ai = AiSettings.load(this)) }
        }
    }

    private fun isDefaultDialer() = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

    private fun refreshSetup() {
        val config = configStore.load()
        val host = runCatching { Uri.parse(config.wsUrl).host }.getOrNull() ?: config.wsUrl
        val lic = LicenseManager.cached(this)
        val licText = when {
            !LicenseManager.hasKey(this) || lic == null -> null
            lic.valid -> "Licensed to ${lic.customerName ?: "customer"}${lic.plan?.let { " · $it" } ?: ""}"
            else -> "Licence inactive: ${lic.message ?: "contact your provider"}"
        }
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val (audioDone, audioText, audioAction) = when (audioStatus) {
            null -> Triple(false, "Checking…", null)
            CallAudioBridge.Status.READY -> Triple(true, "Ready. AI calls stream silently to VaaniFlow.", null)
            CallAudioBridge.Status.STOPPED -> if (needsWifi && !DeviceHealth.isOnWifi(this)) Triple(false, "Connect to Wi-Fi once to start it; mobile data works after that.", "Start") else Triple(false, "Set up, but stopped.", "Start")
            CallAudioBridge.Status.SETUP_REQUIRED -> Triple(false, "One-time pairing with Wireless debugging.", "Set up")
        }
        home = home.copy(
            isDefaultDialer = isDefaultDialer(),
            setup = SetupState(
                isDefault = isDefaultDialer(),
                paired = config.deviceToken.isNotBlank(),
                connected = GatewayRuntime.isConnected(),
                host = host,
                pairingMessage = pairingMessage,
                licence = licText,
                licenceValid = lic?.valid ?: true,
                audioDone = audioDone, audioText = audioText, audioAction = audioAction,
                batteryDone = exempt,
                batteryText = if (exempt) "Allowed. On Vivo/Oppo/Xiaomi also allow background activity in the app's battery settings." else "Without this the phone drops offline after a few minutes.",
                batteryAction = if (exempt) "Phone settings" else "Allow",
                wsUrl = config.wsUrl, deviceId = config.deviceId, deviceToken = config.deviceToken,
            ),
        )
    }

    // ---- calling --------------------------------------------------------------------------

    private fun requestOrDial(number: String) {
        if (number.isBlank()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingDial = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_PERMISSIONS)
            return
        }
        // A personal call: the owner talks on the phone. Telecom asks which SIM when needed.
        val command = GatewayCommand(commandId = "local_${UUID.randomUUID()}", callId = "call_${UUID.randomUUID()}", action = CommandAction.DIAL, to = number)
        controller.handle(command) { result -> if (!result.ok) runOnUiThread { toast(result.error?.replace('_', ' ') ?: "Call failed") } }
    }

    @Deprecated("Deprecated in Android API; kept for the simple permission flow.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadPhoneData()
        if (requestCode == REQUEST_PERMISSIONS && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) pendingDial?.let(::requestOrDial)
        pendingDial = null
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startAudioSetup()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DIALER_ROLE) { refreshSetup(); loadPhoneData() }
    }

    private fun requestDialerRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) return toast("This phone doesn't let apps become the default phone app")
        @Suppress("DEPRECATION")
        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_DIALER_ROLE)
    }

    // ---- setup (ported from the original VaaniFlow setup screen) ---------------------------

    private fun requestBackgroundRunning() {
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val intent = if (exempt) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure { toast("Open Settings, then Apps, then VaaniFlow, then Battery") }
    }

    /** Opening the app also (re)starts a stopped audio server. It starts through Wireless debugging, which needs Wi-Fi. */
    private fun refreshAudioStatus() {
        io.execute {
            var status = CallAudioBridge.status(this)
            var result: ShellLauncher.Result? = null
            if (status == CallAudioBridge.Status.STOPPED) {
                result = ShellLauncher.ensureRunning(applicationContext)
                status = CallAudioBridge.status(this)
            }
            GatewayRuntime.reportHealthSoon()
            runOnUiThread {
                audioStatus = status
                needsWifi = result == ShellLauncher.Result.NeedsWifi
                refreshSetup()
                if (needsWifi && !wifiPromptShown && configStore.load().deviceToken.isNotBlank()) showWifiRequired()
            }
        }
    }

    private fun showWifiRequired() {
        if (isFinishing || wifiDialog?.isShowing == true) return
        wifiPromptShown = true
        val wifiOn = runCatching { getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        wifiDialog = AlertDialog.Builder(this)
            .setTitle("Connect to a Wi-Fi network")
            .setMessage(
                (if (wifiOn) "Wi-Fi is on, but the phone isn't connected to a network yet. " else "") +
                    "VaaniFlow starts its AI call-audio helper through Android's Wireless debugging, which only runs on Wi-Fi. " +
                    "Join any network for a moment (a hotspot works too). After that, AI calls work on mobile data until the phone restarts.",
            )
            .setPositiveButton("Open Wi-Fi") { _, _ -> openWifiSettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { toast("Open Settings and turn on Wi-Fi") }
    }

    private fun restartAudio() {
        wifiPromptShown = false
        CallAudioBridge.restart(this) { result ->
            runOnUiThread {
                if (result == ShellLauncher.Result.NeedsWifi) {
                    needsWifi = true
                    audioStatus = CallAudioBridge.Status.STOPPED
                    refreshSetup()
                    showWifiRequired()
                } else refreshAudioStatus()
            }
        }
    }

    private fun startAudioSetup() {
        if (!DeviceHealth.isOnWifi(this)) return showWifiRequired()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        PairingService.start(this)
        val wirelessDebugging = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        val target = if (wirelessDebugging.resolveActivity(packageManager) != null) wirelessDebugging else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("Open Settings, then Developer options, then Wireless debugging") }
        toast("Turn on Wireless debugging, tap \"Pair device with pairing code\", then use the VaaniFlow notification")
    }

    private fun scanPairingQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val invite = barcode.rawValue?.let(GatewayPairing::parse)
                if (invite == null) toast("That QR code isn't from a VaaniFlow dashboard") else pairWith(invite)
            }
            .addOnFailureListener { toast("Scanner unavailable: ${it.message}") }
    }

    /** Scanning the QR with the camera app opens vaaniflow://pair?... directly. */
    private fun applyPairingIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "tel") return
        GatewayPairing.parse(data.toString())?.let(::pairWith)
        intent.data = null
    }

    private fun pairWith(invite: GatewayPairing.Invite) {
        pairingMessage = "Connecting to ${Uri.parse(invite.serverUrl).host}…"
        refreshSetup()
        io.execute {
            val result = runCatching { GatewayPairing.pair(this, invite) }
            runOnUiThread {
                pairingMessage = null
                result.onSuccess {
                    GatewayRuntime.reconfigure()
                    toast("Connected to your VaaniFlow workspace")
                    io.execute { LicenseManager.refresh(this); runOnUiThread(::refreshSetup) }
                }.onFailure { toast("Couldn't connect: ${it.message}") }
                refreshSetup()
            }
        }
    }

    private fun applyDebugGatewayConfig(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val wsUrl = intent.getStringExtra(EXTRA_DEBUG_WS_URL) ?: return
        val deviceId = intent.getStringExtra(EXTRA_DEBUG_DEVICE_ID) ?: return
        val deviceToken = intent.getStringExtra(EXTRA_DEBUG_DEVICE_TOKEN) ?: return
        if (wsUrl.isBlank() || deviceId.isBlank() || deviceToken.isBlank()) return
        configStore.save(GatewayConfig(wsUrl = wsUrl, deviceId = deviceId, deviceToken = deviceToken))
        GatewayRuntime.reconfigure()
    }

    private fun applyDialIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data?.scheme == "tel") dialNumber = data.schemeSpecificPart.orEmpty()
            else if (intent.action == Intent.ACTION_DIAL && data == null) dialNumber = ""
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
