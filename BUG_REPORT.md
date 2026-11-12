# Instinct 2: watch app stops drawing and stops receiving phone messages ("IQ!") when phone and watch messages cross

## Environment

- **Device:** Instinct 2, firmware 17.22, Connect IQ API 3.4
- **Connect IQ SDK:** 9.1.0 (connectiq-sdk-lin-9.1.0-2026-03-09)
- **Android Connect IQ Mobile SDK:** com.garmin.connectiq:ciq-companion-app-sdk 2.2.0, `IQConnectType.WIRELESS`
- **Phone:** Pixel 10 Pro XL, Android 17
- **Garmin Connect app:** [version: Settings → Apps → Garmin Connect]
- **Build tools:** monkeyc from the command line (no VS Code); Android Gradle Plugin 8.6.1

## Description

A watch app freezes while messages go both ways between it and its Android companion. The watch shows "IQ!" over the app. After that:

- `onUpdate` is never called again.
- The callback passed to `Communications.registerForPhoneAppMessages` is never called again. The phone's `sendMessage` keeps reporting `SUCCESS` for messages the app never gets.
- Timers, button input and `Communications.transmit` keep working. The app's status messages keep reaching the phone, and they show the draw and receive counts frozen.
- `onStop` runs on exit, but the exit (or an activity save, in our full app) isn't finished until the watch is plugged into USB or restarted.
- Nothing is written to CIQ_LOG.

The freeze starts at the moment the phone is told a message was delivered. Each time, that delivery took 6–8 s and reported `SUCCESS`, while normal deliveries take 1–3 s. The same runs have many `FAILURE_DURING_TRANSFER` results (3–13 per run), against about 0 when only the phone sends. It looks like a transfer that is resent while the watch is transmitting is handed to the app in a way that stops its message and UI handling.

Twice, the fault stopped all Connect IQ messaging in both directions. Watch transmits failed within 12 ms, and phone sends failed. This survived restarting the watch app, force-stopping Garmin Connect and restarting the companion app. Only turning the phone's Bluetooth off and on cleared it.

## Steps to reproduce

The attached sample has a watch app (`watch/`) and an Android companion (`android/`). The watch app sends a small status dictionary as soon as its last `transmit` is called back, checking once a second. The phone sends a 500-character string as soon as its last `sendMessage` is called back. So messages cross all the time.

1. Build and sideload the watch app:
   `monkeyc -d instinct2 -f watch/monkey.jungle -o Crossing.prg -y <key> -r`
   Copy it to `GARMIN/APPS/CROSSING.PRG`. Create an empty `GARMIN/APPS/LOGS/CROSSING.TXT` for its log.
2. Build and install the Android app: `cd android && gradlew assembleDebug`, then `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Open **Crossing** on the phone and wait for "ready: Instinct 2".
4. Open **Crossing** on the watch, then tap **Start** on the phone.
5. Watch the counts on the watch screen.

**Control:** open the watch app, press START so the screen says "phone only", then tap Start on the phone. The watch then sends nothing, and the phone sends the same messages back to back.

## Expected

The app keeps drawing and receiving messages. Messages crossing in both directions are delivered, or reported as failed, without stopping the app.

## Actual

The watch screen stops updating and shows "IQ!". The phone log shows `WATCH APP FROZEN: its timer and transmit still run, but onUpdate has not been called for N s`, and its summaries show the phone's delivered count rising while the watch's received count stays fixed.

Results from the test app this sample was reduced from (same traffic pattern, 2026-11-11):

| Traffic | Runs | Result |
|---|---|---|
| Both send back to back | 5 | 4 froze, 5 s to about 3 min in, after 1–85 phone messages. The fifth stopped all messaging at the 2nd phone message (see above). 3–13 `FAILURE_DURING_TRANSFER` per run. |
| Strict turns (watch sends only after a phone message arrives; phone sends 2 s after the watch's message arrives) | 1 | 57 min, 617 messages: no freeze, no failed delivery |
| Phone only (watch status every 30 s) | 1 | 143 min, 3,856 messages: no freeze |

Our full app also froze with strict turn-taking: no watch transmit was in flight, and the screen stopped at a phone delivery that took 14 s. Slow deliveries caused by range alone (10–13 s, phone only) did not freeze it. So crossing is not strictly required, but it makes the freeze happen within minutes.

## Workaround

Strict half-duplex messaging: the watch asks, the phone answers with one message, and neither side sends while the other's message may be in transfer. This makes the freeze rare, but doesn't remove it.

## Attachments

- `crossing-repro.zip`: the sample (watch app and Android app source)
- [the watch log `GARMIN/APPS/LOGS/CROSSING.TXT` and the phone log `Android/data/com.ciqrepro.crossing/files/crossing.log` from a run]
- [Bluetooth HCI snoop log covering a freeze, with its time]

Other Connect IQ companion apps were installed on the phone during our runs. The sample uses its own app id, so they don't receive its messages.
