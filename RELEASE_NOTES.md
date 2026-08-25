# WhatsAppSRV v1.1.0

WhatsAppSRV v1.1.0 turns advancements and player deaths into rich, Minecraft-style WhatsApp event cards.

## New in this release

- Advancement cards use the player's selected SkinRestorer skin when available, then Bukkit profile data, with Steve/Alex as the final fallback.
- Familiar vanilla advancement names such as **Bring Home the Beacon** replace raw internal keys.
- Death cards show the player avatar, death message, world, biome, dimension, and exact X/Y/Z coordinates.
- The death-location panel is sampled from the real blocks around the death point and marks the exact center.
- New `event-cards` configuration toggles control both card types and the death-map radius.
- Image rendering remains asynchronous and automatically falls back to text if media delivery fails.

## Install or upgrade

1. Back up `plugins/WhatsAppSRV/config.yml` and `plugins/WhatsAppSRV/session`.
2. Stop the Minecraft server.
3. Replace the existing plugin with `WhatsAppSRV-1.1.0.jar`.
4. Start the server and run `/wasrv status` followed by `/wasrv test`.

Existing linked-device sessions and selected WhatsApp targets are preserved. Missing v1.1.0 configuration keys use safe defaults automatically, so existing installations do not need to recreate `config.yml`.

## Death-location rendering

Minecraft's server API cannot capture a player's camera view after death without a separate world renderer. WhatsAppSRV therefore renders a deterministic top-down map from the actual nearby block types. This works without Dynmap, BlueMap, a resource pack, or an exposed web port.

## Important security note

`whatsapp-web.js` is unofficial and may lead to logout or account restriction. Use a separate account, avoid spam, and keep `plugins/WhatsAppSRV/session`, `config.yml`, `bridge/config.json`, QR images, and sender IDs private.

See [README.md](README.md) for complete setup and configuration, [SECURITY.md](SECURITY.md) for hardening, and [CHANGELOG.md](CHANGELOG.md) for the full release history.
