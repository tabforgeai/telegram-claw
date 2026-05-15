# Running the Relay Server on Android via Termux

This guide walks through running the Telegram Claw relay server directly on an Android phone
using [Termux](https://termux.dev). No computer required after the initial setup.

**Why this matters:** If you install the Claw app and the relay on the same phone (or a second
old phone), you get a fully self-contained system — no VPS, no Raspberry Pi, no PC left running.

---

## What You Will Need

- Android phone with Termux installed (any phone running Android 7 or newer)
- The relay JAR file (`telegram-claw-relay-1.0.0-SNAPSHOT.jar`)
- Your Firebase service account JSON file
- A free [ngrok](https://ngrok.com) account

The relay JAR is a self-contained fat JAR — all dependencies are bundled. You do not need
Maven or any build tools on the Android device.

---

## Step 1 — Install Termux

**Important:** Do NOT install Termux from Google Play. The Play Store version is outdated and
no longer receives package updates.

Install from [F-Droid](https://f-droid.org/packages/com.termux/) or directly from the
[Termux GitHub releases page](https://github.com/termux/termux-app/releases).

After installing, open Termux and run:
```
pkg update && pkg upgrade -y
```

---

## Step 2 — Disable Battery Optimization for Termux

Android aggressively kills background apps to save battery. Termux must be exempted.
Samsung devices are especially aggressive — follow the Samsung steps carefully.

**Samsung (One UI / Samsung Experience):**
1. Settings → **Device Care** (or "Battery and device care") → **Battery**
2. → **App power management** → "Apps that won't be put to sleep" → **Add** → select Termux
3. Also check: Settings → Apps → Termux → Battery → set to **Unrestricted**

**Stock Android / Pixel:**
1. Settings → Apps → Termux → Battery → **Unrestricted**

**All devices:** if Termux appears in a "Sleeping apps" or "Deep sleeping apps" list anywhere,
remove it — this is a second kill layer that works independently of the above setting.

Without this, Android will kill your relay server within minutes of the screen turning off.

---

## Step 3 — Install Java 21

In Termux:
```
pkg install openjdk-21
```

Verify:
```
java -version
```

You should see something like: `openjdk version "21.x.x"`. Installation takes 2–5 minutes
depending on your connection.

---

## Step 4 — Transfer the Relay JAR to Your Phone

### Option A — ADB (if you have a computer handy)

Build the JAR on your computer first:
```bash
cd telegram-claw-relay
mvn package -q
```

Then push to the phone:
```bash
adb push target/telegram-claw-relay-1.0.0-SNAPSHOT.jar /sdcard/Download/
```

In Termux, enable storage access (first time only):
```
termux-setup-storage
```

Then copy to your Termux home:
```
cp ~/storage/downloads/telegram-claw-relay-1.0.0-SNAPSHOT.jar ~/relay.jar
```

### Option B — Download from your own server or cloud storage

If you host the JAR somewhere (Google Drive, Dropbox, your own server), download it directly:
```
curl -L "YOUR_DIRECT_DOWNLOAD_URL" -o ~/relay.jar
```

Direct download links from Google Drive:
```
# Replace FILE_ID with your file's ID from the share URL
curl -L "https://drive.google.com/uc?export=download&id=FILE_ID" -o ~/relay.jar
```

---

## Step 5 — Transfer the Firebase Service Account JSON

The relay needs your Firebase credentials JSON to send FCM messages to the Claw app.

Transfer it the same way as the JAR:

Via ADB:
```bash
adb push ~/claw-firebase-credentials.json /sdcard/Download/
```

In Termux:
```
cp ~/storage/downloads/claw-firebase-credentials.json ~/claw-firebase-credentials.json
```

Keep this file in your Termux home directory (`~`), which maps to
`/data/data/com.termux/files/home/`.

---

## Step 6 — Set Up ngrok on Android

Telegram's webhook system requires a public HTTPS URL. ngrok creates a secure tunnel
from a public URL to your phone's local port 8080.

### 6a — Download the ngrok binary

1. On your PC, go to [ngrok.com/download](https://ngrok.com/download)
2. Select **Linux** and **arm64**
3. Copy the download link for the `.tgz` file

In Termux, paste your copied URL:
```
curl -L "PASTE_NGROK_ARM64_TGZ_URL_HERE" -o ngrok.tgz
tar xzf ngrok.tgz
chmod +x ngrok
```

> **Why not just `pkg install ngrok`?** ngrok is not in the Termux package repository.
> The binary must be downloaded manually from ngrok.com.

### 6b — Authenticate ngrok

1. Sign up at [ngrok.com](https://ngrok.com) (free)
2. Go to Your Authtoken in the ngrok dashboard
3. In Termux:
```
./ngrok config add-authtoken YOUR_AUTHTOKEN_HERE
```

---

## Step 7 — Create a Startup Script

Create a script that sets all environment variables and starts the relay:

```
nano ~/start-relay.sh
```

Paste the following (fill in your values):

```bash
#!/data/data/com.termux/files/usr/bin/bash

export TELEGRAM_BOT_TOKEN="1234567890:ABCdef..."
export WEBHOOK_URL="https://YOUR-NGROK-URL.ngrok-free.app/webhook"
export GROQ_API_KEY="gsk_..."
export GOOGLE_APPLICATION_CREDENTIALS="$HOME/claw-firebase-credentials.json"
export FCM_DEVICE_TOKEN="the token from the Claw app"
export DEVICE_PUBLIC_KEY="the public key from the Claw app"
export AUTHORIZED_USER_IDS="your Telegram user ID"

java -jar "$HOME/relay.jar"
```

Make it executable:
```
chmod +x ~/start-relay.sh
```

---

## Step 8 — Start Everything

You need two Termux sessions running simultaneously. Swipe right in Termux (or use the
keyboard shortcut) to open a new session.

### Session 1 — Start ngrok

```
./ngrok http 8080
```

Note the `https://` URL shown (e.g., `https://abc123.ngrok-free.app`).

Update `WEBHOOK_URL` in your `start-relay.sh` with this URL + `/webhook`:
```
https://abc123.ngrok-free.app/webhook
```

### Session 2 — Acquire wake lock and start relay

The wake lock prevents Android from throttling the CPU when the screen is off:
```
termux-wake-lock
bash ~/start-relay.sh
```

You should see the startup banner confirming all variables are set.

---

## Step 9 — Register the Webhook

The relay registers the webhook automatically at startup when `WEBHOOK_URL` is set.
Watch the logs for:
```
Webhook registered successfully
```

Then in the Claw app on your phone, set the **Relay Callback URL** to your ngrok URL
(without `/webhook`):
```
https://abc123.ngrok-free.app
```

---

## Special Case: Relay and Claw App on the Same Phone

If you run both the relay server and the Claw app on the **same Android phone**, you can
avoid going through ngrok for the callback:

- Telegram → ngrok → relay (port 8080) — this path still needs ngrok
- Relay → FCM → Claw app → **localhost:8080/callback** — this can stay local

In the Claw app's **Relay Callback URL** setting, use:
```
http://localhost:8080
```

This means the Claw app posts the tool result directly to `localhost:8080/callback`
instead of going out through ngrok and back in. Lower latency, one less round-trip.

> **Note:** The relay still needs ngrok for the Telegram webhook direction. Only the
> callback from the app to the relay can use localhost.

---

## Keeping the Relay Running Overnight

### Basic approach — screen on or Termux notification

As long as Termux is visible or has an active notification (which it does when a session
is running), Android is much less likely to kill it. Combined with battery optimization
disabled (Step 2) and `termux-wake-lock`, this is reliable for most devices.

### More robust — termux-services (optional)

termux-services uses `runit` to manage background services with automatic restart:

```
pkg install termux-services
```

Create a service directory:
```
mkdir -p ~/.config/sv/claw-relay/log
```

Service run script (`~/.config/sv/claw-relay/run`):
```bash
#!/data/data/com.termux/files/usr/bin/bash
export TELEGRAM_BOT_TOKEN="..."
export WEBHOOK_URL="..."
# ... all your env vars ...
exec java -jar /data/data/com.termux/files/home/relay.jar
```

```
chmod +x ~/.config/sv/claw-relay/run
sv-enable claw-relay
sv start claw-relay
```

Check status: `sv status claw-relay`

> **Limitation:** ngrok URLs change on every restart. If you use termux-services, also set
> up ngrok with a static domain (free on ngrok for one domain) and hardcode it in your
> env vars. Otherwise you must update `WEBHOOK_URL` every time.

---

## ngrok Static Domain (Recommended for Long-Running Setup)

Free ngrok accounts get one static domain:

1. In the ngrok dashboard → **Domains** → **New Domain**
2. Copy the static hostname (e.g., `yourword-yourword-1234.ngrok-free.app`)
3. Start ngrok with:
   ```
   ./ngrok http --domain=yourword-yourword-1234.ngrok-free.app 8080
   ```
4. Hardcode this URL in `start-relay.sh` — it never changes

With a static domain, you set `WEBHOOK_URL` once and never touch it again.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `java: not found` | openjdk-21 not installed | `pkg install openjdk-21` |
| `Error: Unable to access jarfile` | Wrong path to relay.jar | Check `ls ~/relay.jar` |
| Relay stops after screen off | Battery optimization not disabled | Repeat Step 2 |
| ngrok session expired (free tier) | Free ngrok sessions expire after 8h | Restart ngrok; use static domain |
| "Webhook registration failed" | `WEBHOOK_URL` not updated after ngrok restart | Update `WEBHOOK_URL` and restart relay |
| Callback never arrives | Claw app has wrong Relay Callback URL | Check app settings — use `http://localhost:8080` for same-device, or ngrok URL for different-device |
| Firebase error at startup | Wrong path to credentials JSON | Check `echo $GOOGLE_APPLICATION_CREDENTIALS` and `ls` the file |
| `termux-wake-lock: command not found` | termux-tools not installed | `pkg install termux-tools` |

---

## Resource Usage

Typical resource usage on an idle phone (no active commands):
- **RAM:** ~150–200 MB (JVM heap + relay overhead)
- **CPU:** <1% idle, spikes to 10–30% during a command
- **Battery:** Minimal at idle; wake lock prevents deep sleep but modern phones handle this well
- **Storage:** ~15 MB for the fat JAR + negligible for `tokens.json`

An old phone (Android 8+, 2 GB RAM) is sufficient to run the relay full-time.

---

## Comparison: Hosting Options

| Option | Cost | Setup | Always-on | Technical skill |
|---|---|---|---|---|
| Termux (this guide) | Free | 20 min | Yes (with wake lock) | Low |
| VPS ($5/month) | ~$5/mo | 30 min | Yes | Medium |
| Raspberry Pi | One-time ~$35 | 45 min | Yes | Medium |
| PC (home computer) | Free if already owned | 15 min | Only when PC is on | Low |

For most personal use cases, Termux on an old Android phone is the easiest always-on option.
