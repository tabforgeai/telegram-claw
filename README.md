# Telegram Claw

> OpenClaw for Android — built for people, not developers.

![Telegram Claw — one phone controls another](assets/telegram-claw-banner.png)

You want to know if your elderly parent is okay. Whether your kid got home. Whether your partner is awake.
You don't want to call. You don't want to install an app on their phone and explain how it works.
You just want to send a message — and get an answer.

---

**"Is he sleeping?"**

You send that to a Telegram bot. Telegram Claw reads the device sensors, checks the screen state,
looks at motion and ambient light, and replies:

> *"Phone hasn't moved in 2 hours, screen is off, room is dark. Probably sleeping."*

No commands. No syntax. No technical knowledge required from the sender.

---

## For the technically curious

Natural language → **Groq** (free, default) or **Claude** → Android hardware control — over Telegram, with no cloud dependency.

Each person creates a dedicated Telegram bot (2 minutes, free via BotFather).
A self-hosted Java relay server receives Telegram messages, calls the Claude API to parse intent,
and pushes structured commands to the Android device via FCM.
The Android app executes locally, checks permissions, and sends the result back.

```
Person A types:   "Turn up his ringer, I need to reach him urgently"
                              ↓
         Claude identifies:   audio_manager { stream: RING, level: 100, force_ping: true }
                              ↓
         Android executes:    ringer set to maximum, silent mode overridden
                              ↓
         Person A receives:   "Ringer set to maximum. Silent mode overridden."
```

Everything runs on your own infrastructure. No Telegram Claw servers see your messages.

---

## 6 Things You Can Do

| Tool | What it does | Permission |
|---|---|---|
| `get_device_context` | Battery, screen state, sound profile, motion, ambient light | None — passive sensors |
| `media_control` | Play, pause, skip, or open a Spotify/YouTube link | None |
| `audio_manager` | Set volume or force a loud alert ping through silent mode | WRITE_SETTINGS (one-time) |
| `notification_sender` | Display a message as toast, notification, or fullscreen alert | POST_NOTIFICATIONS |
| `location_fetcher` | Get current GPS coordinates | Always requires confirmation |
| `camera_capture` | Take a photo and receive it in Telegram | Always requires confirmation |

---

## 8 Security Protocols

Telegram Claw is built around the principle that the device owner is always in control.

1. **Human-in-the-Loop** — sensitive commands (location, camera) always show a confirmation dialog. 60-second timeout → auto-deny.
2. **End-to-End Encryption** — commands are RSA-encrypted with the device's public key. Only the Android Keystore can decrypt them. The relay server never sees the payload.
3. **Permission Manifest** — every tool is off by default. The device owner explicitly enables each one.
4. **TTL-Limited Tokens** — access expires. Options: 2h / 24h / 7 days / permanent. Expired = message dropped.
5. **Transparent Audit Log** — every executed and denied command is written to an immutable on-device log. Export to CSV.
6. **Out-of-Band Pairing** — authorization happens via QR code or SMS PIN, never via Telegram itself. Knowing the bot username grants nothing.
7. **SMS Kill Switch** — if the authorized user's Telegram account is compromised, one SMS revokes all access. Works offline.
8. **Rate Limiting + Auto-Freeze** — more than 10 commands in 60 seconds triggers an automatic sender freeze and notifies the device owner.

---

## Architecture

```
Person A (Telegram)
        │  message: "Is he sleeping?"
        ▼
Telegram Bot API  ──HTTPS POST──►  Relay Server (Java / Maven)
                                         │
                                         │  Claude API (intent parsing)
                                         │  audio_manager { level: 100 }
                                         │
                                         │  FCM push (encrypted)
                                         ▼
                                   Android Claw App
                                         │
                                         │  checks PermissionManifest
                                         │  shows confirmation if needed
                                         │  executes via Android APIs
                                         │  writes to AuditLogger
                                         ▼
                                   Result → Relay → Telegram sendMessage → Person A
```

**Two modules:**
- `telegram-claw-relay` — Java 21, Maven, self-hosted relay server (BSL 1.1)
- `telegram-claw-android` — Java, Android 8.0+, the Claw app on the device (Apache 2.0)

---

## Current Status

> `v0.3.0` — All 6 tools working. All 8 security protocols implemented.

**What works right now:**

| | |
|---|---|
| ✅ | Relay server receives Telegram messages via webhook |
| ✅ | Claude AI parses natural language into structured tool calls |
| ✅ | FCM push — command dispatched to Android device via Firebase |
| ✅ | E2E Encryption — RSA-OAEP + AES-256-GCM, private key stays in Android Keystore (Protocol 2) |
| ✅ | Authorization — static whitelist + dynamic PIN pairing, survives relay restarts (Protocols 4, 6) |
| ✅ | Rate limiting — auto-freeze on command flood (Protocol 8) |
| ✅ | Android app receives FCM commands via persistent ForegroundService |
| ✅ | Permission Manifest — all tools off by default, dedicated settings screen (Protocol 3) |
| ✅ | Audit Log — every command logged on-device, auto-pruned after 30 days (Protocol 5) |
| ✅ | `get_device_context` — battery, screen, sound profile, ambient light, motion |
| ✅ | `audio_manager` — sets volume, overrides silent mode with force ping |
| ✅ | `notification_sender` — toast, status bar notification, heads-up alert |
| ✅ | `media_control` — play, pause, skip |
| ✅ | `location_fetcher` — GPS coordinates, always shows 60-second confirmation dialog (Protocol 1) |
| ✅ | `camera_capture` — takes photo, sends it back to Telegram, always confirms first (Protocol 1) |
| ✅ | Out-of-Band Pairing — 6-character PIN, 10-minute window, single-use (Protocol 6) |
| ✅ | TTL Tokens — paired access persists across relay restarts, expires after 30 days (Protocol 4) |
| ✅ | SMS Kill Switch — one SMS disables all tools and stops the service instantly (Protocol 7) |
| ✅ | Full callback loop: Android POSTs result → relay → Claude interprets → natural language answer |
| ✅ | Language detection — Claude answers in the same language the question was asked |
| ✅ | Offline timeout — 70-second grace period, then "device did not respond" notice |

