# NEX — Nextendo external blockers and integration contract

Updated: 8 November 2026.

This document separates NEX-owned implementation work from values and service behavior that only Nextendo can confirm. The NEX Android application remains a public OAuth client:

- Application ID: `com.nex.nx`
- OAuth redirect: `com.nex.nx://oauth/callback`
- Public Client ID: `nxc_yAdR-14bOEq7`
- OAuth flow: Authorization Code + PKCE (S256)
- Requested scopes: `identity friends presence profil game.matchmaking emulator.status`

Do not add a client secret, another emulator's credential, or any service-side secret to the APK.

## 1. BAAS id_token and JWK publication

NEX already contains the RS256 compact-JWT builder, the `nnex` game-token claim, the `AuthenticationInfo` DataHolder encoder, and the Eden ACC cache bridge. The current runtime intentionally refuses to expose a token until a valid signer and claims configuration are supplied.

Important distinction: the public Ryujinx-Nextendo README documents a BAAS RSA signing key supplied at runtime (`NEXTENDO_BAAS_SIGNING_KEY`); it does not say that the private BAAS key must be issued by Nextendo. The public `baas-jwks` service publishes the matching public JWK from the same RSA key, with a matching `kid`. Therefore the unresolved issue is the NEX-specific key lifecycle and, especially, how the matching public key is made available at the `jku` URL used by the game's BAAS verification flow.

Before enabling the runtime provider, confirm:

1. Whether NEX may use its own RSA key pair and `kid`, or must use a Nextendo-managed key registration.
2. The exact `jku` hostname and path for NEX, and how the matching public JWK is registered or hosted.
3. The exact required JWT header and claims: `alg`, `kid`, `typ`, `jku`, `iss`, `aud`, `sub`/PID mapping, `hm`, `iat`, `exp`, and any title/version claims such as `tv`.
4. Whether the key can be generated and kept in Android Keystore, and whether the guest's BAAS request can reach the public JWK route through the configured exact-host DNS/TLS path.

The private RSA key must not be committed to Git, printed to logs, embedded in CI output, or copied from another emulator. A local throwaway key is not production-ready unless its matching public JWK is reachable and accepted by the title/service.

Public references:
- https://github.com/NextendoNetwork/Ryujinx-Nextendo
- https://github.com/NextendoNetwork/baas-jwks
- https://wiki.nextendo.network/developers

## 2. Client attestation and OAuth/API contract

The public developer documentation says distributed Android apps are public OAuth clients and must use PKCE without a client secret. The official Ryujinx-Nextendo README separately says official builds sign API requests with an application private key. The public documentation does not fully specify whether that cryptographic attestation gate applies to third-party public OAuth clients such as NEX.

Nextendo must confirm:

- Whether the NEX public Client ID is allowed to call `/api/nex-token`, friends, profile and presence endpoints without the official emulator's attestation signature.
- If attestation is required, the supported third-party signing format, headers, canonical request bytes, timestamp/nonce rules, key registration process and rotation/revocation procedure.
- Whether the production API currently accepts the portal scopes `presence`, `profil`, `game.matchmaking` and `emulator.status`, and the exact response contract for each endpoint.
- Whether `GET /api/nex-token` accepts the documented OAuth bearer token and returns `nex_token`, `pid`, `username`, `friend_code` and `expires_in`.
- Whether friend data exposes the stable player-to-`NetworkServiceAccountId` mapping expected by qLaunch, or explicitly confirms the PID-to-NSA mapping used by the reference emulator.

NEX must continue using the public PKCE flow. Do not replace it with a private web-session login or reuse official emulator attestation material.

## 3. SMM2 production routing and matchmaking

The NEX source contains the public SMM2 profile, LoginEx/ticket codecs, PRUDP-Lite state machine and a standalone auth-to-secure session harness. The harness is not yet integrated with the guest game's native socket lifecycle. The live deployment values are not published in the public source repositories.

Nextendo must confirm or provide through an authorized developer channel:

- The production SMM2 auth hostname and IPv4 destination.
- TLS certificate/SNI coverage for that hostname and the expected certificate trust behavior inside Eden.
- The production secure StationURL host and port format returned by LoginEx; NEX will follow the server response rather than guess a secure endpoint.
- The negotiated auth and secure PRUDP virtual station types, ports and supported-function masks.
- Whether SMM2 is enabled for live LoginEx and secure-session testing on the public deployment.
- Which SMM2 RMC methods and payload schemas are implemented server-side for matchmaking, room creation/join/leave, course/session state, and player presence.
- Whether matchmaking and presence are actually enabled for the target game/version. Course World/DataStore support alone is not evidence that online rooms work.

The public profile currently associates the SMM2 title key `fdf6617f` with game-server ID `22306D00`; the corresponding candidate hostname is recorded in `NEXTENDO_SMM2_PROFILE.md`. The production IP, live certificate route and deployed matchmaking support remain unconfirmed.

## 4. NNCS, NAT and P2P

NEX has a 16-byte NNCS probe codec, a two-vantage mapping classifier and a short-lived UDP socket keeper. The socket keeper preserves an existing host UDP mapping across a close/reopen sequence. It does not run live probes or complete P2P hole-punching.

Required title-specific information:

- SMM2 NNCS hostnames and UDP ports for both probe vantage points.
- The public addresses returned by those two vantage points; they must be distinct for the classification to be meaningful.
- Probe request/response timing, retry and keepalive requirements for SMM2.
- Whether SMM2 uses the public NNCS flow documented for other titles or a different title-specific exchange.
- Peer-discovery and matchmaking signaling fields needed before UDP hole-punching.
- Cleanup behavior when a player leaves a room, loses network connectivity, changes account or closes the emulator.

Do not copy Mario Kart 8 ports into the SMM2 profile. Do not infer a second NAT address from the auth server IP.

## 5. Required end-to-end verification

A successful GitHub Actions build or a local PRUDP unit test does not prove live multiplayer.

The release gate is:

1. Two distinct NEX accounts are authorized and have valid, unexpired `nx2.` game tokens.
2. Two Android devices run the same NEX build and the same supported SMM2 game/update version.
3. Both guests obtain the BAAS token through ACC and complete the real LoginEx request.
4. Both complete the secure PRUDP connection using the StationURL and negotiated parameters returned by the service.
5. The game completes the documented RMC matchmaking operations.
6. NNCS returns two valid observations; peer discovery and UDP hole-punching succeed.
7. The players can enter and remain in the same in-game session, then leave cleanly.

For service-side support, send only a token-free diagnostic: exact UTC timestamp, app build/commit, game title and version, test PID(s), endpoint path/hostname, HTTP status or game error code, and the step where the flow stopped. Never send OAuth access/refresh tokens, `nx2.` tokens, BAAS JWTs, authorization codes, PKCE verifiers, private keys or passwords.

## Current engineering boundary

NEX can implement and test its Android Keystore signer, JWT/DataHolder serialization, ACC delivery, DNS route installation, guest socket integration and NAT client logic. NEX cannot truthfully fill production IPs, certificate deployment data, live `jku` registration, third-party attestation requirements or missing server-side SMM2 matchmaking behavior without confirmation from Nextendo or a verified live handshake.
