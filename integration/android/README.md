# Android Nextendo integration

This folder contains the NEX-owned Android integration. DEN2 is a technical reference only; NEX keeps its own registered application identity, OAuth implementation, local session storage and native/network code.

## NEX application identity

- Android application ID: `com.nex.nx`
- Public OAuth Client ID: `nxc_yAdR-14bOEq7`
- OAuth redirect: `com.nex.nx://oauth/callback`
- Authorization URL used by the NEX portal configuration: `https://nextendo.network/oauth/authorize`
- Token URL: `https://nextendo.network/api/oauth/token`
- Application identity header: `X-Nextendo-Client-Id`
- Requested scopes: `identity friends presence profil game.matchmaking emulator.status`
- OAuth flow: Authorization Code + PKCE (S256); no client secret in the APK.

The Client ID above belongs to NEX. Do not substitute DEN's `nxc_z_fetCuVO1qx`, another emulator's ID, or an ID from an old NEX experiment. Verify the registered NEX application and granted scopes in the Nextendo Developer Portal; the requested scopes are not proof that the server granted them.

## Current NEX-owned components

- `NextendoAuthClient.kt` — NEX OAuth Authorization Code + PKCE, token exchange/refresh, userinfo, presence, public statistics, online-counts, separate `nx2.` game-token request, and game-token-backed friend list/add/accept/decline/remove calls.
- `NextendoSessionStore.kt` — AES-GCM encrypted OAuth/game session and pending PKCE transaction storage using Android Keystore.
- `NextendoSessionRefresher.kt` — serialized refresh-token rotation; persists the new token set.
- `NextendoAuthManager.kt` — Chrome Custom Tab login, callback verification, account panel, friend actions, NEX session backup/restore and background refresh.
- `NextendoSessionBackupCodec.kt` — password-encrypted export/import of a NEX-created backup. It does not parse or import another application's bearer-token file.
- `NextendoAccountApi.kt` and `NextendoSocialApi.kt` — authenticated own-profile, accepted/incoming friend parsing, and friend mutations through the NEX game token.
- `NextendoPresencePublisher.kt` — app presence heartbeat and offline publication.
- `NextendoNativeBridge.kt`, `nextendo_friend_cache.h` and `nextendo_friend_hle.h` — native friend-cache bridge and guest-facing friend serializers/handlers.
- `NextendoBaasRuntime.kt`, `NextendoBaasIdTokenBuilder.kt`, `NextendoBaasAuthenticationInfo.kt` — a fail-closed BAAS construction/cache boundary. It delivers no token unless an approved NEX signing/claim provider is provisioned.
- `NextendoNetworkRouting.kt`, `NextendoExactHostDns.kt` and the native exact-host DNS cache — explicit hostname routing without wildcard redirects or disabling TLS verification.
- `NextendoPrudpLiteSession.kt`, `NextendoPrudpLiteWebSocketTransport.kt`, `NextendoNatProbeClient.kt` and the packet observer — protocol tests and diagnostics. These do not yet constitute a live guest-game session.

The sources are copied into the pinned Eden Android tree by `scripts/apply-eden-overlay.py`; they are not a separately compiled Android application module.

## DEN2 and Citron reference integration

The [DEN2](https://github.com/Dmr77680/Den2) and [Citron Nextendo](https://git.nextendo.network/NextendoNetwork/citron-nextendo) source trees are architectural references only. NEX keeps its own public Client ID `nxc_yAdR-14bOEq7`, OAuth redirect, PKCE flow, encrypted session and native networking code. NEX now has friend mutations using its own server-issued `nx2.` token; no DEN or Citron client identity or session is reused.

See [the DEN2/Citron integration audit](../../docs/DEN2_INTEGRATION_AUDIT.md) for the file-by-file comparison and outstanding requirements.

## Multiplayer boundary

A successful OAuth login, an accepted `nx2.` game token, account eligibility, a passing compile, or a PRUDP unit test is not proof of in-game multiplayer.

The Nextendo founder explicitly declined client-generated BAAS keys, third-party RS256 BAAS tokens and per-installation JWKS registration, and said there is no third-party certification/token contract available at present. Therefore NEX must not copy DEN's local RSA generation/signing fallback or attempt to make an unregistered signature appear trusted. `NextendoBaasRuntime` must remain fail-closed until Nextendo provides an approved game-authentication mechanism.

Remaining live-game work includes:

- An officially accepted game-authentication/BAAS mechanism for NEX.
- Any required public-client API attestation contract, if protected endpoints require it.
- Confirmed production SMM2 auth hostname, route IPv4, TLS certificate and SNI behavior.
- Live LoginEx and secure StationURL/PRUDP negotiation using server-returned parameters.
- Actual SMM2 RMC matchmaking/room operations and server-side availability.
- SMM2-specific NNCS/NAT endpoints, keepalive, peer discovery and UDP hole-punching.
- Wiring the tested PRUDP/NAT components into the guest game's actual socket and lifecycle paths.
- End-to-end tests with two authorized NEX accounts, two Android devices and the same supported game version.

Never print access tokens, refresh tokens, `nx2.` tokens, BAAS JWTs, authorization codes, PKCE verifiers or passwords to logs or share them in chat. For support, send token-free diagnostics only.
