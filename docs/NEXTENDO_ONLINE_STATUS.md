# NEX — Nextendo online implementation status

Updated: 6 November 2026

This file distinguishes code that exists in NEX from contracts and server credentials that have not been verified. A successful APK build is not evidence that in-game networking works.

## Verified Android baseline

- GitHub Actions run #54 completed successfully: [workflow run](https://github.com/XDavi196/NEX/actions/runs/42193366726).
- Baseline commit: `e52d79a4feb02b9ad7a2f5284a9a0e1612118508`.
- Release: [NEX v0.1.0-alpha.54](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.54).
- APK asset: `NEX-Android-v0.2.0-rc1-optimized.apk`.
- Pinned Eden source: `v0.2.0-rc1`, commit `8e373eb714aa01117ec885680477501cdb572112`.
- Android application ID: `com.nex.nx`.
- OAuth callback: `com.nex.nx://oauth/callback`.
- NEX public OAuth Client ID: `nxc_yAdR-14bOEq7`.

The current development branch is `nex/eden-v0.2.0-rc1-nextendo`. Post-baseline changes are being validated separately; consult [GitHub Actions](https://github.com/XDavi196/NEX/actions) for the latest run rather than assuming a newer APK exists.

## Implemented in NEX

- Android OAuth Authorization Code + PKCE flow, callback activity, encrypted local session storage, account panel and friends dialog.
- NEX-owned UI and app identity; no Den2 client secrets, signing keys, attestations, user tokens or session data are used.
- A separate parser for the documented `/api/nex-token` response. It requires the `nex_token` field with the `nx2.` prefix, a positive PID, a username and a positive `expires_in`. It deliberately treats the token as opaque and does not invent or decode its internal signature format.
- Unit tests for valid game-token responses, wrong prefixes, undocumented aliases, missing identity/expiry fields, invalid PID/lifetime and expiry overflow.
- A CI step runs the Android unit-test task before the APK build.

Additional contract-alignment work:
- OAuth session parsing accepts an access token even when a third-party response omits `refresh_token`; when that access token expires without a refresh credential, the app requests interactive sign-in.
- Profile and accepted-friend reads use the OAuth-protected `/api/oauth/userinfo` route. Friend records without an explicit presence field are displayed as presence unavailable, not incorrectly labelled offline.
- Incoming friend requests, presence mutations and game-token issuance are not inferred from routes that the published server source does not authorize for OAuth access tokens.
- An Android-to-native bridge transfers at most 300 accepted game-token friend records (PID, NSA, nickname, presence status, current application ID and bounded AppField) into a mutex-protected NEX-owned cache. The friend snapshot is refreshed on the 60-second Nextendo heartbeat; failed refreshes retain the last validated snapshot.
- The Eden overlay now registers legacy `nn::friends` list/profile handlers and the modern qLaunch SDK commands `GetFriendDetailedInfoV2` (20107), `GetFriendListForViewerV2` (20108), `GetFriendDetailListV2` (20109), `GetFriendPresenceV2` (20111) and `GetUserPresenceViewV2` (20601). Modern list records use the 0x220-byte layout; friend detail and presence buffers use 0x800, 0x80 and 0xE8 bytes respectively. The native build and guest-visible IPC behavior are still pending verification.
- The overlay carries the parsed game title ID into friend records and maps an explicitly online friend to the qLaunch online state. Unknown title IDs, play history, avatars, timestamps and friendship metadata are left blank; they are not fabricated. The user's own qLaunch presence uses the HOME Menu title ID only when the SDK's V2 presence view is requested.
- OAuth `/api/oauth/userinfo` alone still does not provide live presence. The richer `/api/friends` refresh remains conditional on a valid `nx2.` game token, following the public account-server implementation. Incoming requests, friend mutations, profile-image delivery and joinable-room metadata remain unavailable.
- Presence publishing currently uses the NEX-issued `nx2.` token because the inspected public account-server implementation accepts web-session/`nx2.` credentials on `/api/presence`; the NexWiki specifies OAuth `presence` scope instead. This behavior is provisional and must be changed to the documented OAuth flow if Nextendo confirms the production gateway follows the wiki.

These changes are partial account/HLE integration, not a claim that a game can authenticate, join matchmaking or play online.

## Contract audit: documentation versus public account-server source

References reviewed:

- [Official NexWiki Developer Documentation](https://wiki.nextendo.network/developers) — OAuth scopes, public-client PKCE, `/api/nex-token`, 24-hour game-token lifetime, BAAS `nnex` flow and refresh-token rotation.
- [NexWiki changelog](https://wiki.nextendo.network/changelog) — v1.8.6 application identity/closed API and v1.8.7 client-attestation update.
- [Ryujinx-Nextendo README](https://github.com/NextendoNetwork/Ryujinx-Nextendo#readme) — public explanation of official-client API request signing.
- [Ryujinx-Nextendo release bake script](https://github.com/NextendoNetwork/Ryujinx-Nextendo/blob/main/distribution/nextendo/bake_release.py) — public source says the registered Client ID is baked as the application identity.
- [Nextendo Developer Portal](https://nextendo.network/developers)
- [Nextendo architecture guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/ARCHITECTURE.md)
- [Nextendo deployment guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/DEPLOYMENT.md)
- [Public account-server OAuth implementation](https://github.com/NextendoNetwork/nextendo-account/blob/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a/oauth.go)
- [Public account-server route and token implementation](https://github.com/NextendoNetwork/nextendo-account/blob/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a/main.go)
- [Public BAAS JWKS service](https://github.com/NextendoNetwork/baas-jwks)

The Developer Portal describes a broader OAuth contract than the public account-server source currently implements. The account-server repository's `main` was at commit `61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a` (12 October 2026) when inspected. This source may lag the deployed service, so the following is a contract discrepancy to confirm with Nextendo—not proof that production behaves identically.

| Area | Developer documentation | Public account-server source |
|---|---|---|
| OAuth scopes | Documents `identity`, `friends`, `presence`, `game.matchmaking`, `mods.favorites` and `profil` | `oauthScopes` currently allows only `identity` and `friends` |
| Token grant | Documents access/refresh-token handling | `oauthToken` accepts `authorization_code`; its third-party response contains `access_token`, `token_type`, `expires_in` and `scope`, but no `refresh_token` |
| Game-token issuance | Describes `GET /api/nex-token` using an OAuth Bearer access token | `nexToken` calls `verifyToken`, which validates a web-session token; it does not validate the OAuth access token |
| Friends and presence | Describes OAuth scopes for social and presence data | `/api/oauth/userinfo` returns identity and basic friends when those scopes are present; `/api/friends` and `/api/presence` use `accountFromBearer`, which accepts a web-session token or an `nx2.` game token, not an OAuth access token |
| Game-token response | Documents `nex_token`, PID, friend code, username and `expires_in`; states the token lasts 24 hours and is game-only | The public `nexToken` handler returns token, PID, friend code and username, but does not include `expires_in` |
| Presence | OAuth app scope `presence`; publish at least once every 90 seconds via `POST /api/presence` | The checked source uses `accountFromBearer` (web session or `nx2.`), not OAuth |

The NexWiki is the intended developer contract. NEX keeps OAuth and `nx2.` credentials separate, but the current publisher has been temporarily aligned with the inspected public server code while the mismatch is confirmed. The older public account-server source may lag production; until Nextendo confirms the deployed contract and a live NEX OAuth session is tested, NEX cannot treat game-token issuance, refresh rotation, presence publishing or multiplayer authentication as verified. Do not switch to the special `nextendo-emulator` built-in client identity: NEX must use its own registered Client ID.

## Blocking requirements for real in-game online play

### 1. BAAS/ACC game authentication

- The account token is distinct from the OAuth access token. The documented game token has the `nx2.` prefix and belongs in the BAAS `id_token`'s `nnex` claim.
- The NexWiki confirms the game-token-to-`nnex` claim path, but the exact BAAS JWT header, claim schema, issuer/audience, `kid`, expiry rules, signature verification rules and NEX-specific client-attestation/provisioning material must still be confirmed.
- The public `baas-jwks` service publishes a public JWK derived from a private RSA key supplied separately to the signer. NEX has not been issued or registered its own BAAS signing key/JWK/`kid`.
- No signing key from Ryujinx-Nextendo, Den2 or another emulator may be reused. No placeholder signature or fabricated claim may be shipped.

**Blocker:** obtain the NEX-specific signing/attestation material and written BAAS contract from Nextendo, including how NEX's public key is registered with the JWKS service.

### 2. NEX/PRUDP and matchmaking

- The pinned Eden source does not contain a Nextendo PRUDP client or game matchmaking implementation.
- The public Nextendo repositories describe server-side services and transport architecture. That is not by itself a client packet contract for every game.
- A client implementation needs verified connection negotiation, packet framing, reliability/ordering, secure transport, service IDs, matchmaking request/response formats, timeouts and error handling for a specifically supported title.
- Do not port private keys or code from another emulator. Review repository licenses before using any source as an implementation dependency.

**Blocker:** a reproducible, permitted client protocol reference and a first supported game's service contract are required before implementing packet formats.

### 3. Game-facing `nn::friends` HLE and qLaunch

- NEX has a bounded native friend cache and an Android/JNI bridge that now retains the actual friend application ID as well as the PID/NSA, nickname, status and validated AppField.
- The overlay handles the legacy list/profile commands and adds the modern qLaunch commands 20107, 20108, 20109, 20111 and 20601. The modern list serializer follows the public Nextendo 0x220-byte friend-record layout; the friend-page serializer returns the 0x800-byte detail buffer and places a known current title ID at the documented qLaunch offsets.
- When the active emulated title is the Switch HOME Menu (`0100000000001000`), the Android presence publisher uses `app_detail=HOME` and the qLaunch description field. The qLaunch own-presence IPC view also returns the HOME Menu title for a linked NEX PID.
- Friend status and current title data are refreshed with the 60-second account heartbeat. No unknown avatar, play history, friend code, last-seen time or notification data is fabricated.

**Remaining blocker:** the current overlay commit must pass the full pinned-Eden native/Android build, then be exercised in qLaunch with a linked Nextendo account and live friends. Profile images, online notifications, received requests, friend mutations and some profile-page fields remain incomplete. This menu/HLE work does not make game matchmaking or multiplayer work.
### 4. Title-scoped DNS and TLS/SNI

- The deployment guide requires DNS redirection of the relevant retail online hostnames, TLS certificates valid for those hostnames, and SNI routing to the correct title service.
- It intentionally uses placeholders and does not provide real deployment addresses or a complete hostname matrix for every title.
- The pinned Eden DNS/SSL path must preserve hostname/SNI semantics and normal certificate validation. NEX must not disable TLS verification or add a wildcard redirect without an explicit contract.

**Blocker:** Nextendo must supply or confirm the supported title hostname matrix, service routing contract, trusted CA/public certificate requirements and the NEX deployment's server addresses. No addresses are hardcoded.

### 5. NAT, UDP mapping, discovery and P2P

- The deployment guide requires two distinct public vantage addresses for the NAT-check responder; the NAT check compares observations from different addresses.
- A working client must preserve the game's UDP mapping during NAT checks, keep sockets alive for the documented lifetime, perform player/session discovery and handle P2P hole-punching and teardown.
- The pinned Eden socket lifecycle has not been extended or verified for Nextendo NAT mapping retention.

**Blocker:** exact NNCS/NAT message contract, probe ports, timeout/keepalive policy, discovery flow and NEX-approved server/NAT addresses are required.

## TLS and credential policy

- Keep platform certificate validation enabled.
- Never copy credentials, private signing keys, attestation material, access tokens, refresh tokens or sessions from Den2 or any other emulator.
- Never log bearer tokens, game tokens, private keys or attestation payloads.
- Keep OAuth access tokens and `nx2.` game tokens separate.
- Any missing endpoint, claim, packet field, key or address is a blocker—not an invitation to invent one.

## Test and release gate

Kotlin tests cover the documented `nx2.` game-token response and strict game-friend payload parsing, including the bounded 0xC0-byte presence field. A host C++ test covers the native friend cache's PID/status validation, de-duplication, 300-entry bound, app-field bound and clearing. These tests do not validate the `nnex` JWT claim, cryptographic signatures, BAAS, PRUDP, HLE IPC serialization, NAT state transitions, UDP cleanup or P2P.

Before calling online support functional, add and pass:
1. BAAS token construction/claim/signature validation tests using NEX-issued key material.
2. HLE `nn::friends` IPC serialization and guest-visible behavior tests (the native cache itself now has a host-side CI test).
3. PRUDP framing, parsing, sequencing and matchmaking tests against a reproducible public contract.
4. NAT state-transition, UDP mapping retention, timeout and resource-cleanup tests.
5. End-to-end tests with two NEX accounts, two Android devices and a Nextendo-supported game.

Until all external contracts are confirmed and the end-to-end test succeeds, NEX in-game multiplayer remains **not implemented / not verified**.
