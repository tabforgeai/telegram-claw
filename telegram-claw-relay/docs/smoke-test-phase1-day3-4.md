# Phase 1 Smoke Test — Day 3-4: Claude Parses Natural Language into a Tool Call

**Goal:** Verify that a natural language Telegram message is correctly converted into a
structured tool call by Claude AI — without any Android app involved yet.

This test builds on the Day 2 smoke test (webhook reception). It adds the AI layer:
the relay server now does something meaningful with the message instead of just logging it.

---

## What This Test Actually Proves

The core value of Telegram Claw is that Person A does not need to know any command syntax.
They type naturally — in Serbian, English, or any language — and Claude figures out what
they want.

This test proves that chain works:

```
You type in Telegram:     "Pojacaj mu zvono, hitno ga trazim"
                                        ↓
Telegram Bot API sends an HTTP POST to our /webhook endpoint
                                        ↓
TelegramUpdateReceiver parses the Update, extracts the message text and sender chat ID
                                        ↓
IntentParser sends the text to Claude API (claude-haiku-4-5-20251001 by default)
with the definitions of all 6 available tools as JSON schemas
                                        ↓
Claude reads the message, identifies the intent, and returns a tool call:
  tool: "audio_manager"
  parameters: { "stream": "RING", "level": 100, "force_ping": true }
                                        ↓
IntentParser wraps this into a ClawCommand and returns it to TelegramUpdateReceiver
                                        ↓
Relay server logs:
  [INTENT] Tool: audio_manager | Params: {"stream":"RING","level":100,"force_ping":true}
  [COMMAND] Tool: audio_manager | Params: {...} | ChatId: 8608523419
```

The command does NOT reach any Android device yet — that is Phase 1 Day 5-6 (FCM push).
The reply does NOT go back to Telegram yet — that is Phase 1 Day 8 (ResponseRouter).

---

## What Is NOT Tested Here

| Feature | Phase |
|---|---|
| FCM push to Android device | Phase 1, Day 5–6 |
| Android app receiving the command | Phase 2 |
| Telegram reply sent back to Person A | Phase 1, Day 8 |
| Authorization (is this sender allowed?) | Phase 1, Day 7 |
| RSA encryption of command payload | Phase 3 |

---

## Prerequisites

Everything from the Day 2 smoke test, plus:

| What | Why |
|---|---|
| Anthropic API key | IntentParser calls the Claude API on every message |

