# Android Nextendo integration

This folder contains the NEX-owned Android integration source:

- `NextendoAuthClient.kt` — OAuth Authorization Code + PKCE, registered Client ID header, token refresh, userinfo, presence publishing and the separate `nx2.` game-token request.
- `NextendoSessionStore.kt` — AES-GCM encrypted OAuth/game session, token-free API diagnostics and pending PKCE transaction storage using Android Keystore.
- `NextendoAuthManager.kt` — Chrome Custom Tab login, callback verification, account panel, friends UI and background session refresh.
- `NextendoCallbackActivity.kt` — Android deep-link callback handler.
- `NextendoAccountApi.kt` — authenticated read-only player profile lookup.
- `NextendoSocialApi.kt` — OAuth `/api/oauth/userinfo` and read-only friends list.
- `NextendoChatClient.kt` — opt-in adapter for the independent Den2 friend-chat service; NEX uses its own PID/session and an encrypted per-installation chat secret, never its OAuth or `nx2.` token as the chat credential.
- `NextendoChatDialog.kt` — friend conversation UI with message polling, send, read receipts, block/report actions and an explicit third-party data disclosure.
- `NextendoPresencePublisher.kt` — online presence heartbeat every 60 seconds, offline publication on disconnect, and in-game Title ID publication from the Eden lifecycle hook.
- `NextendoPrudpLiteAuthenticator.kt` — Nextendo published Lite CONNECT HMAC-MD5 proof, using only a runtime-provided title key.
- `NextendoSmm2Profile.kt` — public SMM2 title profile, WSS endpoint construction and dynamic secure StationURL parsing.
- `NextendoNatProbeClient.kt` — bounded two-vantage NNCS UDP probe using one local socket and explicit runtime endpoint configuration.
- `NextendoBaasIdTokenBuilder.kt` — RS256 BAAS `id_token` with the provisioned JWKS `jku` header and the `nnex` game-token claim.
- `NextendoBaasJwk.kt` — RFC 7638 RSA JWK thumbprint and public-only JWKS serialization.
- `NextendoAndroidKeystoreBaasSigner.kt` — per-installation non-exportable RSA-2048/RS256 key in Android Keystore, with public JWK export only.
- `NextendoAndroidKeystoreBaasProvider.kt` — provider using only provisioned issuer/audience/subject mapping and game-token-bounded `iat`/`exp` claims.
- `NextendoBaasAuthenticationInfo.kt` — wraps the signed JWT in the NEX `AuthenticationInfo` DataHolder for `LoginEx.extraData`.
- `NextendoPrudpLiteSession.kt` — tested PRUDP-Lite handshake, reliable DATA/ACK handling, duplicate suppression, fragmentation and connection state machine.
- `NextendoPrudpLiteWebSocketTransport.kt` — OkHttp binary WebSocket transport with normal TLS verification.
- The guest SSL path records bounded SNI/TLS handshake outcomes for the SMM2 auth host alongside the payload-blind PRUDP SYN/CONNECT metadata. The dashboard distinguishes TLS progress/failure from a PRUDP handshake; this is observability only, not a game-session client.

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

## Optional friend chat (external service)

The conversation UI is adapted from DEN's GPL-3.0-or-later `DenChat.kt` (upstream repository: https://github.com/Dmr77680/Den2, source revision `be6c6ae3161ad5d7ddd232510e4f97f182619cdc`). NEX retains its own Nextendo OAuth client, encrypted account session and PID lookup.

**Important:** the messaging backend is not operated by Nextendo. The adapted DEN client points to `https://2-29-6-216.sslip.io`, a service operated by the DEN developer. Before the first chat request, NEX asks the player to opt in and discloses the data sent: the player's PID, accepted-friend PIDs, messages, read receipts, block and report actions. NEX does not send OAuth access tokens, refresh tokens or `nx2.` game tokens to this service.

A random per-installation chat secret is encrypted with an AES-GCM key in Android Keystore. The chat feature can be disabled locally from the conversation's Options menu. Disabling it does not erase messages already stored by the external operator; deletion requests must be sent to the service operator.

