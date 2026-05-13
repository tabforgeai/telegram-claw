# Phase 2 Smoke Test — Day 17-19: Full End-to-End Loop with Natural Language Answer

**Goal:** Verify that a natural language question sent to a Telegram bot travels the complete
Telegram Claw loop — from Telegram, through Claude AI, via FCM to a real Android device,
back to the relay server via HTTP callback, through a second Claude pass, and returns as a
natural language answer in the same language the question was asked.

This is the first test that requires a **real Android device** with the Claw app installed.
All previous smoke tests could run without one.

---

## What This Test Actually Proves

```
You type in Telegram:    "Spava li on?"
                                    ↓
Relay receives message at /webhook endpoint
                                    ↓
Claude identifies intent → calls get_device_context tool
Relay stores original question in PendingCallbackStore (30s timeout starts)
                                    ↓
FCM push delivered to Android device
                                    ↓
Android executes DeviceContextTool:
  reads battery, screen state, sound profile, ambient light, motion sensor
                                    ↓
Android POSTs result to relay /callback:
  { chatId: 8608523419, tool: "get_device_context",
    result: "Battery: 85% (discharging). Screen: off. Sound: silent (vol 0/15).
             Light: dark (3 lux). Motion: stationary." }
                                    ↓
CallbackReceiver claims original question, cancels timeout
                                    ↓
IntentParser.interpretResult() — second Claude call:
  "The person asked: 'Spava li on?'
   The device returned: Battery 85%, screen off, silent, dark, stationary.
   Write a 2-3 sentence answer in Serbian with a conclusion."
                                    ↓
Person A sees in Telegram:
  "Ekran je ugašen, zvuk je isključen i prostorija je tamna.
   Uređaj nije pomeren u poslednjem očitavanju.
   Na osnovu toga, verovatno spava."
```

**Two things make this test different from everything before it:**

1. **A real Android device participates** — the tool executes on actual hardware,
   reading real sensors. The result is not mocked or simulated.
2. **Claude answers, not just dispatches** — the response is not Claude's pre-execution
   guess ("I'll check the device state...") but a post-execution interpretation of real data.

---

## What Is NOT Tested Here

| Feature | Phase |
|---|---|
| media_control, location_fetcher, camera_capture tools | Phase 3 |
| RSA encryption of command payload | Phase 3, Day 23 |
| QR code pairing ceremony | Phase 3, Day 24 |
| SMS kill switch | Phase 3, Day 25 |
| TTL-limited access tokens | Phase 3, Day 26 |

---

## Prerequisites

**Relay server (Phase 1 complete):**

| What | Why |
|---|---|
| Telegram bot token (from @BotFather) | Relay receives messages and sends replies |
| Anthropic API key | Intent parsing (first Claude call) + result interpretation (second Claude call) |
| Firebase project + service account JSON | FCM push delivery to Android |
| ngrok | Exposes local relay to Telegram webhook AND to Android /callback |

**Android device:**

| What | Why |
|---|---|
| Android 8.0+ device with internet | Runs the Claw app |
| Claw app installed (built from this repo) | Receives FCM, executes tools, POSTs callback |
| USB debugging not required | The app runs standalone once installed |

> **Note on the relay URL:** Both Telegram (webhook) and the Android app (callback)
> need to reach the relay server. A single ngrok tunnel covers both —
> ngrok exposes port 8080 with one public URL, and both `/webhook` and `/callback`
> are registered on that same port.

---

## One-Time Setup — Android App

Build and install the Claw app from Android Studio, then complete the two-field setup
in the app's main screen before running this test.

### 1. Get the FCM Device Token

Open the Claw app on the Android device. The **FCM Device Token** section at the top
of the screen displays the registration token for this device. Copy it — you'll need
it as the `FCM_DEVICE_TOKEN` environment variable on the relay server.

> The token is also printed to Android Studio Logcat (filter by `TelegramClaw`):
> `FCM Token: dU8kXm2T...`

### 2. Configure the Bot Token

