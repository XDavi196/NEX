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

These items are implementation work, not a claim that OAuth, account endpoints, game authentication or multiplayer have been verified against two real NEX accounts.

## Contract audit: documentation versus public account-server source

References reviewed:

- [Nextendo Developer Portal](https://nextendo.network/developers)
- [Nextendo architecture guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/ARCHITECTURE.md)
- [Nextendo deployment guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/DEPLOYMENT.md)
- [Public account-server OAuth implementation](https://github.com/NextendoNetwork/nextendo-account/blob/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a/oauth.go)
- [Public account-server route and token implementation](https://github.com/NextendoNetwork/nextendo-account/blob/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a/main.go)
- [Public BAAS JWKS service](https://github.com/NextendoNetwork/baas-jwks)

The Developer Portal describes a broader OAuth contract than the public account-server source currently implements. The account-server repository's `main` was at commit `61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a` (12 October 2026) when inspected. This source may lag the deployed service, so the following is a contract discrepancy to confirm with Nextendo—not proof that production behaves identically.

| Area | Developer documentation | Public account-server source |
|---|---|---|
| OAuth scopes | Describes identity, friends, presence, profile and game matchmaking scopes | `oauthScopes` currently allows only `identity` and `friends` |
| Token grant | Documents access/refresh-token handling | `oauthToken` accepts `authorization_code`; its third-party response contains `access_token`, `token_type`, `expires_in` and `scope`, but no `refresh_token` |
| Game-token issuance | Describes `GET /api/nex-token` using an OAuth Bearer access token | `nexToken` calls `verifyToken`, which validates a web-session token; it does not validate the OAuth access token |
| Friends and presence | Describes OAuth scopes for social and presence data | `/api/oauth/userinfo` returns identity and basic friends when those scopes are present; `/api/friends` and `/api/presence` use `accountFromBearer`, which accepts a web-session token or an `nx2.` game token, not an OAuth access token |
| Game-token response | Documents `nex_token`, PID, friend code, username and expiry | The public `nexToken` handler returns token, PID, friend code and username, but does not include `expires_in` |

Until Nextendo confirms which contract is live and aligns the docs/server implementation, NEX cannot safely assume that its third-party OAuth access token can obtain a game token, read the full friends/presence data, or refresh the session. Do not switch to the special `nextendo-emulator` built-in client identity: NEX must use its own registered Client ID.

## Blocking requirements for real in-game online play

### 1. BAAS/ACC game authentication

- The account token is distinct from the OAuth access token. The documented game token has the `nx2.` prefix and belongs in the BAAS `id_token`'s `nnex` claim.
- The exact BAAS JWT header, claim schema, issuer/audience, `kid`, expiry rules, signature verification rules and client-attestation contract must be confirmed for NEX.
- The public `baas-jwks` service publishes a public JWK derived from a private RSA key supplied separately to the signer. NEX has not been issued or registered its own BAAS signing key/JWK/`kid`.
- No signing key from Ryujinx-Nextendo, Den2 or another emulator may be reused. No placeholder signature or fabricated claim may be shipped.

**Blocker:** obtain the NEX-specific signing/attestation material and written BAAS contract from Nextendo, including how NEX's public key is registered with the JWKS service.

### 2. NEX/PRUDP and matchmaking

- The pinned Eden source does not contain a Nextendo PRUDP client or game matchmaking implementation.
- The public Nextendo repositories describe server-side services and transport architecture. That is not by itself a client packet contract for every game.
- A client implementation needs verified connection negotiation, packet framing, reliability/ordering, secure transport, service IDs, matchmaking request/response formats, timeouts and error handling for a specifically supported title.
- Do not port private keys or code from another emulator. Review repository licenses before using any source as an implementation dependency.

**Blocker:** a reproducible, permitted client protocol reference and a first supported game's service contract are required before implementing packet formats.

### 3. Game-facing `nn::friends` HLE

- In the pinned Eden revision, `src/core/hle/service/friend/friend.cpp` registers several list/profile/presence IPC commands as null handlers or stubs. The implemented handlers do not provide a complete NEX-backed friend cache.
- NEX's Android friends UI is not the same thing as the Switch game's `nn::friends` IPC surface.
- The game-facing cache needs stable PID/account identity, friend-list serialization, profile records, presence data and title-specific AppKeyValueStorage where the game uses joinable-session presence.

**Blocker:** complete IPC buffer/structure contracts and the NEX-to-HLE cache bridge must be implemented and tested against the pinned Eden ABI. Unknown fields must not be guessed.

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

The new parser tests cover only the documented game-token response envelope. They do not validate the `nnex` JWT claim, cryptographic signatures, BAAS, PRUDP, HLE IPC, NAT state transitions, UDP cleanup or P2P.

Before calling online support functional, add and pass:
1. BAAS token construction/claim/signature validation tests using NEX-issued key material.
2. HLE `nn::friends` IPC serialization and cache tests.
3. PRUDP framing, parsing, sequencing and matchmaking tests against a reproducible public contract.
4. NAT state-transition, UDP mapping retention, timeout and resource-cleanup tests.
5. End-to-end tests with two NEX accounts, two Android devices and a Nextendo-supported game.

Until all external contracts are confirmed and the end-to-end test succeeds, NEX in-game multiplayer remains **not implemented / not verified**.
