# NEX — Nextendo external blockers and integration contract

Updated: 8 November 2026.

This document separates NEX-owned implementation work from values and service behavior that only Nextendo can confirm. The NEX Android application remains a public OAuth client:

- Application ID: `com.nex.nx`
- OAuth redirect: `com.nex.nx://oauth/callback`
- Public Client ID: `nxc_yAdR-14bOEq7`
- OAuth flow: Authorization Code + PKCE (S256)
- Requested scopes: `identity friends presence profil game.matchmaking emulator.status`

Do not add a client secret, another emulator's credential, or any service-side secret to the APK.

## 1. BAAS game authentication — explicitly blocked by Nextendo policy

NEX contains an RS256 JWT builder, an AuthenticationInfo DataHolder encoder and an Eden ACC cache bridge as protocol/test scaffolding. These components are not a production authentication path. The earlier per-installation Android Keystore/JWK proposal is superseded and must not be used to try to register client-generated keys.

The Nextendo founder explicitly told the NEX project:

- Client-generated public keys will not be registered in the Nextendo JWKS.
- Third-party RS256 BAAS tokens signed by client applications will not be accepted.
- The JWKS is not open to per-installation registrations.
- Game tokens are issued by Nextendo's own servers after the player signs in with a Nextendo account.
- There is currently no third-party client certification process and no third-party BAAS token contract, including a jku/claims specification, to implement.

This is a service-side trust restriction, not a missing Client ID or a missing Android Keystore setting. Do not generate a new client-held BAAS signing key, do not publish a locally generated JWK, do not send the retired per-installation registration request as a proposed solution, and do not copy DEN/Citron's local RSA fallback or any other emulator's signing material. NEX's ACC runtime must remain fail-closed and must not provide a client-generated BAAS JWT to the emulated game.

The prerequisite for future work is a new, official Nextendo server-side mechanism that issues or authorizes a game-ready credential for NEX. If the policy changes, Nextendo must provide the supported flow, how the server binds the credential to the NEX application/account/game token, the permitted claims, expiry/refresh contract, and the LoginEx/ACC integration requirements. Until then, BAAS/LoginEx game authentication cannot be completed by NEX.

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

The current public Nextendo compatibility registry lists Super Mario Maker 2 as **Experimental / In preparation**, 70% supported on game version `3.0.3`, with **matchmaking and presence untested**. This is an independent server-side blocker: even a complete NEX client transport cannot create working rooms until the deployed SMM2 service exposes and validates the required matchmaking path.

Nextendo must confirm or provide through an authorized developer channel:

- The production SMM2 auth hostname and IPv4 destination.
- TLS certificate/SNI coverage for that hostname and the expected certificate trust behavior inside Eden.
- The production secure StationURL host and port format returned by LoginEx; NEX will follow the server response rather than guess a secure endpoint.
- The negotiated auth and secure PRUDP virtual station types, ports and supported-function masks.
- Whether SMM2 is enabled for live LoginEx and secure-session testing on the public deployment.
- Which SMM2 RMC methods and payload schemas are implemented server-side for matchmaking, room creation/join/leave, course/session state, and player presence.
- Whether matchmaking and presence are actually enabled for the target game/version. Course World/DataStore support alone is not evidence that online rooms work.

The public profile currently associates the SMM2 title key `fdf6617f` with game-server ID `22306D00`; the corresponding candidate hostname is recorded in `NEXTENDO_SMM2_PROFILE.md`. The production IP, live certificate route and deployed matchmaking support remain unconfirmed.

## 3.1. Shared DNS routing now implemented in the NEX client

The Android account panel now exposes **Configurar rede**. It accepts the main Nextendo IPv4 and a second, distinct NAT-vantage IPv4; both values are encrypted at rest with Android Keystore (AES-GCM), and neither is compiled into the APK.

At emulator startup, NEX restores the saved configuration and installs exact-host DNS mappings in Eden's native resolver. The current registry covers the public game auth host pattern for SMM2, Mario Kart 8 Deluxe, Splatoon 2, Smash Ultimate, Luigi's Mansion 3, ARMS, Mario Tennis Aces, Super Mario Bros. 35, PAC-MAN 99, Tetris 99 and Animal Crossing: New Horizons, plus the exact DAUTH hosts listed in the online architecture. The same registry can configure OkHttp's DNS for the standalone PRUDP WSS session client, so it no longer has to rely on the phone's public DNS for those exact hosts. In both cases the URL hostname is preserved for TLS SNI and ordinary certificate validation. No wildcard Nintendo-domain redirect or trust-all TLS mode is installed.

**Important boundary:** the configured main IPv4 is currently used for these exact auth/DAUTH mappings. The second NAT-vantage IPv4 is securely stored but is not yet consumed by an automatically scheduled NNCS probe; the SMM2 NNCS hostnames, ports and probe lifecycle are still unconfirmed. The DNS installer does not attach the standalone PRUDP coordinator to the game's native socket lifecycle. The game-host registry is routing metadata, not proof that every listed game has a complete Nextendo server, title key, matchmaking contract or P2P path. SMM2 remains the first validation target.

The user must obtain the two correct deployment IPv4 values from Nextendo before using this setting. Documentation example addresses, the Android device IP, or the SMM2 auth hostname are not substitutes for the actual server and NAT vantage addresses.

## 4. NNCS, NAT and P2P

NEX has a 16-byte NNCS probe codec, a two-vantage mapping classifier, a bounded one-shot UDP probe client and a short-lived native UDP socket keeper. The probe client reuses one local socket for two explicitly configured endpoints and validates both response sources. The keeper preserves a game's UDP mapping across a close/reopen sequence. Neither component completes peer discovery, matchmaking signaling or P2P hole-punching. The SMM2 probe runner is not wired into the game's network lifecycle.

Required title-specific information:

- SMM2 NNCS hostnames and UDP ports for both probe vantage points, including confirmation that message type 101 and the current 16-byte exchange match the deployed SMM2 service.
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

NEX now has an Android Keystore-backed network configuration screen and an exact-host multi-title auth/DAUTH DNS installer. This closes the client-side plumbing for main-server hostname routing, but it does not supply production addresses or complete the title login path.

NEX can continue implementing and testing the already-owned DataHolder serialization, ACC cache delivery boundary, exact-host DNS routing, TLS/SNI handling, guest socket integration and NAT client logic. NEX cannot invent BAAS trust, a third-party attestation credential, production IPs, certificate deployment data or missing server-side SMM2 matchmaking behavior. The client-side BAAS cache remains empty until Nextendo provides an approved server-issued game-authentication mechanism.

The previous per-installation JWK registration proposal is retired following the founder's explicit response. The file docs/NEXTENDO_BAAS_REGISTRATION_REQUEST.md is retained only as a historical record and must not be sent as a current request.