Get an API key at [console.anthropic.com](https://console.anthropic.com) → **API Keys** → **Create Key**.
The key starts with `sk-ant-` or `sk-proj-` and is approximately 100–170 characters long.

> **Note:** There is a cost per message — Claude Haiku 4.5 uses roughly 500 input tokens
> and 200 output tokens per command, which comes to approximately $0.0003 per message.
> For personal testing with a handful of messages per day, this is negligible.

---

## Environment Variables

In addition to the Day 2 variables (`TELEGRAM_BOT_TOKEN`, `WEBHOOK_URL`), set:

**PowerShell:**
```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
$env:CLAUDE_MODEL      = "claude-haiku-4-5-20251001"   # optional — this is the default
```

**Windows Command Prompt:**
```
set ANTHROPIC_API_KEY=sk-ant-...
set CLAUDE_MODEL=claude-haiku-4-5-20251001
```

**Linux / macOS:**
```
export ANTHROPIC_API_KEY=sk-ant-...
export CLAUDE_MODEL=claude-haiku-4-5-20251001
```

> **Tip:** Verify your key was set correctly before starting the server:
> ```powershell
> Write-Host $env:ANTHROPIC_API_KEY.Length   # should be 100-170
> ```
> If the length is much higher (e.g. 1645), you copied a JSON blob or JWT token
> instead of the raw key. Go back to the API Keys page and copy only the key string.

---

## Running the Test

### Step 1 — Start ngrok (if not already running)

```
ngrok http 8080
```

Copy the `https://` URL and make sure `WEBHOOK_URL` is set to it + `/webhook`.

### Step 2 — Set all environment variables and start the server

In a single terminal (all variables must be in the same session as the `java` command):

```powershell
$env:TELEGRAM_BOT_TOKEN = "1234567890:ABCdef..."
$env:WEBHOOK_URL        = "https://xxxx.ngrok.io/webhook"
$env:ANTHROPIC_API_KEY  = "sk-ant-..."

cd D:\AndroidStudioProjects\TelegramClaw\telegram-claw-relay
java -jar target\telegram-claw-relay-1.0.0-SNAPSHOT.jar
```

Expected startup output:

```
  Telegram Claw Relay Server  |  Phase 1 Day 3
  PORT:                8080
  TELEGRAM_BOT_TOKEN:  SET
  WEBHOOK_URL:         https://xxxx.ngrok.io/webhook
  ANTHROPIC_API_KEY:   SET
  CLAUDE_MODEL:        claude-haiku-4-5-20251001
Registering webhook with Telegram: https://xxxx.ngrok.io/webhook
Webhook registered. Telegram confirmed: "Webhook was set."
IntentParser initialized | model: claude-haiku-4-5-20251001 | tools: 6
Server is up on port 8080. Waiting for Telegram updates...
```

Key lines to check:
- `ANTHROPIC_API_KEY: SET` — key is visible to the process
- `tools: 6` — all 6 tool definitions were built successfully

### Step 3 — Send a message to the bot

Open Telegram and send a message to your bot. Try natural language — the intent
does not need to match exact syntax. Examples:

| What you send | Expected tool |
|---|---|
| `Pojacaj mu zvono, hitno ga trazim` | `audio_manager` (stream=RING, level=100, force_ping=true) |
| `Gde je sada?` | `location_fetcher` |
| `Posalji mu poruku da dodje kuci` | `notification_sender` |
| `Ugasi mu muziku` | `media_control` (action=PAUSE) |
| `Koliko mu baterije ima?` | `get_device_context` |
| `Slikaj prednju kameru` | `camera_capture` (camera=FRONT) |

### Step 4 — Verify the log

You should see two lines per message:

```
[INTENT] Tool: audio_manager | Params: {"stream":"RING","level":100,"force_ping":true}
[COMMAND] Tool: audio_manager | Params: {"stream":"RING","level":100,"force_ping":true} | ChatId: 8608523419
```

`[INTENT]` is logged by `IntentParser` — Claude made its decision here.
`[COMMAND]` is logged by `TelegramUpdateReceiver` — the ClawCommand object was received.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `ANTHROPIC_API_KEY: NOT SET` in banner | Variable not visible to the Java process | Set it in the same terminal where you run `java -jar`, then restart |
| `401: invalid x-api-key` | Wrong or revoked API key | Create a new key at console.anthropic.com |
| `401: x-api-key header is required` | Key length is 0 — variable is empty | Check `Write-Host $env:ANTHROPIC_API_KEY.Length`; re-set the variable |
| `[INTENT_FAIL] Could not parse intent` | Claude returned no tool call | Add more context to your message, or check the model in use |
| No `[INTENT]` line in log | Message did not reach IntentParser | Check that `[MESSAGE]` line appears first — if not, see Day 2 troubleshooting |

---

## What Changes Between Day 2 and Day 3-4

| | Day 2 | Day 3-4 |
|---|---|---|
| Message received | ✓ | ✓ |
| Message logged | ✓ | ✓ |
| Claude API called | ✗ | ✓ |
| Tool call identified | ✗ | ✓ |
| Parameters extracted | ✗ | ✓ |
| Command sent to Android | ✗ | ✗ (Day 5-6) |
| Reply sent to Telegram | ✗ | ✗ (Day 8) |

---

## Model Selection

The model used for intent parsing is configurable via `CLAUDE_MODEL`.
The default is `claude-haiku-4-5-20251001` — fast and cheap, sufficient for
straightforward intent recognition.

Switch to `claude-sonnet-4-6` for more complex reasoning, such as:
- Multi-step conditional logic ("if battery is low, do X, else do Y")
- Ambiguous messages that need more context to resolve
- Non-standard language or very short messages

The model is hot-swappable — just change the environment variable and restart.
No code changes required.