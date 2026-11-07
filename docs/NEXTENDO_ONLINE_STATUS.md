# NEX — Nextendo online implementation status

Updated: 7 November 2026

A successful APK build proves that the app compiles. It does not prove that the live account service, BAAS, game services, or multiplayer work.

## Verified build evidence

- Original baseline: [Actions run #54](https://github.com/XDavi196/NEX/actions/runs/42193366726), successful.
- Baseline release: [NEX v0.1.0-alpha.54](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.54).
- Later verified build: [Actions run #97](https://github.com/XDavi196/NEX/actions/runs/42321531501), successful.
- Run #97 code commit: 7901bc687df88d80c9337300ee7c44501235c3a8.
- Release: [NEX v0.1.0-alpha.97](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.97).
- Published APK: NEX-Android-v0.2.0-rc1-optimized.apk (27,281,642 bytes), with SHA256SUMS.txt.
- Run #97 passed the native friend-cache test, Android unit tests, optimized Android APK build, artifact upload, and release publication.
- Pinned Eden source: v0.2.0-rc1, commit 8e373eb714aa01117ec885680477501cdb572112.
- Android application ID: com.nex.nx.
- OAuth callback: com.nex.nx://oauth/callback.
- NEX public OAuth Client ID: nxc_yAdR-14bOEq7.
- Development branch: nex/eden-v0.2.0-rc1-nextendo.

The code changes described below are a new revision after run #97. They must pass their own Actions build before a new APK can be called verified.

## Implemented in NEX

### Android identity and account integration

- NEX uses its own OAuth Authorization Code + PKCE (S256) client registration, callback, Android Keystore-backed session storage, account panel and friends UI.
- The requested scope set is limited to identity, friends, presence and game.matchmaking.
- OAuth access tokens and nx2. game tokens are kept as separate credential types.
- The game-token response parser requires the documented nx2. prefix, positive PID, username and positive expires_in. The token is treated as opaque; NEX does not invent or decode its internal signature.
- The account presence publisher now selects only an unexpired OAuth access token with the presence scope. It never substitutes an nx2. game token for the documented OAuth credential.
- The HOME Menu publisher no longer fabricates an AppField payload. AppField remains empty until a real, correctly serialized value is available.

### Native friend cache and nn::friends HLE

- Android transfers a bounded friend snapshot to a mutex-protected NEX-owned native cache.
- Each entry may contain PID, explicit NetworkServiceAccountId (NSA), username, presence status, current application ID and bounded AppField.
- Game-facing list/profile handlers now expose only entries with an explicit non-zero NSA. PID is not silently substituted for NSA.
- Switch account UUIDs and last-seen timestamps are not synthesized from PID. Unknown fields remain zero.
- Presence status is preserved as supplied; ordinary online status is not promoted to OnlinePlay. A title ID is written only when the friend is explicitly in-game and the API supplied a valid title ID.
- The public account-server friend response currently has no NSA or Switch UUID field. Therefore, the qLaunch/game-facing list remains empty for friends whose response has no NSA. This is an intentional fail-closed behavior, not a claim that the social API is complete.
- The modern qLaunch V2 record sizes and command registration still need guest-level validation in Eden/qLaunch. A passing native build is not enough.

### Protocol primitives

- A strict PRUDP-Lite frame encoder/decoder handles the 12-byte header, option blocks, payload lengths, concatenated frames and incomplete stream reads.
- A strict RMC envelope codec handles request, success-response and error-response headers.
- The SMM2 DataStore protocol identifier (115) and method identifiers are recorded from the public DataStore_SMM2 schema.
- A stream decoder clears any incomplete frame when closed.
- These are parsing/serialization primitives only. NEX does not yet establish a PRUDP-Lite session, authenticate a game service, implement reliability windows, or serialize SMM2 method payloads.

### NAT primitives

- A 16-byte big-endian NNCS probe codec follows the public Nextendo NNCS implementation.
- A conservative two-vantage tracker classifies equal mappings, changed ports and ambiguous observations.
- Closing the tracker clears observations and prevents reuse.
- The tracker owns no UDP socket. It does not preserve an emulator socket mapping, perform peer discovery, or hole-punch a P2P connection.

## Current public-contract audit

### Developer documentation versus public account-server source

The current [Nextendo developer documentation](https://wiki.nextendo.network/developers) describes a third-party OAuth flow with identity/friends/presence scopes, a game-token exchange, and a separate NEX game token. The documentation says the game token is placed in the BAAS id_token under the nnex claim, following the official emulator's implementation.

The public [nextendo-account source](https://github.com/NextendoNetwork/nextendo-account/tree/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a), last updated 12 October 2026, does not match all of that contract:

- Its public OAuth scope allow-list contains identity and friends, not presence or game.matchmaking.
- Its public third-party token exchange handles authorization_code but does not return a refresh_token.
- Its /api/nex-token handler verifies a web-session token rather than the public OAuth access token and does not include expires_in in the response.
- Its /api/friends and /api/presence handlers accept a web-session token or nx2. game token, not the OAuth access token described by the wiki.
- Its friend response contains PID and presence information but does not expose an NSA or Switch account UUID.

NEX follows the published developer documentation where a documented contract exists. It does not silently switch to a private web-session flow or derive missing IDs. The production deployment may be ahead of the public source, but that has not been independently verified.

### BAAS, nnex claim and client attestation

- The developer documentation requires the NEX game token in the BAAS nnex claim and points to the official emulator's ManagerServer implementation for the exact construction.
- The public documentation does not enumerate a complete NEX-specific claim contract (including the exact claim value, issuer, audience, key identifier, key URL, signing algorithm and expiry rules).
- The NEX-owned private signing key and the registration of its corresponding public key with the Nextendo JWKS service have not been supplied.
- The Nextendo changelog describes mandatory cryptographic client attestation for online clients. NEX has its own public Client ID, but no NEX-issued attestation signing material or verified signing procedure.
- No Den2 or other emulator key, attestation, token or session is used.

**Blocker:** Nextendo must issue the NEX-specific signing/attestation material and confirm the exact BAAS claim, JWT, JWKS and API request-signature contract. No JWT is fabricated or shipped.

### SMM2 NEX/PRUDP service

The public NintendoClients reference documents PRUDP-Lite framing and RMC envelopes. The public DataStore_SMM2 schema identifies protocol 115 and methods such as register_user (47), get_users (48), sync_user_profile (49), get_courses (70) and search_courses_latest (73).

The public Nextendo NEX repository is a server implementation; it is not a complete client handshake specification for every title. The public sources do not provide a verified SMM2 client endpoint/access-key pair and complete NEX authentication/matchmaking flow for this NEX registration.

**Blocker:** the SMM2 service hostname/port, NEX access key, service negotiation, authenticated session requirements, and method payload contract must be confirmed before a live client is connected.

### Title DNS and TLS/SNI

- The Nextendo deployment guide requires title-scoped hostname routing, certificates valid for the original retail hostnames, and correct SNI selection.
- The guide uses placeholders and does not publish a complete SMM2 hostname-to-service mapping or the NEX deployment's server addresses.
- NEX does not add guessed hostnames, IP addresses, wildcard redirects or disabled certificate checks.

**Blocker:** Nextendo must provide or confirm the SMM2 hostname matrix, destination routing, certificate trust requirements and NEX service addresses. Platform TLS certificate validation remains enabled.

### NAT, UDP mapping, discovery and P2P

- The public Nextendo NNCS implementation documents a 16-byte probe and reports the observed external port/address and server address. Its deployed ports are title-specific; the public implementation inspected is not evidence that the same ports apply to SMM2.
- The deployment guide requires observations from two distinct public vantage addresses.
- The new codec/tracker does not bind the emulator's UDP socket, preserve its NAT mapping, run a keepalive schedule, discover peers or execute hole-punching.
- No SMM2-specific NNCS endpoints, probe ports, discovery messages or P2P lifecycle have been verified.

**Blocker:** obtain the SMM2 NAT-check and P2P contract, two approved vantage endpoints, probe/keepalive timing, session-discovery flow and socket-lifetime requirements.

## Test status and release gate

Run #97 passed the native friend-cache test and Android unit tests for the code that was present in that run. The next revision adds host tests for HLE record serialization and explicit-NSA filtering, plus Android tests for PRUDP-Lite, RMC, NAT parsing/state cleanup and OAuth presence-token selection; none of those new tests is covered by run #97.

Required before claiming in-game online support:

1. BAAS JWT construction and claim/signature validation using NEX-issued key material.
2. Verified account API contract, including refresh, presence, game-token exchange, NSA and UUID mapping.
3. Guest-visible HLE IPC tests for legacy and modern qLaunch friend records.
4. PRUDP-Lite handshake, authentication, reliability, RMC payload and SMM2 service integration tests.
5. Title-specific DNS/TLS/SNI tests with certificate validation enabled.
6. NAT state, live UDP mapping retention, keepalive, peer discovery, hole-punching and cleanup tests.
7. End-to-end testing with two NEX accounts, two Android devices and a Nextendo-supported game.

Until those checks pass, NEX in-game multiplayer remains **not implemented / not verified**.
