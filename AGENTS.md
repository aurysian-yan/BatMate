# Project Rules

- Keep Android on React Native and Expo, the wearable on Xiaomi Vela, and macOS on native SwiftUI.
- Use pnpm and pinned dependencies. Do not add frameworks without an explicit need.
- Keep user-facing text in the shared `locales/` catalog, with Chinese and English support.
- Use concise Chinese code comments. Keep internal diagnostics out of product UI.
- Use native component defaults and system appearance. Avoid unsolicited visual decoration.
- Preserve the installed Android and wearable package identity and matching signing certificate.
- Never commit pairing credentials, private keys, certificates, device identifiers, or generated binaries.
- Write commits in Chinese as `<type>: <动词开头的描述>`.
- Validate changed runtime logic; stop development and test servers before finishing.
