# Android Nextendo integration scaffold

This folder contains NEX-owned Android integration components:

- `NextendoAuthClient.kt` — OAuth Authorization Code + PKCE, refresh-token exchange and separate `nx2.` game-token request.
- `NextendoSessionStore.kt` — encrypted token persistence using Android Keystore and AES-GCM, with no plaintext fallback.

## NEX configuration

- Package / application ID: `com.nex.nx`
- Client ID: `nxc_yAdR-14bOEq7`
- Redirect URI: `com.nex.nx://oauth/callback`
- Scopes: `identity friends presence profil game.matchmaking`

## Integration status

These files are a source scaffold. They have not yet been compiled against Eden Android because the pinned Eden source tree has not yet been imported into this repository.

Before wiring them into the emulator:

1. Import Eden tag `v0.2.0-rc1` and select the Android Optimized build configuration.
2. Add the matching Android intent filter to the callback activity.
3. Add Chrome Custom Tabs/browser launch and a lifecycle-safe pending OAuth transaction store.
4. Call synchronous network methods only from a background dispatcher, or convert them to suspend/non-blocking APIs.
5. Persist OAuth token rotation immediately, then fetch and persist the separate game token.
6. Integrate the game token into the NEX ACC/BAAS path using only NEX-owned application credentials and attestation material.
7. Compile and test on Android before calling any feature complete.

Never print access tokens, refresh tokens, game tokens, callback codes, PKCE verifiers or attestation material to Android logs.