In the Claw app, find the **Bot Token** field. Paste the Telegram bot token
(the same token used by the relay server — from @BotFather). Tap **Save Settings**.

This is what allows the Android app to fall back to direct Telegram messaging
if the relay callback URL is not set or unreachable.

### 3. Configure the Relay Callback URL

In the Claw app, find the **Relay Callback URL** field. Enter the **base URL** of the
running relay server — the ngrok URL **without** any path:

```
https://abc123.ngrok.io          ✓  correct
https://abc123.ngrok.io/webhook  ✗  wrong — do not include the path
https://abc123.ngrok.io/callback ✗  wrong — the app appends /callback automatically
```

Tap **Save Settings**.

> If no relay URL is configured, the app falls back to Day 17 behavior:
> Android sends the raw result directly to Telegram, bypassing Claude's interpretation.

### 4. Enable Tool Permissions

In the Claw app, enable the tools needed for this test:

- **get_device_context** — ON (required for "is he sleeping?" type questions)
- **audio_manager** — ON (optional, for the volume test below)
- **notification_sender** — ON (optional, for the notification test below)

All other tools can remain OFF.

---

## Running the Test

### Step 1 — Start ngrok

```
ngrok http 8080
```

Copy the `https://` URL. You will use it for both `WEBHOOK_URL` and the Relay Callback URL in the app.

### Step 2 — Build and start the relay server

```powershell
cd D:\AndroidStudioProjects\TelegramClaw\telegram-claw-relay
mvn package -q

$env:TELEGRAM_BOT_TOKEN             = "1234567890:ABCdef..."
$env:WEBHOOK_URL                    = "https://xxxx.ngrok.io/webhook"
$env:ANTHROPIC_API_KEY              = "sk-ant-..."
$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\Users\yourname\firebase-key.json"
$env:FCM_DEVICE_TOKEN               = "dU8kXm2T...4Yv9q"

java -jar target\telegram-claw-relay-1.0-SNAPSHOT.jar
```

Expected startup output — check these lines:

```
  TELEGRAM_BOT_TOKEN:             SET
  WEBHOOK_URL:                    https://xxxx.ngrok.io/webhook
  ANTHROPIC_API_KEY:              SET
  GOOGLE_APPLICATION_CREDENTIALS: C:\Users\yourname\firebase-key.json
  FCM_DEVICE_TOKEN:               SET
Webhook registered. Telegram confirmed: "Webhook was set."
IntentParser initialized | model: claude-haiku-4-5-20251001 | tools: 6
Server is up on port 8080. Waiting for Telegram updates...
```

### Step 3 — Test 1: Device context query

Send a message to the bot asking about the device state. Try in Serbian or English:

| What you send | Expected tool |
|---|---|
| `Spava li on?` | `get_device_context` |
| `Is he sleeping?` | `get_device_context` |
| `Koliko mu baterije ima?` | `get_device_context` |
| `Is he awake?` | `get_device_context` |

**Expected relay log (in order):**

```
[MESSAGE] From: Cvele (id=8608523419) | Text: "Spava li on?"
[INTENT]  Tool: get_device_context | Params: {}
[COMMAND] Tool: get_device_context | Params: {} | ChatId: 8608523419
[PENDING] Stored for chatId=8608523419 | timeout=30s | question="Spava li on?"
[FCM]     Dispatched 'get_device_context' → device. Message ID: projects/.../messages/...
[CALLBACK] tool=get_device_context | chatId=8608523419 | result=Battery: 85%...
[PENDING]  Claimed for chatId=8608523419
[INTERPRET] get_device_context → Ekran je ugašen, zvuk je isključen...
[REPLY]    Sent to chatId=8608523419: "Ekran je ugašen..."
```

**Expected Android Logcat** (filter by `TelegramClaw`):

```
[FCM] Command received | tool=get_device_context | chatId=8608523419
[EXECUTE] tool=get_device_context | chatId=8608523419
[get_device_context] Battery: 85% (discharging). Screen: off. Sound: silent...
Callback posted to relay | chatId=8608523419 tool=get_device_context
```

