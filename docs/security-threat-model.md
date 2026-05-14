# Telegram Claw — Security Threat Model

This document enumerates the assets Telegram Claw protects, the threats it mitigates, the protocols that provide that mitigation, and the things it explicitly does not protect against.

A document that names its limits builds more trust than one that doesn't.

---

## Assets

| Asset | Sensitivity | Notes |
|---|---|---|
| GPS coordinates | High | One-time snapshot; always requires Person B confirmation |
| Camera photos | High | Always requires Person B confirmation; sent over Telegram |
| Device sensor data | Medium | Battery, screen state, motion, ambient light — passive, no HitL |
| Audio control | Medium | Can override silent mode; disruptive if abused |
| Notification display | Medium | Can show fullscreen alerts on Person B's device |
| Paired user list | Medium | Who has access; stored in `tokens.json` on relay |
| Bot token | High | Controls the entire Telegram bot; anyone who has it can impersonate the bot |
| Android private key | Critical | In Android Keystore; decrypts all incoming FCM commands |
| FCM device token | Medium | Needed to push to the device; useless without the private key |
| Groq / Anthropic API key | Medium | Billed access to AI inference; does not expose device data |

---

## Trust Model

```
Person A (Telegram)
    │  plaintext natural language query
    ▼
Relay Server  ──────────────────────────────────────────────────────┐
    │  GROQ_API_KEY / ANTHROPIC_API_KEY                              │
    │  query text sent to AI provider                                │
    ▼                                                                │
AI Provider (Groq / Anthropic)                                       │
    │  structured tool call returned                                  │
    ▼                                                                │
Relay Server                                                         │
    │  FCM payload: RSA-OAEP + AES-256-GCM encrypted                 │
    │  relay never holds the private key                             │
    ▼                                                                │
Firebase FCM ────────────────────────────────────────────────────────┘
    │
    ▼
Android Device (private key in Keystore TEE)
    │  decrypts, checks PermissionManifest, runs tool
    │  for location/camera: shows HitL dialog (60s → auto-deny)
    │
    │  result POSTed back to relay over HTTPS
    ▼
Relay Server  ──sends natural language answer──►  Person A (Telegram)
```