**Want to try it today?**

Four guides walk you through the full stack, step by step.
The first three need only a computer — no Android device required.
The fourth runs the complete loop on a real phone.

1. [`docs/smoke-test-phase1.md`](telegram-claw-relay/docs/smoke-test-phase1.md) — get the relay server running and receive your first Telegram message
2. [`docs/smoke-test-phase1-day3-4.md`](telegram-claw-relay/docs/smoke-test-phase1-day3-4.md) — send *"Is he sleeping?"* and watch Claude decide which tool to call
3. [`docs/smoke-test-phase1-day5-6.md`](telegram-claw-relay/docs/smoke-test-phase1-day5-6.md) — FCM push dispatched to a real Android device
4. [`docs/smoke-test-phase2-day17-19.md`](telegram-claw-relay/docs/smoke-test-phase2-day17-19.md) — **full loop on a real Android device:** question → Claude → FCM → Android sensors → callback → Claude interprets → natural language answer in your language *(requires an Android device)*

All you need for guides 1–3: Java 21, Maven, a Telegram bot token (free, 2 minutes via BotFather), and an Anthropic API key.

> **Default AI provider:** The relay server uses **Groq** (free tier: 14,400 requests/day — typical personal use costs nothing).
> Switch to Claude by setting `LLM_PROVIDER=anthropic`. No code changes required.

---

## Roadmap

| Tag | What's included |
|---|---|
| `v0.1.0` ✅ | Relay server: Telegram webhook + Claude intent parsing + FCM dispatch |
| `v0.2.0` ✅ | Android app: first real tool working end-to-end (`audio_manager`) |
| `v0.3.0` ✅ | All 6 tools + all 8 security protocols |
| `v1.0.0` | Stable release: relay + Android + docs + self-hosting guide |

---

## Self-Hosting

Telegram Claw is designed to be self-hosted. You own everything:

- Your Telegram bot (created in 2 minutes via [@BotFather](https://t.me/BotFather))
- Your relay server (a single JAR, runs anywhere Java 21 runs)
- Your Anthropic API key (Claude Haiku — approximately $0.0003 per command)
- Your Android device (the Claw app installed as an APK)

Setup guides:
- [`docs/smoke-test-phase1.md`](telegram-claw-relay/docs/smoke-test-phase1.md) — get the relay server running locally
- [`docs/smoke-test-phase1-day3-4.md`](telegram-claw-relay/docs/smoke-test-phase1-day3-4.md) — verify Claude intent parsing works

Full self-hosting guide coming with `v1.0.0`.

---

## Why Not Just Use...

| Tool | What's missing |
|---|---|
| Google Find My Device | Only your own device, no AI, no extensibility |
| Android automation tools | Require a PC with ADB connected — not usable remotely |
| OpenClaw | Explicit command syntax required — no natural language, no AI |
| Telegram bots (DIY) | You'd have to build everything in this repo yourself |

Most Android remote-control tools are built for one of two audiences: developers with a PC
connected via ADB, or power users with root access. They treat automation as the goal and treat
the controlled device as infrastructure.

Telegram Claw is built for a different relationship:

- **No PC, no ADB, no root.** The only connection between Person A and Person B's device is a
  Telegram message — over the internet, no USB cable, no developer mode required on Person B's
  side.

- **Built for your primary phone.** Other automation tools are explicitly not meant for everyday
  devices. Telegram Claw is designed for a phone carried 24 hours a day by someone who isn't
  technical.

- **Consent is the architecture, not a setting.** Location and camera always require a
  confirmation dialog on Person B's device. There is no way to bypass it remotely. If Person B
  doesn't tap Allow within 60 seconds, the command is automatically denied.

- **Person B doesn't need to know anything technical.** They install an APK and enable the tools
  they are comfortable with. Person A does all the setup.

- **You control the infrastructure.** The relay runs on your server. No Telegram Claw company
  sees your messages, your location, or your camera photos.

---

## Licensing

| Module | License |
|---|---|
| `telegram-claw-android` | [Apache 2.0](LICENSE-APACHE) — free for everyone |
| `telegram-claw-relay` | [Business Source License 1.1](LICENSE-BSL) — free for personal use; commercial hosting requires a license |
| `telegram-claw-enterprise` | Proprietary — commercial tools, contact TabForge |

BSL 1.1 converts to Apache 2.0 four years after first public release.
Self-hosting for personal or family use is explicitly permitted under BSL 1.1.

---

## Contributing

This project follows a split-license model. Contributions to `telegram-claw-android` (Apache 2.0)
are straightforward. Contributions to `telegram-claw-relay` (BSL 1.1) require a CLA to ensure
the license terms remain enforceable.

CLA and contribution guide coming with `v0.1.0`.

---

Built by [TabForge](https://github.com/tabforgeai).