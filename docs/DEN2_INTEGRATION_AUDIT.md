# NEX — DEN2 integration audit and implementation requirements

Updated: 8 November 2026.

This document records what was verified in the public [Dmr77680/Den2](https://github.com/Dmr77680/Den2) source and how NEX can implement equivalent supported behavior while keeping its own Nextendo application identity.

## Identity boundary — NEX keeps its own client

NEX must continue using:

- Android application ID: `com.nex.nx`
- Public OAuth Client ID: `nxc_yAdR-14bOEq7`
- OAuth redirect: `com.nex.nx://oauth/callback`
- OAuth flow: Authorization Code + PKCE (S256)
- No embedded client secret.

DEN's source declares a different client ID (`nxc_z_fetCuVO1qx`) in `src/web_service/nextendo_api.cpp`. That value is evidence about DEN's own registration only. It must not be copied into NEX. The NEX branch already uses its own Client ID in `NextendoAuthClient.kt`.

NEX must also not reuse DEN's OAuth refresh/access tokens, local account file, private signing material, attestation material, or service credentials. A user should sign in to NEX through NEX's own registered OAuth flow. The NEX encrypted backup is for NEX-created backups only; it is deliberately not a parser for another application's bearer-token file.

## What the DEN source demonstrates

The public source contains these separate pieces:

| DEN source | Observed responsibility | NEX approach |
|---|---|---|
| `src/web_service/nextendo_api.cpp` | Browser OAuth/PKCE, app Client ID, token exchange/refresh, account API calls and `/api/nex-token` | Keep the NEX-owned Kotlin client and NEX Client ID. |
| `src/common/nextendo_account.cpp` | Local account/session persistence, PID and player identity | NEX already stores its session encrypted with Android Keystore. |
| `src/core/hle/service/acc/acc.cpp` | Game-facing ACC BAAS id_token construction and `nnex` claim | Do not copy the local-signing fallback; see the security decision below. |
| `src/core/hle/service/ssl/ssl_backend_openssl.cpp` and `src/core/hle/service/sockets/sfdnsres.cpp` | TLS/SNI and Nextendo-specific DNS/socket compatibility | Port only protocol behavior that is verified against the official service and keep certificate verification enabled. |
| `src/core/hle/service/friend` and the Android friends UI | Game-facing friend records and the in-app social view | Use NEX's own friend cache, serializers, UI, and verified PID/NSA mappings. |

DEN's release notes describe obtaining `nextendo_account.txt` using the Orange app. DEN's README warns that the file contains a bearer token. That file is not a safe substitute for NEX OAuth authorization and must not be imported blindly or committed/shared.

## What is already present in NEX

The current NEX branch already contains:

- Own OAuth Authorization Code + PKCE flow and callback validation.
- NEX Client ID and application header.
- Encrypted Android Keystore session storage and serialized refresh-token rotation.
- Separate retrieval/parsing/storage of the server-issued `nx2.` game token.
- NEX's own encrypted session backup and restore format.
- Account profile, read-only friends view, presence publishing and public network statistics.
- Native friend-cache bridge and qLaunch friend-service serializers/handlers.
- Exact-host DNS route support, TLS/SNI diagnostics, a PRUDP-Lite test harness and NNCS/NAT probe components.
- An ACC BAAS token cache and a fail-closed runtime boundary that does not deliver a locally signed token to the guest unless an approved NEX signer/claim contract is provisioned.

Relevant NEX code is under `integration/android/src/main/java/com/nex/nx/nextendo/`, native overlay components are under `integration/android/src/main/cpp/`, and the Eden integration is applied by `scripts/apply-eden-overlay.py`.

## Important security decision: do not copy DEN's local BAAS signer

The inspected DEN `acc.cpp` contains code that:

1. Constructs an RS256 BAAS JWT locally.
2. Places a game token in the `nnex` claim.
3. Loads a key from `NEXTENDO_BAAS_SIGNING_KEY` or `nextendo_baas.pem`.
4. If no key is available, generates and persists a local RSA-2048 key.

The code's presence does not prove that a locally generated key is published, registered or accepted by Nextendo. The source alone cannot establish how a production DEN build obtains a trusted signing key.

The Nextendo founder explicitly told the NEX project that client-generated public keys cannot be registered, third-party RS256 BAAS tokens cannot be accepted, the JWKS is not open to per-installation registrations, and no third-party certification/token contract is currently available. Therefore:

- NEX must not generate a replacement BAAS key and pretend it is trusted.
- NEX must not copy DEN's private key, signing key, attestation or application credentials.
- NEX must not hardcode DEN's BAAS issuer, audience, JKU, key ID or claim values as if they were an approved NEX contract.
- Keep BAAS/ACC game authentication fail-closed until Nextendo changes its policy and provides an official, server-issued or explicitly approved third-party mechanism.

This is a service-side trust requirement, not a missing Kotlin screen or a missing Client ID.

## Remaining requirements for actual in-game multiplayer

### 1. Official game-authentication path

Required from Nextendo:

- A supported method for a third-party NEX client to obtain the game-facing BAAS identity/token, or an official server-side exchange that returns a game-ready credential.
- Confirmation of how the server binds that credential to the NEX account/PID and the `nx2.` game token.
- The exact permitted LoginEx/ACC contract and token lifetime/refresh behavior.

The founder's current response says this is not available to third-party clients today. Until that changes, this stage is blocked and must not be simulated.

### 2. Client/API attestation, if the protected endpoints require it

Required from Nextendo:

- Whether NEX's registered public OAuth client may call `/api/nex-token`, friends and presence without official-emulator attestation.
- If attestation is required, the approved third-party format and the NEX-specific provisioning/rotation process.

NEX must not borrow the official emulator's or DEN's private attestation key.

### 3. Live title endpoint configuration

For the first target, Super Mario Maker 2:

- Production auth hostname and route IPv4.
- TLS certificate/SNI behavior for that hostname.
- Actual secure StationURL returned by LoginEx and negotiated PRUDP station parameters.
- Confirmation that the deployed SMM2 service currently accepts live LoginEx and secure sessions.

Never substitute a development loopback address, another game's endpoint, a sample IP, or a guessed port.

### 4. Matchmaking and NAT/P2P

Required to verify with Nextendo's SMM2 service:

- The RMC methods and payloads actually supported for room creation, joining, leaving and player/session state.
- SMM2 NNCS/NAT check hostnames, ports, packet contract, two-vantage behavior and keepalive timing.
- Peer discovery/signaling data and UDP hole-punch lifecycle.
- Confirmation that SMM2 matchmaking is enabled server-side for the tested game version.

NEX's standalone PRUDP/NAT test components and packet observer are not proof of a live game session. The PRUDP coordinator still needs to be connected to the guest game's real socket/lifecycle path and verified on two devices.

## What is needed from the project owner

No DEN Client ID, DEN token file, password, private key, client secret or private attestation file is needed.

The only owner-side checks are:

1. Confirm in the Nextendo Developer Portal that NEX's own Client ID `nxc_yAdR-14bOEq7` and exact redirect `com.nex.nx://oauth/callback` remain active, and verify which scopes are actually granted to the test account.
2. Provide only token-free diagnostics from a NEX test: build/commit, game title/update version, UTC time, endpoint hostname/path, HTTP status or game error code, and the stage that failed.
3. Keep using NEX's own browser sign-in. Do not send `nextendo_account.txt`, OAuth tokens, `nx2.` tokens, BAAS JWTs, authorization codes, PKCE verifiers or passwords in chat.

## Implementation status

The account/social client layer is already present in NEX and can continue to be improved independently. The missing BAAS trust contract is currently denied by Nextendo, so a code-only port of DEN's local JWT signer would not be a legitimate or verifiable way to enable multiplayer. The remaining game-network pieces must be finished and tested only after the corresponding official service values and game-authentication path are available.
