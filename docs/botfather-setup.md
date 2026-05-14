# BotFather Setup Guide

This takes about 3 minutes. You will end up with a **bot token** — a long string that looks like:

```
1234567890:ABCdefGhijKlmnoPqrsTuvwxyz
```

That token is the password to your bot. Keep it private.

---

## Step 1 — Open BotFather

Open Telegram and search for **@BotFather** — it is the official Telegram bot for creating other bots.

The verified account has a blue checkmark next to its name.

Tap **Start** (or send `/start`) if you have not used BotFather before.

---

## Step 2 — Create a New Bot

Send this command to BotFather:

```
/newbot
```

BotFather will ask two questions:

**1. Name** — the display name people see when they open the bot.
Example: `My Claw`

**2. Username** — must end in `bot`, no spaces, no special characters.
Example: `my_claw_bot` or `MyClawBot`

If the username is already taken, try a different one.

---

## Step 3 — Copy Your Token

After you pick a username, BotFather replies with something like:

> Done! Congratulations on your new bot. You will find it at t.me/my_claw_bot.
> You can now add a description, about section and profile picture for your bot, see /help for a list of commands. By the way, when you've finished creating your cool bot, ping our Bot Support if you want a better username for it. Just make sure the bot is fully operational before you do this.
>
> Use this token to access the HTTP API:
> **1234567890:ABCdefGhijKlmnoPqrsTuvwxyz**
>
> Keep your token secure and store it safely, it can be used by anyone to control your bot.

Copy the token — the long string after "Use this token to access the HTTP API:".

That is your `TELEGRAM_BOT_TOKEN`. Go back to the [Setup Guide](setup-guide.md) and continue from Step 2.

---

## Optional: Find Your Telegram User ID

The relay server needs your **Telegram user ID** (a number, not your username) so it knows who is allowed to send commands.

1. Open Telegram and search for **@userinfobot**
2. Tap **Start** (or send `/start`)
3. It immediately replies with your user ID — a number like `8608523419`

Copy that number — you will need it as `AUTHORIZED_USER_IDS` in Step 6 of the setup guide.

---

## Troubleshooting

**"That username is already taken"**
Try a different username. Add a number or your name: `elena_claw_bot`, `claw2025bot`.

**"I can't find @BotFather"**
Make sure the account has a blue checkmark. There are fake BotFather accounts — only the verified one can create bots.

**"I lost my token"**
Send `/mybots` to @BotFather → tap your bot → **API Token**. You can also revoke and regenerate a token there if it was exposed.

---

← Back to [Setup Guide](setup-guide.md)
