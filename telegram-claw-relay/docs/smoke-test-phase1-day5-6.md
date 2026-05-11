# Phase 1 Smoke Test — Day 5-6: FCM Push Dispatched to Android Device

**Goal:** Verify that a natural language Telegram message travels the entire relay chain —
from Telegram, through Claude AI intent parsing, all the way to Firebase Cloud Messaging (FCM)
delivering a structured command push to a registered Android device.

This test builds on the Day 3-4 smoke test (Claude intent parsing). It adds the final relay
layer: the parsed command is now physically sent to an Android device via FCM.

---

## What This Test Actually Proves

After Day 3-4, the relay server could parse intent but the command went nowhere.
This test closes that gap — the command now leaves the server and reaches Firebase,
which delivers it to the registered device.

```
You type in Telegram:     "Turn up his ringer, I need to reach him urgently"
                                        ↓
Telegram Bot API sends an HTTP POST to our /webhook endpoint
                                        ↓
TelegramUpdateReceiver parses the Update, extracts the text and sender chat ID
                                        ↓
IntentParser sends the text to Claude API (claude-haiku-4-5-20251001 by default)
                                        ↓
Claude identifies the intent and returns a tool call:
  tool: "audio_manager"
  parameters: { "stream": "RING", "level": 100, "force_ping": true }
                                        ↓
CommandDispatcher builds an FCM data message and calls the Firebase API:
  data: { tool: "audio_manager", params: "...", chatId: "..." }
  token: <device FCM registration token>
                                        ↓
Firebase accepts the message and returns a Message ID:
  projects/android-telegram-claw/messages/0:1778496018229732%f86a2d16f9fd7ecd
                                        ↓
FCM delivers the push to the Android device
(the device receives the FCM push, but no handler acts on it yet — that is Phase 2)
```

**The Message ID is the proof.** A Message ID from Firebase means the push was accepted
and is guaranteed to be delivered to the device as soon as it is reachable.

---

## What Is NOT Tested Here

| Feature | Phase |
|---|---|
| Android app actually receiving and handling the FCM push | Phase 2, Day 12 |
| Any tool executing on the device (volume change, location, etc.) | Phase 2, Day 15–17 |
| Telegram reply sent back to Person A | Phase 1, Day 8 |
| Authorization check (is this sender allowed?) | Phase 1, Day 7 |
| RSA encryption of command payload | Phase 3 |

The device receives the FCM data message but has no `FirebaseMessagingService` to process it
yet. The phone will not visibly react. This is expected and correct.

---

## Prerequisites

Everything from the Day 3-4 smoke test, plus:

