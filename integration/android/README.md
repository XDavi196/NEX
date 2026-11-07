# Android Nextendo integration

This folder contains the NEX-owned Android integration source:

- `NextendoAuthClient.kt` — OAuth Authorization Code + PKCE, registered Client ID header, token refresh, userinfo, presence publishing and the separate `nx2.` game-token request.
- `NextendoSessionStore.kt` — AES-GCM encrypted OAuth/game session, token-free API diagnostics and pending PKCE transaction storage using Android Keystore.
- `NextendoAuthManager.kt` — Chrome Custom Tab login, callback verification, account panel, friends UI and background session refresh.
- `NextendoCallbackActivity.kt` — Android deep-link callback handler.
- `NextendoAccountApi.kt` — authenticated read-only player profile lookup.
- `NextendoSocialApi.kt` — OAuth `/api/oauth/userinfo` and read-only friends list.
- `NextendoPresencePublisher.kt` — online presence heartbeat every 60 seconds, offline publication on disconnect, and in-game Title ID publication from the Eden lifecycle hook.
- `NextendoPrudpLiteAuthenticator.kt` — Nextendo published Lite CONNECT HMAC-MD5 proof, using only a runtime-provided title key.
- `NextendoPrudpLiteSession.kt` — tested PRUDP-Lite handshake, reliable DATA/ACK handling, duplicate suppression, fragmentation and connection state machine.
- `NextendoPrudpLiteWebSocketTransport.kt` — OkHttp binary WebSocket transport with normal TLS verification.

## NEX configuration

- Application ID: `com.nex.nx`
- Client ID used in this branch: `nxc_yAdR-14bOEq7`
- Redirect URI: `com.nex.nx://oauth/callback`
- Scopes (owner-confirmed portal configuration): `identity friends presence profil game.matchmaking emulator.status`
- Authorization URL: `https://nextendo.network/oauth/authorize`
- Token URL: `https://nextendo.network/api/oauth/token`
- Application identity header: `X-Nextendo-Client-Id`

The README on `main` currently has a different Client ID (`nxc_6BozwtiXy9n`). Confirm the correct value in the Developer Portal before live sign-in.

## Status and integration

The sources are not compiled as a standalone Android module. `scripts/apply-eden-overlay.py` copies them into the pinned Eden Android project, changes the Optimized flavor package from Eden's original spoofed ID to `com.nex.nx`, adds AndroidX Browser, registers the callback and inserts a Nextendo account entry in the root settings.

GitHub Actions builds the Optimized release flavor from Eden tag `v0.2.0-rc1`.

The Android integration currently has:
- OAuth login/session scaffold and account profile panel.
- Read-only friends list from `/api/oauth/userinfo`.
- App-level online heartbeat, offline publication and real game Title ID status via the repeatable Eden overlay.
- Separate `nx2.` game-token request.

Still missing:
- Friend request mutations and final guest-visible `nn::friends` IPC validation.
- Native game lifecycle callback for real in-game presence/title ID.
- NEX-specific ACC/BAAS `id_token` generation and `nnex` claim injection.
- Verified SMM2 WSS endpoint, runtime access-key source and accepted CONNECT/BAAS payload. The PRUDP-Lite client transport is implemented as a testable module but is not wired to a live title.
- DNS mapping, title TLS/SNI, complete SMM2 RMC methods and matchmaking.
- NAT/P2P socket ownership, keepalive, discovery and hole-punching.
- End-to-end testing with two accounts and a supported game.

A successful OAuth login or retrieval of an `nx2.` token does not by itself prove multiplayer works. See [the online status checklist](../../docs/NEXTENDO_ONLINE_STATUS.md).

Never print access tokens, refresh tokens, game tokens, callback codes or PKCE verifiers to Android logs.


## NEX visual identity and online diagnostics

The fragment-wide glass treatment was removed. The launcher uses an adaptive NEX icon based on the original logo, while the Games header keeps Eden's Material title view and displays the text `NEX`. The app retains Eden's existing Material styling.

The account panel distinguishes OAuth connectivity from the separate `nx2.` game credential. It displays the scopes returned by OAuth, the latest token-free HTTP result for the presence and game-token requests, and whether SMM2 is ready. The game-token result is saved inside the encrypted Android Keystore-backed session. qLaunch friend rows are only available when the public API supplies an explicit NSA; a PID is never substituted.


## BAAS boundary (not enabled)

\`NextendoBaasIdTokenBuilder.kt\` inserts the server-issued \`nx2.\` token into the \`nnex\` claim and delegates RS256 to \`NextendoBaasSigner\`. The actual signer and claim set must be supplied by an NEX-specific Nextendo provisioning contract. No private key, test key, fallback signature or other emulator credential is shipped. The builder is not wired to Eden ACC yet.
