package com.vaaniflow.app

import android.provider.CallLog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.vaaniflow.app.ai.AiSettings
import com.vaaniflow.app.data.PhoneData
import com.vaaniflow.app.ui.AssistantScreen
import com.vaaniflow.app.ui.HomeActions
import com.vaaniflow.app.ui.HomeScreen
import com.vaaniflow.app.ui.HomeState
import com.vaaniflow.app.ui.HomeTab
import com.vaaniflow.app.ui.SetupActions
import com.vaaniflow.app.ui.SetupState
import com.vaaniflow.app.ui.VaaniTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to PNGs under app/build/outputs/roborazzi (visual check, no device needed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val contacts = listOf(
        PhoneData.Contact(1, "a", "Asha Verma", null, true, listOf(PhoneData.Number("+919812345678", "Mobile"))),
        PhoneData.Contact(2, "b", "Bharat Kumar", null, true, listOf(PhoneData.Number("+919900011122", "Work"))),
        PhoneData.Contact(3, "c", "Dr. Rao", null, false, listOf(PhoneData.Number("+919811122233", "Mobile"))),
        PhoneData.Contact(4, "d", "Meera", null, true, listOf(PhoneData.Number("+919822233344", "Home"))),
        PhoneData.Contact(5, "e", "Rahul Mehta", null, false, listOf(PhoneData.Number("+919833344455", "Mobile"))),
    )
    private val recents = listOf(
        PhoneData.CallEntry(1, "+919812345678", "Asha Verma", CallLog.Calls.INCOMING_TYPE, now - 5 * 60_000, 184, null, null),
        PhoneData.CallEntry(2, "+918877665544", null, CallLog.Calls.MISSED_TYPE, now - 40 * 60_000, 0, null, null),
        PhoneData.CallEntry(3, "+918877665544", null, CallLog.Calls.MISSED_TYPE, now - 42 * 60_000, 0, null, null),
        PhoneData.CallEntry(4, "+919811122233", "Dr. Rao", CallLog.Calls.OUTGOING_TYPE, now - 3 * 3_600_000, 62, null, null),
        PhoneData.CallEntry(5, "+919833344455", "Rahul Mehta", CallLog.Calls.INCOMING_TYPE, now - 26 * 3_600_000, 310, null, null),
    )
    private val setup = SetupState(isDefault = true, paired = true, connected = true, host = "voice.example.com", licence = "Licensed to Smile Dental · growth", audioDone = true, audioText = "Ready. AI calls stream silently to VaaniFlow.", batteryDone = false, batteryText = "Without this the phone drops offline after a few minutes.", batteryAction = "Allow")
    private val state = HomeState(contacts, recents, listOf("sim1" to "SIM 1 · Jio", "sim2" to "SIM 2 · Airtel"), false, true, setup, AiSettings.Settings())
    private val actions = HomeActions({}, {}, {}, {}, {}, {}, SetupActions({}, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> }), {})

    private fun shot(name: String, tab: HomeTab, dial: String? = null, dark: Boolean = false) {
        compose.setContent { VaaniTheme(dark = dark) { HomeScreen(state, actions, tab, dial) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun recents() = shot("01-recents", HomeTab.RECENTS)
    @Test fun favourites() = shot("02-favourites", HomeTab.FAVOURITES)
    @Test fun contacts() = shot("03-contacts", HomeTab.CONTACTS)
    @Test fun assistant() = shot("04-assistant", HomeTab.ASSISTANT)
    @Test fun recentsDark() = shot("05-recents-dark", HomeTab.RECENTS, dark = true)
    @Test fun dialpad() = shot("06-dialpad", HomeTab.RECENTS, dial = "2742")
}
