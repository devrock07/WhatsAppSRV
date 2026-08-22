# WhatsAppSRV v1.0.0

The first supported public release of WhatsAppSRV turns a Paper/Spigot server and one selected WhatsApp group or DM into a polished two-way community bridge.

## What is included

- One JAR: the plugin installs and manages its private Node.js/WhatsApp bridge.
- Linux Pterodactyl support on x64 and ARM64, including the ARM64 Chromium runtime path.
- Persistent QR login, readable group/DM selection, and clear readiness diagnostics.
- Minecraft chat, joins, leaves, deaths, advancements, and lifecycle notifications.
- Real player-head images through SkinsRestorer or Bukkit, with safe Steve/Alex fallback.
- Emoji-preserving inbound chat and useful labels for stickers and other WhatsApp media.
- Direct WhatsApp command replies with ⏳ while processing and ✅/❌ on completion.
- A graphical `/status` card with server health, memory, uptime, version, icon, TPS, and online player heads.
- Safe read-only status commands and dedicated group-admin whitelist add/remove/list commands.
- Optional arbitrary console commands remain disabled unless the sender passes the admin gate, is explicitly allowlisted for console access, and the exact complete command is allowlisted.

## Install

1. Upload `WhatsAppSRV.jar` to `plugins/` and start the server.
2. Wait for the first-start runtime/browser setup.
3. Scan the QR from the console or `plugins/WhatsAppSRV/bridge/qr.png`.
4. Wait for `WhatsApp client is ready`.
5. Run `/wasrv chats`, `/wasrv select <number>`, and `/wasrv test`.

Keep at least 750 MB of persistent storage free (1 GB recommended) and ensure the container allows outbound HTTPS and child processes.

Upgrading from a private preview build triggers one fresh npm dependency install. Keep the server running until the bridge reports that it has started; the saved WhatsApp session is preserved.

## Important security note

`whatsapp-web.js` is unofficial and may lead to logout or account restriction. Use a separate account, avoid spam, and keep `plugins/WhatsAppSRV/session`, `config.yml`, `bridge/config.json`, QR images, and sender IDs private.

See [README.md](README.md) for setup and troubleshooting, [SECURITY.md](SECURITY.md) for hardening, and [CHANGELOG.md](CHANGELOG.md) for the complete release record.
