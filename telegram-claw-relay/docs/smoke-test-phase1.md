# Phase 1 Smoke Test — Telegram Message Arrives at Relay Server

**Goal:** Verify the end-to-end path: you send a Telegram message to your bot →
the relay server receives it → it appears in the server log.

No Android app needed yet. This test covers only the relay server side.

---

## Prerequisites

| What | Why |
|---|---|
| Java 21+ installed | To run the relay server JAR |
| Maven installed | To build the JAR (`mvn package`) |
| A Telegram account | To send a test message |
| ngrok installed | To give your localhost a public HTTPS address |

---

## Step 0 — Create a Telegram Bot (once, takes 2 minutes)

Telegram bots are not regular user accounts — they are accounts controlled by a program
(our relay server). Every bot has a **token**: a string that acts as both its identifier
and its password. It looks like this:

```
1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ
```

**How to create one:**

1. Open Telegram (mobile or [web.telegram.org](https://web.telegram.org))
2. Search for the contact `@BotFather` — this is Telegram's official bot for creating bots
3. Send it the message: `/newbot`
4. It will ask for a display name (e.g. `TelegramClawTest`) and a username
   (must end in `bot`, e.g. `telegramclawtest_bot`)
5. BotFather replies with your token — **save it somewhere safe**

You only do this once. The same bot and token are reused across all development sessions.

---

## Step 1 — Build the Relay Server JAR

```
cd telegram-claw-relay
mvn package -q
```

This produces `target/telegram-claw-relay-1.0.0-SNAPSHOT.jar` — a fat JAR with all
dependencies bundled. The `-q` flag suppresses Maven's verbose output.

---

## Step 2 — Start ngrok

The relay server runs on `localhost:8080`. Telegram cannot reach a private localhost address —
it needs a **public HTTPS URL**. ngrok creates a temporary tunnel from a public URL to your
local port.

```
ngrok http 8080
```

ngrok prints a forwarding line like this:

```
Forwarding  https://a3f7-91-150-22-44.ngrok.io -> http://localhost:8080
```

**Copy the `https://` URL** — you will need it in the next step.

> The URL changes every time you restart ngrok (on the free plan).
> Re-register the webhook whenever the URL changes (just restart the relay server with the new URL).

---

## Step 3 — Set Environment Variables

Open a terminal and set these three variables before starting the server.
All configuration is via environment variables — nothing is hard-coded.

**Windows (Command Prompt):**
```
set TELEGRAM_BOT_TOKEN=1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ
set WEBHOOK_URL=https://a3f7-91-150-22-44.ngrok.io/webhook
set PORT=8080
```

**Windows (PowerShell):**
```
$env:TELEGRAM_BOT_TOKEN = "1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ"
$env:WEBHOOK_URL        = "https://a3f7-91-150-22-44.ngrok.io/webhook"
$env:PORT               = "8080"
```

**Linux / macOS:**
```
export TELEGRAM_BOT_TOKEN=1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ
export WEBHOOK_URL=https://a3f7-91-150-22-44.ngrok.io/webhook
export PORT=8080
```

> Note the `/webhook` at the end of `WEBHOOK_URL` — this is the path that
> `TelegramUpdateReceiver` is registered on in `Main.java`.

---

## Step 4 — Start the Relay Server

In the **same terminal** where you set the environment variables:

```
java -jar target/telegram-claw-relay-1.0.0-SNAPSHOT.jar
```

Expected startup output:

```
---------------------------------------------------
  Telegram Claw Relay Server  |  Phase 1 Day 2
---------------------------------------------------
  PORT:                8080
  TELEGRAM_BOT_TOKEN:  SET
  WEBHOOK_URL:         https://a3f7-91-150-22-44.ngrok.io/webhook
---------------------------------------------------
Registering webhook with Telegram: https://a3f7-91-150-22-44.ngrok.io/webhook
Webhook registered. Telegram confirmed: "Webhook was set."
  Active webhook URL:   https://a3f7-91-150-22-44.ngrok.io/webhook
  Pending update count: 0
Server is up on port 8080. Waiting for Telegram updates...
```

If you see `Webhook registered` and `Server is up` — the setup is correct.

**Common problems:**

| Symptom | Cause | Fix |
|---|---|---|
| `TELEGRAM_BOT_TOKEN is not set` | Variable not set in this terminal | Set it and restart |
| `Telegram rejected webhook — error 401` | Invalid bot token | Double-check the token from BotFather |
| `Telegram rejected webhook — error 400` | URL is HTTP, not HTTPS | ngrok gives HTTPS — use that URL |
| `Cannot reach Telegram API` | No internet / firewall | Check connectivity |

---

## Step 5 — Send a Message to the Bot

1. In Telegram, search for your bot by username (e.g. `@telegramclawtest_bot`)
2. Open the chat and send any message, for example: `hello`

---

## Step 6 — Verify the Log

Switch back to the terminal where the relay server is running.
You should see a line like this:

```
[MESSAGE] From: Your Name (@your_username) | Text: "hello"
```

**That is the smoke test passing.** The message traveled:

```
Your Telegram → Telegram servers → ngrok tunnel → relay server → logged
```

---

## What Is NOT Tested Here

This smoke test only verifies message reception and logging. The following are
implemented in later phases and not yet active:

| Feature | Phase |
|---|---|
| Claude AI intent parsing (`"turn up the volume"` → `audio_manager` tool call) | Phase 1, Day 3–4 |
| FCM push to Android device | Phase 1, Day 5–6 |
| Authorization check (is this sender allowed?) | Phase 1, Day 7 |
| Telegram reply back to sender | Phase 1, Day 8 |
| Any Android tool execution | Phase 2 |

---

## Teardown

To stop the relay server: `Ctrl+C` in its terminal.  
To stop ngrok: `Ctrl+C` in its terminal.  

The webhook registration stays on Telegram's side until you register a new URL
or call `deleteWebhook`. This is fine — stale webhooks simply fail silently
until the next valid registration.
