# Contributing to WhatsAppSRV

Thanks for helping make the Minecraft ↔ WhatsApp bridge more reliable and safer.

## Before opening a change

- Use GitHub Issues for reproducible bugs and focused feature proposals.
- Search existing issues and pull requests first.
- Report vulnerabilities privately according to [SECURITY.md](SECURITY.md).
- Keep changes compatible with the project's one-JAR deployment model.

## Development setup

Recommended tools:

- JDK 17
- Maven 3.9+
- Node.js 24 for bridge syntax checks
- A disposable Paper test server
- A separate WhatsApp test account

Clone the repository, then verify the current source:

```bash
mvn -B clean verify
node --check bridge/index.js
```

The shaded plugin is generated at `target/WhatsAppSRV.jar`. Maven compiles Java 8-compatible bytecode even when the build runs on JDK 17.

Do not run `npm install` in the repository unless you are intentionally changing the bridge dependencies. When dependencies change, update and review `bridge/package-lock.json`; never commit `bridge/node_modules`.

## Repository layout

| Path | Purpose |
| --- | --- |
| `src/main/java/dev/codex/whatsappsrv` | Bukkit plugin, local bridge client, command handling, and image rendering. |
| `src/main/resources` | Default plugin configuration and Bukkit metadata. |
| `bridge/index.js` | Local authenticated HTTP bridge and WhatsApp client lifecycle. |
| `bridge/package.json` / `package-lock.json` | Pinned embedded bridge dependencies. |
| `.github/workflows/build.yml` | Reproducible Maven and JavaScript validation. |

## Design and security rules

- Never expose the local bridge on a public interface.
- Never log API tokens, session contents, or reusable login material.
- Never commit `session`, `config.json`, QR images, runtime/browser directories, or `node_modules`.
- Keep Bukkit API access on the server thread. Network requests and image rendering belong off-thread.
- Validate and bound every string, collection, HTTP body, image, and queue that crosses the Java/Node boundary.
- Add remote administration through a purpose-built validated operation. Do not turn a user-controlled string into an unrestricted console command.
- Preserve exact full-line allowlist matching for optional console commands.
- Maintain compatibility with Linux x64 and ARM64 Pterodactyl hosts.
- Prefer Java 8 language/API compatibility in plugin source unless the public compatibility policy changes.

## Testing changes

At minimum, run:

```bash
mvn -B clean verify
node --check bridge/index.js
```

For bridge, authentication, browser, or runtime changes, also test a clean plugin directory and an existing saved session. For UI/message changes, verify text and image fallback paths. For platform changes, document which x64/ARM64 host images were tested.

Useful manual checks include:

- First-start download and checksum verification
- QR creation, authentication, readiness, and restart persistence
- `/wasrv chats`, selection, status, test, and reload
- WhatsApp direct replies and ⏳/✅/❌ reactions
- Read-only commands as a normal member
- Whitelist commands as a group admin and rejection as a normal member
- Exact console allowlist rejection for sender, command, and extra-argument mismatches
- Join/leave and status images with SkinsRestorer, Bukkit skin data, and offline fallback
- Unicode emoji and media placeholders in Minecraft chat
- Plugin shutdown without an orphan Node/Chromium process

## Pull requests

Keep pull requests focused and explain:

- What changed and why
- User-visible or configuration changes
- Security impact
- Tests performed, including architecture where relevant
- Upgrade or rollback considerations

Update `README.md` and `CHANGELOG.md` for user-visible changes. Do not include generated binaries in a pull request; release artifacts are built from tagged source.

By contributing, you agree that your contribution is licensed under the project's [MIT License](LICENSE).
