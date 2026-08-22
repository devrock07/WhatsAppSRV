# Changelog

All notable changes to WhatsAppSRV are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-22

First supported public release.

### Added

- One-JAR Paper/Spigot plugin with an embedded localhost WhatsApp bridge.
- Automatic, SHA-256-verified Node.js runtime installation for Linux x64 and ARM64 Pterodactyl hosts.
- Managed Chromium support, including an ARM64-specific browser and font/runtime setup.
- Console and PNG QR login with persistent linked-device authentication.
- Readable WhatsApp group/DM discovery and numbered target selection.
- Two-way Minecraft/WhatsApp chat with Unicode emoji preservation.
- Join, leave, death, advancement, startup, and shutdown notifications.
- Join/leave player-head images using SkinsRestorer, Bukkit profile, and Steve/Alex fallbacks.
- Safe read-only `/players`, `/status`, `/tps`, `/version`, `/ping`, and `/help` WhatsApp commands.
- Direct command replies with pending, success, and failure reactions.
- Graphical `/status` card with server health, resource usage, and online player heads.
- Group-admin-only `/whitelist add`, `/whitelist remove`, and `/whitelist list` commands.
- Optional exact sender and exact full-line console-command allowlists, disabled by default.
- Bounded inbound message queue, input/output limits, localhost bearer-token authentication, and secure generated-data defaults.
- Optional SkinsRestorer integration without a hard plugin dependency.

### Security

- The local HTTP bridge binds to `127.0.0.1` and authenticates every request.
- Runtime authentication, QR, browser, dependency, and token files are excluded from version control.
- Dynamic whitelist input is restricted to valid Minecraft usernames and a fixed add/remove/list operation set.
- The release lock uses Puppeteer 25.8.0 and Sparticuz Chromium 149.0.0, and omits unused RemoteAuth-only optional packages at runtime.
