# Third-party notices

WhatsAppSRV is MIT-licensed, but it builds on third-party software whose own licenses continue to apply.

## Libraries bundled in the release JAR

| Component | Version | License |
| --- | --- | --- |
| Gson | 2.11.0 | Apache License 2.0 |
| Error Prone annotations | 2.27.0 | Apache License 2.0 |
| Apache Commons Compress | 1.28.0 | Apache License 2.0 |
| Apache Commons Codec | 1.19.0 | Apache License 2.0 |
| Apache Commons IO | 2.20.0 | Apache License 2.0 |
| Apache Commons Lang | 3.18.0 | Apache License 2.0 |
| XZ for Java | 1.10 | Public domain |

The shaded JAR preserves the applicable dependency license/notice metadata under `META-INF`. Spigot API and its provided dependencies are compile-time-only and are not bundled.

## Bridge dependencies installed at runtime

The embedded lockfile installs `whatsapp-web.js`, Puppeteer, Sparticuz Chromium, QR utilities, and their transitive packages into the private plugin data directory. Exact versions, download integrity hashes, and package metadata are recorded in [`bridge/package-lock.json`](bridge/package-lock.json). License files supplied by those packages remain in `bridge/node_modules` after installation.

Notable direct components include:

- [`whatsapp-web.js`](https://github.com/wwebjs/whatsapp-web.js) — Apache License 2.0
- [Puppeteer](https://github.com/puppeteer/puppeteer) — Apache License 2.0
- [Sparticuz Chromium](https://github.com/Sparticuz/chromium) — MIT License; Chromium itself includes BSD-style and other third-party licenses
- [`qrcode`](https://github.com/soldair/node-qrcode) — MIT License
- [`qrcode-terminal`](https://github.com/gtanner/qrcode-terminal) — MIT License

WhatsApp and Meta are not dependencies, licensors, sponsors, or endorsers of this project.
