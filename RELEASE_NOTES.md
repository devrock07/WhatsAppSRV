# WhatsAppSRV v1.0.1

WhatsAppSRV v1.0.1 is a branding and presentation release for the public Minecraft-to-WhatsApp bridge.

## New in this release

- A colored, fixed-width `WhatsAppSRV` ASCII banner that stays aligned in Paper and Pterodactyl consoles.
- A boxed **Made by DevRock** credit and visible plugin version at startup.
- `/wasrv credits` and `/wasrv about` for showing the banner again without restarting.
- `/about` and `/credits` inside the selected WhatsApp chat.
- `console-banner.enabled: false` for server owners who prefer quiet startup logs.

All bridge, session, chat relay, player-head, status-card, whitelist, and security behavior from v1.0.0 remains intact.

## Install

1. Upload `WhatsAppSRV.jar` to `plugins/` and start the server.
2. Wait for the first-start runtime/browser setup.
3. Scan the QR from the console or `plugins/WhatsAppSRV/bridge/qr.png`.
4. Wait for `WhatsApp client is ready`.
5. Run `/wasrv chats`, `/wasrv select <number>`, and `/wasrv test`.

Keep at least 750 MB of persistent storage free (1 GB recommended) and ensure the container allows outbound HTTPS and child processes.

Upgrading from v1.0.0 does not force an npm or Chromium reinstall. The saved WhatsApp linked-device session is preserved.

## Important security note

`whatsapp-web.js` is unofficial and may lead to logout or account restriction. Use a separate account, avoid spam, and keep `plugins/WhatsAppSRV/session`, `config.yml`, `bridge/config.json`, QR images, and sender IDs private.

See [README.md](README.md) for setup and troubleshooting, [SECURITY.md](SECURITY.md) for hardening, and [CHANGELOG.md](CHANGELOG.md) for the complete release record.
