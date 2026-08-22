## What changed

Describe the change and why it is needed.

## Security and compatibility

- Authorization/privacy impact:
- x64/ARM64 or upgrade impact:

## Verification

- [ ] `mvn -B clean verify`
- [ ] `node --check bridge/index.js`
- [ ] Relevant clean-session and saved-session paths tested
- [ ] Documentation/changelog updated when user-visible
- [ ] No tokens, sessions, QR images, sender IDs, runtime files, or `node_modules` included
