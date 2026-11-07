# NEX — Nextendo online implementation status

Updated: 7 November 2026.

A successful APK build proves that the configured Android source and native toolchain compile. It does not prove that the live account service, BAAS, SMM2 game service or multiplayer works.

## Verified build evidence

- Original requested baseline: [Actions run #54](https://github.com/XDavi196/NEX/actions/runs/42193366726), successful.
- Original baseline release: [NEX v0.1.0-alpha.54](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.54).
- Intermediate verified build: [Actions run #97](https://github.com/XDavi196/NEX/actions/runs/42321531501), successful.
- Last verified baseline before this visual cleanup: [Actions run #102](https://github.com/XDavi196/NEX/actions/runs/42382244088), successful.
- Run #102 source commit: \`1eeaef07c96ae9d365c9739c22eb523c857937c2\`.
- Release: [NEX v0.1.0-alpha.102](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.102).
- APK: \`NEX-Android-v0.2.0-rc1-optimized.apk\`, 27,281,806 bytes.
- GitHub release asset SHA-256: \`2ae9258b771204107e5aad879122d1ab77ae83f27517b292458207c96fda2cc4\`.
- Release also includes \`SHA256SUMS.txt\`.
- Run #102 passed the native friend-cache/HLE serializer test, Android Nextendo unit tests, optimized Android APK build, artifact upload and experimental release publication. It requested all six portal scopes.
- The current branch now includes logo restoration and glass-effect removal; those visual changes require the next CI build before they are considered verified.
- Pinned Eden source: \`v0.2.0-rc1\`, commit \`8e373eb714aa01117ec885680477501cdb572112\`.
- Android application ID: \`com.nex.nx\`.
- OAuth callback: \`com.nex.nx://oauth/callback\`.
- NEX public OAuth Client ID: \`nxc_yAdR-14bOEq7\`.
- Development branch: \`nex/eden-v0.2.0-rc1-nextendo\`.

Run #102 verified code commit \`1eeaef07c96ae9d365c9739c22eb523c857937c2\`. The logo and visual cleanup in the current branch are newer source changes and are not covered by that APK until the next build completes.

## Implemented in NEX

### Android account identity

- NEX uses its own public OAuth Authorization Code + PKCE (S256) client registration, callback, Android Keystore-backed session storage and account panel.
- Requested scopes now match the owner-provided portal URL exactly: `identity friends presence profil game.matchmaking emulator.status`.
- OAuth access tokens and \`nx2.\` game tokens are separate credential types.
- The game-token parser requires the documented \`nx2.\` prefix, positive PID, username and positive \`expires_in\`. The token remains opaque; NEX does not forge or decode its internal signature.
- Refresh-token rotation is serialized and the returned token set is persisted as a whole. If a refresh token is absent, NEX does not invent one.
- Presence publishing uses an unexpired OAuth access token with the \`presence\` scope and a 60-second heartbeat. It never substitutes the \`nx2.\` game token for the documented OAuth credential.
- Game friends are fetched separately with the \`nx2.\` token. The HOME Menu publisher does not fabricate AppField data.

### ACC and player identity

- Eden retains its original local ACC account ID. The NEX PID is not substituted for Eden's account ID, NSA or Switch account UUID.
- The NEX PID remains available to the NEX-owned social/account context; this does not create a BAAS `id_token` or authenticate a NEX game session.
- The exact BAAS/ACC game-auth contract and guest-level validation remain blockers.

### Native friend cache and nn::friends HLE

- Android transfers a bounded friend snapshot through JNI to a mutex-protected NEX-owned native cache.
- Each entry may contain PID, explicit NetworkServiceAccountId (NSA), username, presence status, current application ID and bounded AppField.
- Game-facing list/profile/detail/presence handlers and qLaunch V2 serializers are wired to that cache in the Eden overlay.
- Records are exposed only when the API provides an explicit non-zero NSA. PID is not silently substituted for a friend's NSA.
- Switch account UUIDs and last-seen timestamps are not synthesized. Unknown fields remain zero.
- Presence status is preserved as supplied. Ordinary online status is not promoted to OnlinePlay, and a title ID is written only when the friend is explicitly in-game and the API supplied a valid title ID.
- The public account-server friend response currently has no confirmed NSA or Switch UUID field. Therefore the game-facing friend list remains empty for entries without an explicit NSA. This is fail-closed behavior, not a claim that the social API is complete.
- The C++ host test validates cache bounds, duplicate filtering, explicit-NSA filtering and qLaunch V2 record serialization. Guest-visible IPC behavior still needs validation inside Eden/qLaunch and a supported game.

### PRUDP, RMC and SMM2 protocol primitives

- A strict PRUDP-Lite frame codec handles the 12-byte header, option blocks, payload lengths, concatenated frames and incomplete stream reads.
- A strict RMC envelope codec handles request, success-response and error-response headers.
- The SMM2 DataStore protocol identifier (115) and public method identifiers are recorded from the NintendoClients DataStore_SMM2 schema.
- The stream decoder clears incomplete frames when closed.
- Android unit tests cover token parsing, game-friend parsing, OAuth parsing, presence authorization, PRUDP-Lite frames, RMC envelopes, social parsing and NAT probe/state handling.

These are codecs and parsers only. NEX does not yet establish a live PRUDP session, complete service authentication, reliability/acknowledgement windows, retransmission, secure-channel negotiation, SMM2 method payload serialization or matchmaking.

### NAT primitives

- A 16-byte big-endian NNCS probe codec follows the public Nextendo NNCS implementation.
- A conservative two-vantage tracker classifies equal mappings, changed ports and ambiguous observations.
- The tracker rejects duplicate/unknown vantage observations and clears its state on close.
- The tracker owns no UDP socket. It does not preserve the emulator's live mapping, schedule keepalives, discover peers or perform P2P hole-punching.

## Current Nextendo public-contract audit

### Developer documentation and public account source

The current [Nextendo Developer Documentation](https://wiki.nextendo.network/developers) describes:

- A public OAuth client using PKCE and the scopes \`identity\`, \`friends\`, \`presence\` and \`game.matchmaking\`.
- A one-hour OAuth access token and a 30-day rotating refresh token.
- \`GET /api/nex-token\` using the OAuth access token and the \`game.matchmaking\` scope.
- A separate \`nx2.\` game token with a 24-hour lifetime.
- The game token in the BAAS \`id_token\` under the \`nnex\` claim; the game server checks the signature, expiry and PID.
- Presence publication by the OAuth client at least every 90 seconds.

The public [nextendo-account source](https://github.com/NextendoNetwork/nextendo-account/tree/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a), last updated 12 October 2026, does not match all of that contract:

- Its public OAuth scope allow-list contains \`identity\` and \`friends\`, not \`presence\` or \`game.matchmaking\`.
- Its public third-party authorization-code exchange does not return a refresh token.
- Its \`/api/nex-token\` handler validates a web-session token rather than the documented OAuth access token and does not include \`expires_in\`.
- Its \`/api/presence\` handler accepts a web-session token or \`nx2.\` token, not the OAuth access token described by the Developer Documentation.
- Its \`/api/friends\` handler accepts a web-session token or \`nx2.\` game token, not an OAuth access token.
- Its friend response contains PID and presence information but does not expose a confirmed NSA or Switch account UUID.

NEX follows the published Developer Documentation where a public contract exists. It does not switch to a private web-session flow, infer missing identifiers or use a different emulator's credentials. The production deployment may be ahead of the public source; that has not been independently verified.

### BAAS, nnex claim and client attestation

- The Developer Documentation requires the \`nx2.\` game token in the BAAS \`nnex\` claim and points to the official emulator's \`ManagerServer.cs\` for the exact construction.
- The public documentation page does not enumerate the complete NEX-specific JWT contract: exact claim shape/value, issuer, audience, key identifier, key URL, signing algorithm and time-claim rules.
- The NEX-owned private signing key and registration of its corresponding public key with the Nextendo JWKS service have not been issued to this project.
- The Nextendo changelog describes mandatory cryptographic client attestation for emulator API requests. NEX has its own public Client ID but no NEX-issued attestation signing material or confirmed signing procedure.
- No Den2 or other emulator key, attestation, token or session is used.

**Blocker:** Nextendo must issue NEX-specific signing/attestation material and confirm the exact BAAS claim, JWT, JWKS and API request-signature contract. No JWT or attestation is fabricated or shipped.

### Super Mario Maker 2 NEX service

Super Mario Maker 2 is the first intended live test target. The official Nextendo organization publishes a dedicated [SMM2 NEX server repository](https://github.com/NextendoNetwork/super-mario-maker-2), confirming that Nextendo has a title-specific server implementation.

The public server repository is server-side code, not a complete client handshake specification. The current public sources do not provide a verified SMM2 client hostname/port, NEX access key, title service negotiation, authenticated session contract, full RMC payload schema for the required game flow or the deployed endpoint mapping for this NEX registration.

**Blocker:** Nextendo must confirm the SMM2 client endpoint and title-specific authentication/matchmaking contract before NEX opens a live connection. NEX does not guess a server address or access key.

### Title DNS and TLS/SNI

- The Nextendo deployment guide describes title-scoped hostname routing, SNI passthrough and certificates valid for the original retail hostnames.
- Its published port table lists examples for Mario Kart 8 Deluxe, Splatoon 2 and Super Smash Bros. Ultimate, but not a complete SMM2 mapping.
- It uses deployment placeholders and does not publish the NEX deployment's server addresses.
- NEX does not add guessed hostnames, IP addresses, wildcard redirects or disabled certificate checks.

**Blocker:** obtain the SMM2 hostname matrix, destination routing, certificate trust requirements and NEX service addresses. Platform TLS certificate validation remains enabled.

### NAT, UDP mapping, discovery and P2P

- The public Nextendo NNCS implementation documents a 16-byte probe that reports the observed external port/address and server address.
- The deployment guide requires observations from two distinct, client-reachable vantage addresses.
- NNCS ports and service endpoints are deployment/title-specific; the inspected public examples do not confirm the SMM2 values.
- The new codec/tracker does not bind or preserve the emulator's UDP socket, run a keepalive schedule, discover peers or execute hole-punching.
- No SMM2-specific NAT-check endpoints, probe ports, discovery messages or P2P lifecycle have been verified.

**Blocker:** obtain the SMM2 NAT-check endpoints and ports, probe/keepalive timing, session-discovery flow and socket-lifetime requirements.

## Test and release gate

Run #99 passed:

1. The native friend-cache/HLE serializer test.
2. The Android Nextendo unit-test task, including game-token, game-friend, OAuth, presence, PRUDP-Lite, RMC, social and NAT tests.
3. The optimized Android APK build against the pinned Eden source.
4. Artifact upload and GitHub release publication.

This is build/test evidence only. It is not a live account-service acceptance test or a multiplayer test.

Required before claiming in-game online support:

1. BAAS JWT construction and claim/signature validation using NEX-issued key material.
2. Confirmed OAuth/game-token/refresh/presence API behavior in the live deployment.
3. A verified NEX PID ↔ game account/NSA mapping and guest-visible HLE IPC tests.
4. PRUDP handshake, authentication, reliability, RMC payload and SMM2 service integration tests.
5. SMM2 title-specific DNS/TLS/SNI tests with certificate validation enabled.
6. NAT state, live UDP mapping retention, keepalive, peer discovery, hole-punching and cleanup tests.
7. End-to-end testing with two NEX accounts, two Android devices and Super Mario Maker 2.

Until those checks pass, NEX in-game multiplayer remains **not implemented / not verified**.


## NEX UI and current device-facing status

- The official NEX logo is used in the Android launcher identity and the Games screen header.
- A NEX-owned Android glass layer styles fragment bars, Material buttons, selected card surfaces and on/off switches with translucent gradients and accent strokes. It remains compatible with Android 11 and does not depend on the iOS-only Expo GlassEffect component.
- The account panel now separates account sign-in from game readiness. A valid \`nx2.\` token is displayed as a prerequisite only; SMM2 is still marked disconnected until BAAS/nnex signing, title endpoint and PRUDP session are implemented and verified.
- The friends panel explains that an OAuth-visible friend is not automatically a game-visible friend: qLaunch records still require a confirmed NSA/UUID mapping.
- OAuth callback state is now mandatory and compared in constant time before exchanging an authorization code.
- The account panel retries the documented game-token request when a granted \`game.matchmaking\` scope exists and the stored game token is missing/expired. Failure does not invalidate OAuth account access and is not presented as multiplayer readiness.

## CI capacity blocker

The supplied GitHub Budgets and Alerts screenshot shows all five configured budgets at 100% with \`Usage blocked\`. GitHub documents that an exhausted budget with \`Stop usage when budget limit is reached\` can block GitHub Actions hosted runners. A code push can update the branch, but a new build/release cannot be considered verified until the applicable Actions budget is raised or the stop-usage setting is changed by the account owner. This is a billing/runner-capacity blocker, not an NEX source compile result.


## Portal checklist — values to verify in the NEX application

Open the NEX application entry at https://nextendo.network/developers and verify the following visible configuration (a redacted screenshot is sufficient):

1. Application type: **Public** (Android is a distributed native client and must use PKCE; do not configure or embed a client secret).
2. Client ID: \`nxc_yAdR-14bOEq7\`.
3. Registered redirect URI: \`com.nex.nx://oauth/callback\` (exact spelling, scheme and path).
4. The owner-provided portal-generated URL selects: `identity friends presence profil game.matchmaking emulator.status`. NEX requests this exact list. The inspected public account-server source has only `identity` and `friends` in its OAuth allow-list; it does not currently show `presence`, `profil`, `game.matchmaking` or `emulator.status`. The live deployment may differ, so verify the actual token response's `scope` field and report any `invalid_scope` response to Nextendo. Do not silently assume that portal selection equals a granted permission.
5. Application status and NEX team member role/approval: confirm the app can be used by the owner/test account in the current portal rollout.

Do **not** generate or send a bot token for this Android login flow. Do not send access tokens, refresh tokens, authorization codes, the \`nx2.\` game token, passwords, client secrets, or private keys in chat or screenshots. A public app should not have a client secret.

## Separate Nextendo provisioning request — not ordinary Portal fields

The standard OAuth application page does not provide the missing BAAS signing and game-service configuration. Nextendo must provision/confirm separately for NEX:

- NEX-specific BAAS RS256 signer or approved signing integration, plus the assigned \`kid\` and public JWK registration.
- Exact NEX BAAS \`iss\`, \`aud\`, \`jku\`, \`sub\`/device-identity rules, time claims, \`hm\` semantics and expiry; confirmation of optional \`tv\`/title claims.
- Client-attestation contract and NEX-issued attestation material or managed attestation method.
- Confirmation that OAuth access tokens with \`game.matchmaking\` are accepted by \`GET /api/nex-token\` and that its response includes the documented \`nex_token\`, \`pid\`, \`username\`, \`friend_code\`, and \`expires_in\` fields.
- Confirmation that OAuth access tokens with \`presence\` are accepted by \`POST /api/presence\`.
- SMM2 endpoint/port, service negotiation and matchmaking contract, title DNS/TLS/SNI mapping, and SMM2 NNCS/NAT/P2P endpoints and timing.

### Public account-server source discrepancy

The public \`nextendo-account\` source currently differs from the Developer Documentation: its OAuth allow-list contains only \`identity\` and \`friends\`; third-party authorization-code responses return no refresh token; \`/api/nex-token\` validates a web-session token (not an OAuth bearer) and returns no \`expires_in\`; and \`/api/presence\` accepts a web-session or \`nx2.\` token (not the documented OAuth bearer). The production deployment may have moved ahead, but NEX has not independently verified that. NEX therefore continues to use only the documented public OAuth flow and will not switch to private web-session authentication.

### BAAS builder boundary

\`NextendoBaasIdTokenBuilder\` now defines the compact-JWT boundary: RS256 header, NEX-provisioned \`kid\`, HTTPS issuer/JWKS values supplied by the contract provider, and the server-issued \`nx2.\` token inserted into the \`nnex\` claim. The signing key is behind \`NextendoBaasSigner\`; no private key or fallback signer is included. The builder is not yet connected to Eden ACC and must stay disabled until Nextendo provisions the NEX-specific contract and signer. Unit tests use a fake signer only to test serialization and rejection behavior.
