# NEX architecture

## Product boundaries

- Product name: NEX.
- Target: Android phones and tablets only.
- Emulator source base: Eden tag `v0.2.0-rc1`.
- Requested Android build variant: `Eden-Android-v0.2.0-rc1-optimized.apk`.
- Android application ID: `com.nex.nx`.
- Network service: Nextendo Network, using the NEX-owned application registration.
- No Den2 fork and no desktop/iOS/headset targets.

## Planned layers

| Layer | Responsibility |
|---|---|
| Eden Android frontend | Existing Android UI, game library, settings, input and emulation lifecycle |
| NEX identity | Application ID, package name, app version and release metadata |
| Nextendo Android | OAuth PKCE, encrypted session persistence, account profile and friends API |
| Nextendo presence | Publish and display online/in-game activity with the approved presence scope |
| Nextendo game auth | Fetch the separate nx2 game token and provide it to the emulated BAAS/NEX authentication path |
| Network compatibility | Nextendo DNS redirection, TLS/SNI compatibility, game NEX/PRUDP, UDP/NAT/P2P behavior |
| Validation | Unit tests, API contract tests, supported-title tests and physical-device tests |

## Pinned Eden source

- Upstream: https://git.eden-emu.dev/eden-emu/eden
- Tag: `v0.2.0-rc1`
- Commit: `8e373eb714aa01117ec885680477501cdb572112`
- Requested Android artifact variant: `Eden-Android-v0.2.0-rc1-optimized.apk`

The APK variant is a build flavor of that Eden release; it is not a separate source branch. The source must be imported from the pinned release tag, then built with the Android Optimized configuration. See [EDEN_BASE.md](EDEN_BASE.md).

## Android identity

- Application ID: `com.nex.nx`.
- OAuth callback URI: `com.nex.nx://oauth/callback`.
- Callback scheme: `com.nex.nx`.
- Callback host: `oauth`.
- Callback path: `/callback`.

The callback URI must be registered exactly in the NEX application settings in the Nextendo developer portal and must match the Android intent filter and token exchange. If the registered URI changes, update the portal, code and manifest together.

## Source integration strategy

1. Import the Eden source at the pinned tag/commit and retain all upstream license notices, third-party licenses and required source disclosures.
2. Keep NEX-specific code in clearly named modules and small compatibility patches.
3. Use only the NEX-owned Nextendo application identity and credentials issued for NEX.
4. Do not import another emulator developer's private keys, attestation material or client credentials.
5. Pin dependencies and record the source revision in every build.
6. Do not publish a build as multiplayer-ready until a real supported game session has been tested.
