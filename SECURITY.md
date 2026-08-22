# Security policy

## Supported versions

Security fixes are provided for the latest public release line.

| Version | Supported |
| --- | --- |
| 1.0.x | Yes |
| Development snapshots and older private builds | No |

## Reporting a vulnerability

Do not open a public issue for a vulnerability, leaked credential, or working exploit.

Use the repository's **Security → Report a vulnerability** option to create a private GitHub Security Advisory. If private reporting is not enabled, contact the repository owner privately through their GitHub profile and disclose only enough information to establish a secure reporting channel.

Include:

- A clear description and expected impact
- Affected WhatsAppSRV and server versions
- Host architecture and relevant Pterodactyl/container details
- Minimal reproduction steps or a proof of concept
- Whether authentication/session data may have been exposed
- Suggested remediation, if known

Please allow a reasonable period for validation and a coordinated release before publishing details. Maintainers will acknowledge a complete report, assess severity, and communicate a remediation plan when the issue is reproducible.

## Secrets and sensitive files

Treat these as credentials:

- `plugins/WhatsAppSRV/session/`
- `plugins/WhatsAppSRV/config.yml`
- `plugins/WhatsAppSRV/bridge/config.json`
- `plugins/WhatsAppSRV/bridge/qr.png`
- Console logs containing a live QR or private sender IDs

If any of them are exposed:

1. Remove the leaked material from public access. Deleting only the latest Git commit is not enough if it remains in history.
2. Unlink the affected device from WhatsApp immediately.
3. Stop the server, remove the compromised session, and pair a new session.
4. Replace the plugin's `api-token` with a new long random value or allow WhatsAppSRV to generate one in a fresh config.
5. Review WhatsApp linked devices, server logs, installed plugins, and Pterodactyl users for unexpected access.

## Deployment hardening

- Keep the bridge bound to `127.0.0.1`; never expose port 3210 publicly.
- Keep `whatsapp-commands.console.enabled` set to `false` unless there is a specific operational need.
- If console commands are enabled, require explicit sender IDs and exact, low-impact command lines.
- Grant Pterodactyl panel and filesystem access only to trusted administrators.
- Keep the plugin, Minecraft server, Java runtime, and host image updated.
- Use a separate WhatsApp account, avoid bulk messaging, and respect WhatsApp's terms and rate limits.
- Back up the session only to encrypted, access-controlled storage.
- Do not run the same session concurrently on multiple Minecraft servers.

## Security boundaries

WhatsAppSRV authenticates its local bridge, validates outbound media, bounds message queues and payloads, and restricts dynamic administrative operations. It cannot protect against a compromised host, malicious server operator, hostile plugin with filesystem/process access, a WhatsApp account takeover, or a provider that exposes the container's files.

The use of `whatsapp-web.js` is inherently unofficial and can result in logout or account restriction. That platform risk is not considered a vulnerability in WhatsAppSRV, but unexpected credential disclosure, authorization bypass, command injection, or remote-code-execution behavior is.
