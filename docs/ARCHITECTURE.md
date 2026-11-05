# NEX architecture

## Product boundaries

- Product name: NEX.
- Target: Android phones and tablets only.
- Emulator source base: official Eden source tree.
- Network service: Nextendo Network, using the application registration owned/managed for NEX.
- No Den2 fork and no desktop/iOS/headset targets.

## Planned layers

| Layer | Responsibility |
|---|---|
| Eden Android frontend | Existing Android UI, game library, settings, input and emulation lifecycle |
| NEX identity | NEX application ID, package name, app version and release metadata |
| Nextendo Android | OAuth PKCE, encrypted session persistence, account profile and friends API |
| Nextendo game auth | Fetch nx2 game token and provide it to the emulated BAAS/NEX authentication path |
| Network compatibility | Nextendo DNS redirection, TLS/SNI compatibility, NEX services and UDP/NAT/P2P behavior |
| Validation | Unit tests, API contract tests, supported-title tests and physical-device tests |

## Android identity

Initial Android application ID: com.xdavi196.nex.

OAuth callback URI: com.xdavi196.nex://oauth/callback.

The callback URI must be registered exactly in the NEX application settings in the Nextendo developer portal. If the package/application ID changes, update the registered redirect URI and Android manifest together.

## Source integration strategy

1. Import the Eden source at a pinned tag/commit and preserve its upstream history where possible.
2. Keep NEX-specific code in clearly named modules and small compatibility patches.
3. Avoid importing another emulator's release credentials, signing keys, app-attestation material or Client ID.
4. Pin every upstream dependency and record the source revision in the build.
5. Do not publish a build as multiplayer-ready until a real supported game session has been tested.
