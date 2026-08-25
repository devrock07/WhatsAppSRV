# Changelog

All notable changes to WhatsAppSRV are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-08-25

### Added

- Graphical 960x540 advancement cards with the player's selected SkinRestorer/Bukkit skin, a polished Minecraft-style layout, familiar vanilla advancement titles, and progression category.
- Graphical death-location cards with the player's skin, death message, world, biome, dimension, and exact X/Y/Z coordinates.
- A top-down location panel sampled from the real blocks around the death point, with a configurable 3-8 block radius and a marked center point.
- `event-cards.enabled`, `event-cards.advancements`, `event-cards.deaths`, and `event-cards.death-map-radius` configuration options.
- Renderer coverage for image dimensions, PNG validity, and the bridge's 2 MiB media limit.

### Changed

- Advancement messages now show readable in-game-style titles instead of raw keys such as `nether create_beacon`.
- Advancement and death rendering runs asynchronously after Bukkit-only state is safely captured on the server thread.
- Image failures fall back to the configured text message, with death coordinates preserved.

## [1.0.1] - 2026-08-22

### Added

- Colored, fixed-width ASCII startup banner designed for Minecraft and Pterodactyl consoles.
- Boxed `Made by DevRock` credit and version display.
- `/wasrv credits` and `/wasrv about` Minecraft admin subcommands.
- `/about` and `/credits` read-only WhatsApp commands.
- `console-banner.enabled` configuration toggle for quiet startup logs.

### Changed

- Updated public release metadata and documentation for v1.0.1.
- Kept the embedded bridge dependency marker unchanged, so upgrading from v1.0.0 does not force an unnecessary npm/Chromium reinstall.

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
