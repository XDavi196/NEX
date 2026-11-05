# Android Nextendo integration

This folder contains the NEX-owned Android integration source:

- `NextendoAuthClient.kt` — OAuth Authorization Code + PKCE, token refresh and the separate `nx2.` game-token request.
- `NextendoSessionStore.kt` — AES-GCM encrypted OAuth/game session and pending PKCE transaction storage using Android Keystore.
- `NextendoAuthManager.kt` — Chrome Custom Tab launch, callback verification, background token exchange, game-token acquisition and account panel.
- `NextendoCallbackActivity.kt` — Android deep-link callback handler.
- `NextendoAccountApi.kt` — authenticated read-only player profile lookup.

## NEX configuration

- Application ID: `com.nex.nx`
- Public Client ID: `nxc_yAdR-14bOEq7`
- Redirect URI: `com.nex.nx://oauth/callback`
- Scopes: `identity friends presence profil game.matchmaking`

## Status and integration

The sources are not compiled as a standalone Android module. `scripts/apply-eden-overlay.py` copies them into the pinned Eden Android project, changes the Optimized flavor package from Eden's original spoofed ID to `com.nex.nx`, adds AndroidX Browser, registers the callback and inserts a Nextendo account entry in the root settings.

GitHub Actions then builds the Optimized release flavor from Eden tag `v0.2.0-rc1`.

The first integration does not yet implement the friends endpoint, presence payload/heartbeat, profile editing, or in-game ACC/BAAS token injection. Those are separate tasks requiring the exact service API contract and emulator network-path changes. A successful OAuth login or retrieval of an `nx2.` token does not by itself prove multiplayer works.

Never print access tokens, refresh tokens, game tokens, callback codes, PKCE verifiers or attestation material to Android logs.