**Expected Telegram message received by Person A:**

A single natural language message in the same language as the question, ending with
a clear conclusion. Example:

> *"Ekran je ugašen, zvuk je isključen i prostorija je tamna. Uređaj nije pomeren.
> Na osnovu toga, verovatno spava."*

> *"The screen is off, the device is on silent, and the ambient light is very low.
> The device appears stationary. Based on this, they are most likely sleeping."*

**This single message — in the question's language, with a conclusion — is the smoke test passing.**

### Step 4 — Test 2: Action tool with callback confirmation

Send a volume command. Unlike query tools, action tool replies are sent immediately
by the relay without waiting for a callback interpretation:

```
Pojacaj mu zvono na 80%
```

Expected Telegram reply (immediate, from relay):
> *"Pojačavam zvono na 80%. Šaljem komandu na uređaj."*

Then, a second message arrives from Android via callback:
> *"Volume set to index 12/15 (80%)"*

### Step 5 — Test 3: Offline device (timeout)

This test verifies the 30-second timeout behavior when the device is unreachable.

1. Power off the Android device (or disable its internet connection)
2. Send `Is he sleeping?` to the bot
3. Wait approximately 30 seconds

Expected Telegram message:
> *"The device did not respond. It may be offline or out of battery."*

Expected relay log:
```
[PENDING] Stored for chatId=8608523419 | timeout=30s
[FCM]     Dispatched 'get_device_context' → device. Message ID: ...
[TIMEOUT] No callback from device for chatId=8608523419 — sending offline notice.
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Only one Telegram message: "I'll check the device state..." — no follow-up | Relay URL not configured in Android app, or wrong URL (includes `/webhook`) | Open app → check Relay Callback URL field — should be base URL only, e.g. `https://abc123.ngrok.io` |
| Relay log shows `[UNKNOWN_UPDATE]` for callback payload | Android is POSTing to `/webhook` instead of `/callback` — relay URL in app contains `/webhook` | Remove the path from the relay URL in app settings |
| Android Logcat shows callback sent but relay log shows nothing | Relay running old JAR (before Day 18) | Run `mvn package -q` and restart with the new JAR |
| `[TIMEOUT]` fires but device is on | Device has no internet, or FCM not delivered | Check device WiFi/data; wait for FCM retry |
| Claude answers in English regardless of question language | `originalQuestion` is null — `PendingCallbackStore` not populated | Verify relay JAR includes Day 19 changes — rebuild if needed |
| No `[CALLBACK]` in relay log, but Logcat shows "Callback posted" | ngrok session expired or restarted (new URL) | Restart ngrok, update `WEBHOOK_URL` and Relay Callback URL in app |
| `[DISPATCH_FAIL] UNREGISTERED` | FCM device token is from a different Firebase project | Check that `google-services.json` in the app matches your Firebase project |

---

## What Changes Across the Three Test Days

| | Day 5-6 | Day 17 | Day 18-19 |
|---|---|---|---|
| Telegram message received | ✓ | ✓ | ✓ |
| Claude picks tool | ✓ | ✓ | ✓ |
| FCM push to Android | ✓ | ✓ | ✓ |
| Android executes tool | ✗ | ✓ | ✓ |
| Result returned to Person A | ✗ | Raw data, direct to Telegram | ✓ Natural language, via relay |
| Claude interprets the result | ✗ | ✗ | ✓ |
| Answer in question's language | ✗ | ✗ | ✓ |
| Conclusion ("probably sleeping") | ✗ | ✗ | ✓ |
| Offline device → timeout notice | ✗ | ✗ | ✓ |

---

## Teardown

Stop the relay server: `Ctrl+C` in its terminal.  
Stop ngrok: `Ctrl+C` in its terminal.  

The Android app continues running as a foreground service — it will restart automatically
after device reboot via `BootReceiver`. No teardown needed on the device side.