| What | Why |
|---|---|
| Firebase account | Free at [firebase.google.com](https://firebase.google.com) |
| Firebase project | Created in Firebase Console |
| Service account JSON | Downloaded from Firebase Console — authenticates the relay server |
| Android device with internet | To receive FCM pushes |
| Developer mode enabled on the device | To install the token-getter app via Android Studio |
| Android Studio installed | To build and deploy the token-getter app |

---

## One-Time Setup — Firebase Project

This is done once. Skip if you already have a Firebase project for this app.

### 1. Create the Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **"Add project"**
3. Name it (e.g. `android-telegram-claw`) — the name does not need to match anything in code
4. Disable Google Analytics (not needed)
5. Click **"Create project"**

### 2. Register the Android app in Firebase

1. In your Firebase project, click the **Android icon** ("Add app")
2. **Android package name:** `ai.tabforge.telegramclaw` — must match exactly
3. App nickname: `Telegram Claw` (optional)
4. Click **"Register app"**
5. Download `google-services.json` → place it in `telegram-claw-android/app/`
6. Click through the remaining setup steps (no code changes needed from Firebase wizard)

### 3. Download the service account key (for the relay server)

The relay server needs to authenticate with Firebase to send FCM messages.
This is done via a **service account** — a machine identity with a private key.

1. In Firebase Console → **Project Settings** (gear icon) → tab **"Service accounts"**
2. Click **"Generate new private key"** → download the JSON file
3. Save it somewhere **outside the project repository** (it is a secret — never commit it)
   Example: `C:\Users\yourname\firebase-telegram-claw-key.json`

> The JSON file contains a private RSA key. Anyone who has it can send FCM messages
> to all devices registered to your Firebase project. Treat it like a password.

---

## One-Time Setup — FCM Device Token

The relay server needs to know **which Android device** to send commands to.
Each installation of the Android app generates a unique FCM registration token —
a long string (~160 characters) that identifies that specific app on that specific device.

In Phase 2, the Android app will register this token with the relay server automatically
during the pairing ceremony. For this smoke test, we get the token manually using a
minimal token-getter app.

### Enable Developer Mode on the Android device

1. **Settings → About phone → Software information → Build number**
   Tap **Build number** 7 times → "Developer mode enabled" confirmation appears
2. **Settings → Developer options → USB debugging** → turn ON

### Install the token-getter app

1. Connect the Android device to your computer via USB cable
   (use a data cable, not a charge-only cable)
2. On the device: when prompted "Allow USB debugging?" → tap **Allow**
3. In Android Studio, the device should appear in the device selector (top toolbar)
4. Open the `telegram-claw-android` project in Android Studio
5. Make sure `google-services.json` is in the `app/` folder
6. Click **Run ▶** — Android Studio builds and installs the app on the device

The app opens and displays the FCM token on screen:

```
FCM Device Token

Copy the token below and set it as FCM_DEVICE_TOKEN env var on the relay server.

dU8kXm2T...4Yv9q  (full token, ~160 characters)
```

Long-press the token text to select and copy it.

> **Tip:** The token is also printed to Android Studio's Logcat tab.
> Filter by tag `TelegramClaw` to find the line: `FCM Token: dU8kXm2T...`
> Logcat is easier to copy from than the phone screen.

> **Note:** The device must have internet access when the app first opens —
> FCM token generation requires a call to Google's servers.

---

## Environment Variables

In addition to the Day 3-4 variables, set two new ones:

**PowerShell:**
```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\Users\yourname\firebase-telegram-claw-key.json"
$env:FCM_DEVICE_TOKEN               = "dU8kXm2T...4Yv9q"
```

**Windows Command Prompt:**
```
set GOOGLE_APPLICATION_CREDENTIALS=C:\Users\yourname\firebase-telegram-claw-key.json
set FCM_DEVICE_TOKEN=dU8kXm2T...4Yv9q
```

**Linux / macOS:**
```
export GOOGLE_APPLICATION_CREDENTIALS=/home/yourname/firebase-telegram-claw-key.json
export FCM_DEVICE_TOKEN=dU8kXm2T...4Yv9q
```

---

## Running the Test

### Step 1 — Start ngrok

```
ngrok http 8080
```

Copy the `https://` URL and set `WEBHOOK_URL` to it + `/webhook`.

### Step 2 — Set all environment variables and start the server

```powershell
$env:TELEGRAM_BOT_TOKEN            = "1234567890:ABCdef..."
$env:WEBHOOK_URL                   = "https://xxxx.ngrok-free.app/webhook"
$env:ANTHROPIC_API_KEY             = "sk-ant-..."
$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\Users\yourname\firebase-key.json"
$env:FCM_DEVICE_TOKEN              = "dU8kXm2T...4Yv9q"

cd D:\AndroidStudioProjects\TelegramClaw\telegram-claw-relay
java -jar target\telegram-claw-relay-1.0.0-SNAPSHOT.jar
```

Expected startup output:

```
---------------------------------------------------
  Telegram Claw Relay Server  |  Phase 1 Day 5-6
---------------------------------------------------
  PORT:                           8080
  TELEGRAM_BOT_TOKEN:             SET
  WEBHOOK_URL:                    https://xxxx.ngrok-free.app/webhook
  ANTHROPIC_API_KEY:              SET
  CLAUDE_MODEL:                   claude-haiku-4-5-20251001
  GOOGLE_APPLICATION_CREDENTIALS: C:\Users\yourname\firebase-key.json
  FCM_DEVICE_TOKEN:               SET
---------------------------------------------------
Registering webhook with Telegram: https://xxxx.ngrok-free.app/webhook
Webhook registered. Telegram confirmed: "Webhook was set."
IntentParser initialized | model: claude-haiku-4-5-20251001 | tools: 6
Firebase Admin SDK initialized from: C:\Users\yourname\firebase-key.json
Server is up on port 8080. Waiting for Telegram updates...
```

Key lines to check:
- `GOOGLE_APPLICATION_CREDENTIALS: <path>` — not "NOT SET"
- `FCM_DEVICE_TOKEN: SET`
- `Firebase Admin SDK initialized from: ...` — Firebase auth succeeded

### Step 3 — Send a message to the bot

Open Telegram and send a message to your bot. Use the same examples as Day 3-4:

| What you send | Expected tool | Expected FCM payload |
|---|---|---|
| `Turn up his ringer, I need to reach him urgently` | `audio_manager` | `{stream:RING, level:100, force_ping:true}` |
| `Gde je sada?` | `location_fetcher` | `{accuracy:HIGH}` |
| `Posalji mu poruku da dodje kuci` | `notification_sender` | `{message:..., display_mode:NOTIFICATION}` |
| `Koliko mu baterije ima?` | `get_device_context` | `{}` |

### Step 4 — Verify the log

You should now see four lines per message (two new lines compared to Day 3-4):

```
[MESSAGE] From: Cvele (id=8608523419) | Text: "Turn up his ringer, I need to reach him urgently!"
[INTENT]  Tool: audio_manager | Params: {"stream":"RING","level":100,"force_ping":true}
[COMMAND] Tool: audio_manager | Params: {"stream":"RING","level":100,"force_ping":true} | ChatId: 8608523419
[FCM]     Dispatched 'audio_manager' → device. Message ID: projects/android-telegram-claw/messages/0:1778496018229732%f86a2d16f9fd7ecd
```

**The `[FCM]` line with a Message ID is the smoke test passing.**

The Message ID format is always: `projects/<firebase-project-id>/messages/<unique-id>`

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `FCM dispatch disabled` in startup log | `GOOGLE_APPLICATION_CREDENTIALS` or `FCM_DEVICE_TOKEN` not set | Set both in the same terminal session as `java -jar` |
| `Failed to initialize Firebase Admin SDK` | Wrong path to credentials JSON, or file not found | Check the path — use absolute path, no quotes needed in PowerShell `$env:` syntax |
| `[DISPATCH_FAIL] ... INVALID_ARGUMENT` | Device token malformed or truncated | Re-copy the full token from the device screen or Logcat — it must be ~160 characters |
| `[DISPATCH_FAIL] ... UNREGISTERED` | Token is for a different Firebase project | Make sure the Android app uses the `google-services.json` from this same Firebase project |
| `[DISPATCH_FAIL] ... SERVICE_NOT_AVAILABLE` | Device has no internet at the time of push | Reconnect the device to WiFi/data and retry |
| `[INTENT]` line appears but no `[FCM]` line | CommandDispatcher failed silently | Look for `[DISPATCH_FAIL]` warning immediately after `[COMMAND]` |
| Phone screen shows nothing after push | Expected — no `FirebaseMessagingService` in the token-getter app | This is Phase 2 work. The push was delivered; the device just has no handler yet. |

---

## What Changes Between Day 3-4 and Day 5-6

| | Day 3-4 | Day 5-6 |
|---|---|---|
| Message received | ✓ | ✓ |
| Claude parses intent | ✓ | ✓ |
| Tool call identified | ✓ | ✓ |
| Command dispatched via FCM | ✗ | ✓ |
| Firebase Message ID returned | ✗ | ✓ |
| Android app handles the push | ✗ | ✗ (Phase 2) |
| Telegram reply to sender | ✗ | ✗ (Day 8) |

---

## Teardown

Stop the relay server: `Ctrl+C` in its terminal.  
Stop ngrok: `Ctrl+C` in its terminal.  

The Firebase credentials file can stay wherever you saved it — it is reused across sessions.
The FCM device token stays valid until the Android app is uninstalled or Firebase rotates it.