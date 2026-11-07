# Android Nextendo integration

This folder contains the NEX-owned Android integration source:

- `NextendoAuthClient.kt` — OAuth Authorization Code + PKCE, registered Client ID header, token refresh, userinfo, presence publishing and the separate `nx2.` game-token request.
- `NextendoSessionStore.kt` — AES-GCM encrypted OAuth/game session and pending PKCE transaction storage using Android Keystore.
- `NextendoAuthManager.kt` — Chrome Custom Tab login, callback verification, account panel, friends UI and background session refresh.
- `NextendoCallbackActivity.kt` — Android deep-link callback handler.
- `NextendoAccountApi.kt` — authenticated read-only player profile lookup.
- `NextendoSocialApi.kt` — OAuth `/api/oauth/userinfo` and read-only friends list.
- `NextendoPresencePublisher.kt` — online presence heartbeat every 60 seconds, offline publication on disconnect, and in-game Title ID publication from the Eden lifecycle hook.

## NEX configuration

- Application ID: `com.nex.nx`
- Client ID used in this branch: `nxc_yAdR-14bOEq7`
- Redirect URI: `com.nex.nx://oauth/callback`
- Scopes: `identity friends presence profil game.matchmaking mods.favorites`
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
- Friend request mutations and in-game `nn::friends` IPC projection.
- Native game lifecycle callback for real in-game presence/title ID.
- ACC/BAAS `id_token` generation and `nnex` claim injection.
- DNS mapping, game TLS/SNI validation, game NEX/PRUDP and NAT/P2P socket handling.
- End-to-end testing with two accounts and a supported game.

A successful OAuth login or retrieval of an `nx2.` token does not by itself prove multiplayer works. See [the online status checklist](../../docs/NEXTENDO_ONLINE_STATUS.md).

Never print access tokens, refresh tokens, game tokens, callback codes or PKCE verifiers to Android logs.


## NEX glass and online readiness display

\`NexGlassEffect.kt\` styles fragment bars, Material buttons, selected cards and switches with an Android-native translucent treatment. The overlay registers the helper for fragment views so settings and game screens receive the same appearance. The Games screen header and launcher use the official NEX logo.

The account panel distinguishes OAuth account connectivity and the \`nx2.\` game token from actual in-game readiness. It reports SMM2 as disconnected while BAAS signing, title endpoints and a PRUDP session are missing. qLaunch friend rows are only available when the public API supplies an explicit NSA; a PID is never substituted.

The OAuth callback must include the matching state value. A missing or mismatched state is rejected before exchanging the authorization code.


## BAAS boundary (not enabled)

\`NextendoBaasIdTokenBuilder.kt\` inserts the server-issued \`nx2.\` token into the \`nnex\` claim and delegates RS256 to \`NextendoBaasSigner\`. The actual signer and claim set must be supplied by an NEX-specific Nextendo provisioning contract. No private key, test key, fallback signature or other emulator credential is shipped. The builder is not wired to Eden ACC yet.
