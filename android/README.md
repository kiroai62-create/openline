# VaaniFlow Android app

A complete phone app (default dialer) with the VaaniFlow AI receptionist built in. The owner uses it
like the Google Phone app; calls the AI takes stream silently to the VaaniFlow server.

## Screens

- **Favourites**: starred contacts and people you call often.
- **Recents**: call history grouped by day, missed calls in red, SIM label on dual-SIM phones, tap to expand (message, add contact).
- **Contacts**: A-Z list, create contact, open in the contacts app.
- **Keypad**: bottom sheet with T9 contact suggestions, long-press 0 for `+`.
- **VaaniFlow AI**: AI on/off, when the AI answers (ring first / instantly / off), ring seconds, saved contacts ring normally, SIM the AI handles, business hours, setup checklist (default phone app, QR pairing, call audio, battery) and advanced settings.
- **Call screen**: incoming (answer, decline, let AI answer), active (mute, keypad, speaker/Bluetooth/headset, add call, hold, swap, merge, give to AI), AI-handled calls (take over, end), SIM chooser, proximity screen-off, lock-screen support.
- **Notifications**: incoming/ongoing call style notifications, missed calls, and a status notification with **Turn AI off/on**.

## Personal calls vs AI calls

For every incoming call `ai/AiSettings.kt` decides on the phone:

| Decision | Sent as | What happens |
|---|---|---|
| Saved contact, personal SIM, or AI off | `never` | Rings normally, the AI never answers |
| Ring first | `after:<seconds>` | Rings; the server answers after the delay if nobody did |
| Instant, closed hours, or already on a call | `now` | Silent, the AI answers at once |

If the phone is offline or unlicensed, calls just ring. If the AI doesn't pick up in time, the phone
starts ringing for the owner. Only calls the server answers or places (or that the owner hands
over) go into background audio processing; personal calls use the earpiece and microphone normally.

## "App was denied access to be default Phone app"

Android 13+ blocks the phone-app role for APKs installed outside an app store. The app detects
this after the role request and shows a step-by-step sheet: App info → ⋮ → **Allow restricted
settings** → back to VaaniFlow → **Set as default**. Installing through Google Play (including
internal app sharing) avoids the block.

## Design

Looks like the Google Phone app so it feels part of the system: Material You colours from the
wallpaper (Android 12+, Google's baseline blue before that), the phone's own font, Google's search
bar, segmented lists, keypad and call screen. The only addition is the **AI** tab on the right.
Theme in `ui/Theme.kt`, shared pieces in `ui/Components.kt`. Screenshots: `docs/screenshots/`.

## Battery

The status connection is the main background cost, so it wakes the radio as little as possible:
the server pings every 45 s, the phone's own ping is only a 120 s dead-socket check, and health
reports go every 5 minutes plus on connect and network changes. The "Stay online in the
background" step asks for the battery exemption and then opens the maker's own background or
autostart screen (Vivo, iQOO, Xiaomi, Oppo/Realme, Huawei, Samsung).

## Build

```bash
./gradlew :app:assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew :protocol:test :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest -Droborazzi.test.record=true   # screenshots in app/build/outputs/roborazzi
```

Needs JDK 17 and the Android SDK (platform 36.1, build-tools 36.1.0). Installs on Android 10+; AI
call audio needs Android 11+ (Wireless debugging).

## How AI call audio works

Ordinary apps can't capture or play call audio. The app pairs once with the phone's own Wireless
debugging (via [libadb-android](https://github.com/MuntashirAkon/libadb-android)) and starts a
small helper process with Android's `shell` permissions. The helper captures the downlink, plays
the agent's audio into the call and streams both to the server. No root, Shizuku or PC needed.

| Package | What |
|---|---|
| `ui/` | Compose screens and theme (brand: Vaani Indigo `#5B47FB`, Flow Teal `#14C8B0`) |
| `ai/` | When the AI may answer |
| `data/` | Contacts, call log and SIMs |
| `telecom/` | `InCallService`, ringer, notifications, call actions, call registry |
| `transport/` | Server connection, foreground service, health reports |
| `shell/`, `audio/` | Wireless-debugging helper and call audio bridge |
| `config/`, `license/` | QR pairing, credentials and licence |
| `protocol/` (module) | Wire format shared with the server |
