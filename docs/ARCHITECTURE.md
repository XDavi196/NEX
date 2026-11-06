# NEX architecture

## Product boundaries

- Product: NEX.
- Target: Android phones and tablets only.
- Emulator source base: Eden tag `v0.2.0-rc1`.
- Pinned Eden commit: `8e373eb714aa01117ec885680477501cdb572112`.
- Android application ID: `com.nex.nx`.
- OAuth callback URI: `com.nex.nx://oauth/callback`.
- Network service: Nextendo Network, using the public application registration managed for NEX.
- NEX is not based on Den2 and does not target desktop, iOS, or headset operating systems.

## Main layers

| Layer | Responsibility |
|---|---|
| Eden Android frontend | Game library, settings, input, graphics, and emulation lifecycle |
| NEX identity | NEX package/application ID, app label, version, and release metadata |
| Nextendo Android | OAuth PKCE, encrypted session storage, callback handling, account profile, and friends API |
| Nextendo presence | Publish and display account/app/game activity where supported |
| Nextendo game authentication | Obtain the separate `nx2.` token and pass it to the emulated BAAS authentication path |
| Game networking | Nextendo DNS routing, TLS/SNI, NEX/PRUDP, matchmaking, NAT, and P2P |
| Validation | Unit tests, API contract tests, supported-title tests, and physical-device testing |

## Pinned Eden source

- Upstream: https://git.eden-emu.dev/eden-emu/eden
- Tag: `v0.2.0-rc1`
- Commit: `8e373eb714aa01117ec885680477501cdb572112`
- Android build variant: `Eden-Android-v0.2.0-rc1-optimized.apk`

The Optimized suffix identifies an Android build flavor, not a separate source branch. The build workflow checks out the pinned Eden source and applies the NEX overlay. See [the source baseline](EDEN_BASE.md) in the integration branch.

## Android identity

- Application ID: `com.nex.nx`
- OAuth callback: `com.nex.nx://oauth/callback`
- Callback scheme: `com.nex.nx`
- Callback host: `oauth`
- Callback path: `/callback`

The registered redirect URI must match the Android intent filter and token-exchange request exactly. If the Developer Portal registration changes, update the app configuration and portal together.

## Nextendo client identity

The current NEX public Client ID is `nxc_yAdR-14bOEq7`. API requests identify the application through `X-Nextendo-Client-Id`. NEX is a public OAuth client using PKCE; no client secret belongs in the APK.

## Integration strategy

1. Pin the exact Eden tag and commit used by every build.
2. Keep NEX-specific Android code and overlay changes separate and reviewable.
3. Preserve upstream copyright notices, licenses, and third-party attributions.
4. Use only the NEX-owned Nextendo application identity and credentials issued for NEX.
5. Never copy another emulator developer's client credentials, private keys, or attestation material.
6. Do not advertise multiplayer support until a supported game session has passed end-to-end testing.