**Key trust boundary:** The relay server is semi-trusted infrastructure. It routes encrypted commands but cannot read them. It does receive plaintext tool results via `/callback` (see [What the Relay Server Sees](#what-the-relay-server-sees)).

The AI provider (Groq or Anthropic) is a third-party service. It receives the natural language query but never receives device data or tool execution results.

---

## Threat Table

| # | Threat | Attacker | Vector | Mitigation | Residual Risk |
|---|---|---|---|---|---|
| T1 | Unauthorized command execution | Anyone who discovers the bot username | Sends a Telegram message to the bot | Static whitelist (`AUTHORIZED_USER_IDS`) + TokenStore TTL tokens (Protocols 4, 6) | Bot usernames are not secret by design in Telegram; the whitelist is the only gate |
| T2 | Command flood / abuse by authorized sender | Authorized user whose account is compromised | Rapid message sending | Rate limiter: 10 commands in 60 seconds → auto-freeze; FCM alert sent to device owner (Protocol 8) | 10 commands execute before freeze triggers; alert requires FCM to be reachable |
| T3 | Interception of FCM command payload | Network attacker, Firebase employee, relay compromise | Sniff or read FCM message | RSA-OAEP + AES-256-GCM encryption; private key lives in Android Keystore TEE; relay never holds it (Protocol 2) | On devices without hardware-backed TEE (older than Android 7 / no StrongBox), key may be software-backed |
| T4 | Unauthorized location or camera access | Any authorized sender | Send `location_fetcher` or `camera_capture` command | Human-in-the-Loop: every request shows a confirmation dialog on Person B's screen; 60-second timeout auto-denies (Protocol 1) | Person B can be socially pressured into tapping Allow; no technical mitigation for this |
| T5 | Compromise of the relay server | Attacker with full shell access | Read process memory, disk, logs | E2E encryption means relay sees only encrypted FCM payload; private key is never on the relay; tool results arrive via HTTPS callback (see T6) | Relay does see: sender identity, query text, tool name, and callback result (plaintext) |
| T6 | Interception of tool execution result (callback) | Network attacker, relay compromise | Intercept or read `/callback` POST | HTTPS transport; ngrok or VPS TLS terminates the connection | The relay sees plaintext tool results (e.g., GPS coordinates, device context). This is an acknowledged limitation — see [What the Relay Server Sees](#what-the-relay-server-sees) |
| T7 | Compromise of Person A's Telegram account | Account takeover, SIM swap | Attacker sends commands as Person A | SMS Kill Switch: one SMS from any phone disables all tools and stops the service (Protocol 7) | Kill switch requires SMS reachability and that Person B knows their kill phrase; does not help if Person B's number is also compromised |
| T8 | Pairing code interception | Eavesdropper during pairing | Intercept the 6-character PIN | PIN is shared out-of-band (WhatsApp, in person); 10-minute TTL; single-use — consumed on first successful pair (Protocol 6) | An attacker who intercepts the PIN within 10 minutes and uses it before the legitimate recipient can pair successfully |
| T9 | Bot token leak | Credential theft | Token in env var, shell history, log file | Token is never logged; never appears in relay logs | If leaked, attacker can read messages sent to the bot and reply as the bot — whitelist still blocks command execution, but message content (natural language) is exposed |
| T10 | Stale access after relationship changes | Former authorized user | Paired token not explicitly revoked | 30-day TTL auto-expires all paired access; re-pairing required (Protocol 4) | Default 30-day window; reduce via `TOKEN_TTL_DAYS` for higher-sensitivity deployments |
| T11 | Access freeze evasion | Frozen sender | Create a new Telegram account | Freeze is per Telegram user ID; new account bypasses it | Secondary defense: whitelist mode (`AUTHORIZED_USER_IDS`) prevents any new ID from sending commands |
| T12 | AI provider query exposure | Groq / Anthropic as a data processor | Natural language query sent to API | Groq / Anthropic receives only the query text, not device data or results; data processing agreements apply | Query text may reveal intent (e.g., "is he sleeping?"). Use a self-hosted LLM (`LLM_PROVIDER=custom`) for zero third-party AI exposure — not yet implemented |
| T13 | Replay attack on FCM | Network attacker | Resend a captured FCM message | Each FCM command carries a `pendingId` matched to an active `PendingCallbackStore` entry; device processes a given ID once | Window between FCM delivery and callback expiry (~70 seconds); replay within that window could re-execute a command |

---

## What the Relay Server Sees

This is an honest accounting of what a self-hosted relay server operator — or an attacker who compromises the relay — can observe.

**The relay server sees:**
- The Telegram sender's user ID and display name
- The natural language query text (e.g., *"Is he sleeping?"*)
- The tool name selected by the AI (e.g., `get_device_context`)
- The structured tool parameters in JSON (e.g., `{"stream": "RING", "level": 100}`)
- The raw tool result from the Android device (e.g., GPS coordinates, device context JSON) — posted back via `/callback` over HTTPS

**The relay server does NOT see:**
- The plaintext FCM payload (encrypted with the device's RSA public key)
- The Android Keystore private key (never leaves the device)
- The camera photo in transit to Telegram (sent directly via Telegram Bot API from the relay, but the relay receives the image bytes via `/callback` before forwarding)

**Implication:** The relay must be hosted by someone you trust. The design is self-hosted precisely so this trust decision stays with the device owner, not a third party.

---

## Out of Scope

These threats are acknowledged but not mitigated by Telegram Claw:

| Threat | Why out of scope |
|---|---|
| Rooted Android device | Root access can bypass Android Keystore protections and extract the private key. TEE (hardware-backed Keystore) raises the bar significantly but is not a complete defense against a determined local attacker. |
| Physical access to the Android device | An attacker with the unlocked device can disable Claw, read the audit log, or factory-reset. Physical security is the device owner's responsibility. |
| Compromise of Telegram's infrastructure | Telegram itself is a trusted third party. A Telegram-level compromise is out of scope for a self-hosted system. |
| Compromise of Firebase / FCM infrastructure | FCM is a Google service. A Google-level compromise of FCM is out of scope; E2E encryption ensures even FCM can't read payloads. |
| Social engineering of Person B | If Person B is tricked into approving a HitL dialog, Telegram Claw cannot prevent the action — it only ensures Person B had the chance to deny. |
| Telegram metadata analysis | Telegram metadata (who messages which bot, when) is visible to Telegram. This is a privacy concern, not a security vulnerability in Telegram Claw itself. |

---

## Hardening Notes

**For higher-sensitivity deployments:**

- Set `TOKEN_TTL_DAYS=7` or `TOKEN_TTL_DAYS=1` to shorten paired access windows
- Set `AUTHORIZED_USER_IDS` to a static whitelist; disable pairing entirely for single-person setups
- Host the relay on a VPS you control, not a shared hosting provider
- Rotate the bot token periodically via BotFather (`/revoke`)
- Use a custom kill phrase that is not guessable from context
- On Android, verify that the device has hardware-backed Keystore: Settings → Security → Encryption & credentials → Credential storage type should show **Hardware**

**For zero AI-provider exposure (future):**
- A `LLM_PROVIDER=custom` option pointing to a local Ollama or llama.cpp instance is the planned path to removing Groq/Anthropic entirely from the data flow.