This adapter provides private direct messages only. It does not implement Nextendo matchmaking, BAAS, game presence or in-game voice chat.

The Android integration currently has:
- OAuth login/session scaffold and account profile panel.
- Read-only friends list from `/api/oauth/userinfo`.
- App-level online heartbeat, offline publication and real game Title ID status via the repeatable Eden overlay.
- Separate `nx2.` game-token request.

Still missing:
- Friend request mutations and final guest-visible `nn::friends` IPC validation.
- Native game lifecycle callback for real in-game presence/title ID.
- Production BAAS signer and NEX JWKS registration, provisioned `kid`/`jku` and issuer/audience/subject values, client-attestation material, and wiring of the JWT + `AuthenticationInfo` DataHolder into Eden ACC/LoginEx.
- The live SMM2 auth hostname/service IP and DNS/TLS mapping. The public SMM2 profile now supplies the public PRUDP title key and version settings; the secure WSS endpoint must come from the StationURL advertised by LoginEx.
- The secure PRUDP CONNECT ticket/check payload derived from LoginEx, complete SMM2 RMC methods and matchmaking. The PRUDP-Lite transport remains a tested module, not a live title session.
- Automatic wiring of the NNCS probe into the guest game's NAT lifecycle, SMM2 endpoint provisioning, periodic keepalive, peer discovery, matchmaking signaling and UDP hole-punching.
- End-to-end testing with two accounts and a supported game.

A successful OAuth login or retrieval of an `nx2.` token does not by itself prove multiplayer works. See [the online status checklist](../../docs/NEXTENDO_ONLINE_STATUS.md).

Never print access tokens, refresh tokens, game tokens, callback codes or PKCE verifiers to Android logs.


## NEX visual identity and online diagnostics

The fragment-wide glass treatment was removed. The launcher uses an adaptive NEX icon based on the original logo, while the Games header keeps Eden's Material title view and displays the text `NEX`. The app retains Eden's existing Material styling.

The account panel distinguishes OAuth connectivity from the separate `nx2.` game credential. It displays the scopes returned by OAuth, the latest token-free HTTP result for the presence and game-token requests, and whether SMM2 is ready. The game-token result is saved inside the encrypted Android Keystore-backed session. qLaunch friend rows are only available when the public API supplies an explicit NSA; a PID is never substituted.


## BAAS boundary (not enabled)

The NEX BAAS pipeline now builds the RS256 compact JWT, puts the current server-issued \`nx2.\` token in the string-valued \`nnex\` claim, and encodes it as the \`Token\` field of the \`AuthenticationInfo\` DataHolder for \`LoginEx.extraData\`. The JWS \`kid\` and \`jku\` are supplied by the NEX runtime signer; \`iss\`, \`aud\` and \`sub\` are supplied by the provisioned claim provider. The NEX Android Keystore signer now creates a per-installation RSA-2048 key with a non-exportable private component, signs the exact compact-JWT input with SHA256withRSA, and exports only a public RSA JWK Set whose `kid` is the RFC 7638 thumbprint. The account network settings can copy that public JWKS for registration. A provisioned provider can then use server-supplied issuer, audience, subject mapping and token lifetime; its `exp` is capped at the current `nx2.` token expiry. The key/JWK tests verify public-only serialization and RS256 interoperability. This still does not publish the JWK to a public endpoint or satisfy Nextendo's registration/attestation contract, so the runtime remains fail-closed by default.

See [the SMM2 profile and BAAS/LoginEx contract](../../docs/NEXTENDO_SMM2_PROFILE.md) for the public SMM2 values and the remaining live-provisioning requirements. No private key, server password, fallback signature or other emulator credential is shipped.

## Den2 UI adaptation attribution

The compact Nextendo friends/account entry point in the Games toolbar and the per-title online/version badges are adapted from the Android UI work in [Dmr77680/Den2](https://github.com/Dmr77680/Den2), released under GPL-3.0. Eden Optimized remains the emulator base. NEX retains its own package ID, Nextendo public Client ID, PKCE authentication, encrypted session storage, APIs and HLE/network implementation. No Den2 account token, OAuth client identity, private signing key or attestation material is used.
