# Telegram Claw — Self-Hosting Guide

This guide walks you through running your own Telegram Claw instance from scratch.
Total setup time: approximately 30–45 minutes.

---

## What You Will Need

**Accounts (all free):**
- [Telegram](https://telegram.org) account
- [Anthropic](https://console.anthropic.com) account — for Claude API key
- [Firebase](https://console.firebase.google.com) account — for push notifications (FCM)
- [ngrok](https://ngrok.com) account — to expose your relay server to the internet

**Hardware:**
- Android phone, Android 8.0 or newer (the device you want to control)
- A computer to run the relay server (Windows, macOS, or Linux)

**Software on your computer:**
- Java 21 or newer — [download](https://adoptium.net)
- Android Studio — [download](https://developer.android.com/studio) (needed to build the Android app)
- ngrok — [download](https://ngrok.com/download)

---

## Step 1 — Create a Telegram Bot

Follow the [BotFather Setup Guide](botfather-setup.md) to create your bot in 3 minutes.

You will end up with a **bot token** that looks like: `1234567890:ABCdefGhijKlmnoPqrsTuvwxyz`

Keep it safe — it is the password to your bot.

---

## Step 2 — Get an AI API Key

Telegram Claw uses an AI model to understand natural language commands.
The default provider is **Groq** — free tier, no credit card required.

### Option A — Groq (recommended, free)

1. Go to [console.groq.com](https://console.groq.com)
2. Sign up or log in
3. Navigate to **API Keys** → **Create API Key**
4. Copy the key — you will need it later as `GROQ_API_KEY`

> **Cost:** Free tier provides 14,400 requests/day.
> Typical personal use (10–20 commands/day) costs nothing.

### Option B — Anthropic (Claude)

If you prefer Claude, set `LLM_PROVIDER=anthropic` in Step 6 and use an Anthropic API key instead.

1. Go to [console.anthropic.com](https://console.anthropic.com)
2. Sign up or log in
3. Navigate to **API Keys** → **Create Key**
4. Copy the key — you will need it later as `ANTHROPIC_API_KEY`

> **Cost:** Claude Haiku processes each command for approximately $0.0003 (~$0.10/month for typical use).

---

## Step 3 — Set Up Firebase

Firebase provides the push notification channel between the relay server and your Android device.

### 3a — Create a Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → name it anything (e.g. `my-telegram-claw`)
3. Disable Google Analytics if prompted (not needed) → **Create project**

### 3b — Add the Android app to your project

1. On the project overview, click the **Android icon** (Add app)
2. Package name: `ai.tabforge.telegramclaw`
3. Click **Register app**
4. Download `google-services.json` — save it, you will need it in Step 5
5. Click through the remaining steps (no code changes needed)

### 3c — Download relay server credentials

1. In Firebase Console → **Project Settings** (gear icon) → **Service accounts**
2. Click **Generate new private key** → **Generate key**
3. Save the downloaded JSON file somewhere safe (e.g. `~/claw-firebase-credentials.json`)

This file is the relay server's identity — keep it private and never commit it to git.

---

## Step 4 — Build the Android App

1. Clone the repository:
   ```
   git clone https://github.com/tabforgeai/telegram-claw.git
   cd telegram-claw
   ```

2. Copy your `google-services.json` from Step 3b into the `app/` folder:
   ```
   cp ~/Downloads/google-services.json app/google-services.json
   ```

3. Open the project in Android Studio

4. Connect your Android phone via USB and enable **USB debugging**:
   - Settings → About phone → tap **Build number** 7 times
   - Settings → Developer options → enable **USB debugging**

5. Click **Run ▶** in Android Studio — the app installs on your phone

6. Open the **Telegram Claw** app on your phone. You will see:
   - **FCM Device Token** — copy this, you need it in Step 6
   - **Device Public Key** — copy this, you need it in Step 6

---

## Step 5 — Configure the Android App

In the Telegram Claw app on your phone:

1. **Bot Token** section → paste your bot token from Step 1
2. **Relay Callback URL** section → leave blank for now (fill in after Step 7)
3. Tap **Save Settings**
4. **Permissions** section → tap **Manage Permissions** → enable the tools you want

---

## Step 6 — Run the Relay Server

### 6a — Build the relay JAR

```bash
cd telegram-claw-relay
mvn package -q
```

This produces `target/telegram-claw-relay-1.0.0-SNAPSHOT.jar`.

### 6b — Set environment variables

On Windows (PowerShell):
```powershell
$env:TELEGRAM_BOT_TOKEN    = "1234567890:ABCdef..."
$env:GROQ_API_KEY          = "gsk_..."
$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\path\to\claw-firebase-credentials.json"
$env:FCM_DEVICE_TOKEN      = "the token from the Claw app"
$env:DEVICE_PUBLIC_KEY     = "the public key from the Claw app"
$env:AUTHORIZED_USER_IDS   = "your Telegram user ID"
```

On macOS / Linux:
```bash
export TELEGRAM_BOT_TOKEN="1234567890:ABCdef..."
export GROQ_API_KEY="gsk_..."
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/claw-firebase-credentials.json"
export FCM_DEVICE_TOKEN="the token from the Claw app"
export DEVICE_PUBLIC_KEY="the public key from the Claw app"
export AUTHORIZED_USER_IDS="your Telegram user ID"
```

> **How to find your Telegram user ID:** send any message to [@userinfobot](https://t.me/userinfobot) on Telegram.

> **Using Anthropic instead of Groq?** Add `$env:LLM_PROVIDER = "anthropic"` and `$env:ANTHROPIC_API_KEY = "sk-ant-..."` instead of `GROQ_API_KEY`.

### 6c — Start the server

```bash
java -jar target/telegram-claw-relay-1.0.0-SNAPSHOT.jar
```

You should see the startup banner confirming all variables are set.

---

## Step 7 — Expose the Relay Server via ngrok

The relay server runs on your computer. Telegram needs a public HTTPS URL to send messages to it.

1. In a new terminal, run:
   ```bash
   ngrok http 8080
   ```

2. Copy the `https://` URL from ngrok output (e.g. `https://abc123.ngrok.io`)

3. Set the webhook so Telegram knows where to send messages:
   ```bash
   export WEBHOOK_URL="https://abc123.ngrok.io/webhook"
   ```
   Then restart the relay server — it registers the webhook automatically at startup.

4. Go back to the Claw app on your phone → **Relay Callback URL** → paste the same `https://abc123.ngrok.io` URL → **Save Settings**

---

## Step 8 — Test the Connection

1. Open Telegram and find your bot (the one you created in Step 1)
2. Send: `"What's the battery level?"`
3. Within a few seconds you should receive a reply like:
   > *"Battery is at 84%, charging. Screen is off."*

If it works — you're done. 🎉

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| No reply from bot | Webhook not registered | Restart relay server with `WEBHOOK_URL` set |
| "Unauthorized sender" in relay log | Your Telegram ID not in whitelist | Add your ID to `AUTHORIZED_USER_IDS` |
| "GROQ_API_KEY is not set" in banner | Missing Groq key | Set `GROQ_API_KEY` or switch to `LLM_PROVIDER=anthropic` |
| "FCM dispatch disabled" in banner | Missing Firebase credentials | Check `GOOGLE_APPLICATION_CREDENTIALS` path |
| App shows "Fetching token..." indefinitely | No internet on phone | Check wifi/data |
| Commands arrive but nothing happens on phone | Tools not enabled | Open Claw app → Manage Permissions |

---

## Optional: Add More Authorized Users

Instead of editing environment variables, use the built-in pairing system:

1. Open the Claw app → **Pairing** section → tap **Generate PIN**
2. Share the 6-character PIN with the person you want to authorize (WhatsApp, in person, etc.)
3. They send the PIN to your Telegram bot — they are immediately authorized
4. Access expires automatically after 30 days

---

## Optional: Enable E2E Encryption

If `DEVICE_PUBLIC_KEY` is set (it is, from Step 6b), all commands are automatically encrypted
end-to-end using RSA-OAEP + AES-256-GCM. The relay server never sees the plaintext command payload.
No additional configuration needed.

---

## Keeping It Running

ngrok URLs change every time you restart ngrok. For a permanent setup:

- Use a VPS (any $5/month server works) instead of your personal computer
- Use a static domain or a free service like [Render](https://render.com) or [Railway](https://railway.app)
- Set `WEBHOOK_URL` once and never touch it again

### Alternative: Run the relay on an Android phone via Termux

If you have an old Android phone lying around, you can run the relay on it —
no computer, no VPS, no monthly cost.

See [relay-on-android-termux.md](relay-on-android-termux.md) for the full guide.
