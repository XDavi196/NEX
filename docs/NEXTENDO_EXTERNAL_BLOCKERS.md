# NEX — Nextendo external blockers and integration contract

Updated: 8 November 2026.

This document separates NEX-owned implementation work from values and service behavior that only Nextendo can confirm. The NEX Android application remains a public OAuth client:

- Application ID: `com.nex.nx`
- OAuth redirect: `com.nex.nx://oauth/callback`
- Public Client ID: `nxc_yAdR-14bOEq7`
- OAuth flow: Authorization Code + PKCE (S256)
- Requested scopes: `identity friends presence profil game.matchmaking emulator.status`

Do not add a client secret, another emulator's credential, or any service-side secret to the APK.

## 1. BAAS / ACC game authentication: fail-closed

The official [Developer Documentation](https://wiki.nextendo.network/developers) describes a separate, server-issued `nx2.` game token obtained with the `game.matchmaking` permission. It lasts 24 hours and must be bound as the string-valued `nnex` claim in the BAAS token sent with game LoginEx. The public [Nextendo NEX core](https://github.com/NextendoNetwork/nextendo-nex/blob/main/authbinding.go) confirms that the server extracts `nnex` from a JWT embedded in LoginEx extraData.

Those sources do **not** publish a complete client-side BAAS signing contract for third-party emulator clients. The retired [BAAS registration proposal](NEXTENDO_BAAS_REGISTRATION_REQUEST.md) records Nextendo's rejection of third-party client-generated RS256 tokens and per-installation JWK registration. Therefore NEX does not ship a client-side BAAS JWT builder, PEM import, Citron/DEN signer, generated JWK registration flow or OAuth-token fallback. The Eden ACC bridge remains present but its token cache is always cleared; NEX does not send a fabricated BAAS token.

NEX requires Nextendo to provide a supported NEX-specific game-authentication flow, such as a server-issued BAAS id_token or an explicitly approved exchange. The required contract must specify the exact JWT claims and types, issuer/audience, PID binding, expiry, signature verification, key/attestation model and how the result is delivered to ACC/LoginEx. Until then, the game-authentication boundary remains disabled.

Never import another emulator's account file, OAuth session, refresh token, game token, private key, JWK, client secret or attestation material. NEX sign-in is exclusively its own public OAuth Authorization Code + PKCE flow; encrypted NEX backups are the only supported session restore format.

## 2. Client attestation and OAuth/API contract

The public developer documentation requires public Android applications to use PKCE and says unregistered clones are refused. The official Ryujinx-Nextendo README and its v1.8.7 changelog state that the official emulator signs API requests with an application-specific private key and that client attestation became mandatory. **The current NEX code sends `X-Nextendo-Client-Id` but does not implement a cryptographic client-attestation signature.** This is an unresolved server authorization blocker, not a missing JWT claim.

Nextendo must confirm:

- Whether the NEX public Client ID is allowed to call `/api/nex-token`, friends, profile and presence endpoints without the official emulator's attestation signature.
- Whether NEX's configured Client ID is registered and explicitly allowed to receive the `game.matchmaking` permission and online access.
- A NEX-specific client-attestation credential and the exact signing contract: algorithm, headers, canonical request bytes, timestamp/nonce rules, key registration, rotation and revocation.
- Whether the production API currently accepts the portal scopes `presence`, `profil`, `game.matchmaking` and `emulator.status`, and the exact response contract for each endpoint.
- Whether `GET /api/nex-token` accepts the documented OAuth bearer token and returns `nex_token`, `pid`, `username`, `friend_code` and `expires_in`.
- Whether friend data exposes the stable player-to-`NetworkServiceAccountId` mapping expected by qLaunch, or explicitly confirms the PID-to-NSA mapping used by the reference emulator.

NEX must continue using the public PKCE flow. Do not replace it with a private web-session login, reuse official Ryujinx/Citron private keys, or fabricate attestation headers. Without a credential issued for NEX, the client cannot satisfy an attestation gate.

## 2.1. Nextendo account features ported from DEN2

The NEX Android client separates its OAuth access token from the server-issued `nx2.` game token. Per the live Developer Documentation, `POST /api/presence` uses the OAuth access token plus the `presence` scope; `nx2.` is reserved for game authentication and the BAAS `nnex` claim. The public account source has older endpoint/token discrepancies documented in `NEXTENDO_ANDROID.md`. Implemented client features include:

- Friends: list/refresh, add by Friend Code, accept/decline requests and remove; game-facing friend cache/IPC and real guest `UpdateUserPresence` capture.
- Presence: online/playing/offline heartbeat using OAuth `presence` scope; actual 0xC0 title AppKeyValueStorage forwarded as Base64; DEN2's SMM2/Splatoon private-battle `InGame` fix is gated by confirmed two-vantage NAT; ARMS `JoinMode` 1–4 maps to joinable presence.
- Account/profile code includes read, console nickname, JPEG/Mii and gallery avatar paths, but live third-party account-management permissions are not confirmed; these calls must not be treated as available until Nextendo authorizes the `profil` scope and matching API routes.
- History and transfer: real Eden playtime sync and history read. BCAT seed download/export and SHA-256 remain separate public title-data operations. Cloud-save upload/download is not offered: Nextendo removed the cloud-save service in September 2026.
- Diagnostics: backend health ping, online eligibility check, public online-counts feed, exact-host DNS/TLS routing and payload-blind PRUDP/NAT diagnostics.

The public server source contains account/profile/history/BCAT/save/health/gallery handlers, but the current Nextendo documentation marks cloud saves as removed and reserves account management to official applications. A route remaining in source is not proof that the live deployment accepts a third-party client. DEN2 also contains calls for `/api/my-lobby`, `/api/recent-players`, `/api/report-player` and `/api/avatar?pid=...`, but these handlers are not registered in the public `nextendo-account` server route table checked for this integration. NEX therefore displays the lobby/recent-player functions as unavailable rather than sending speculative requests. Player reporting and PID avatar download remain blocked until Nextendo publishes/authorizes those routes. The NZP player-count call belongs to the separate NZP game service, not Nextendo, and is not represented as a Nextendo feature.

## 2.2. Cloud-save service status

The current Nextendo cloud-save guide says the service was **removed in September 2026**. NEX therefore no longer exposes cloud-save upload/download actions. BCAT schedule bundles are a separate service and remain available as public title data where the server has published a bundle. No cloud-save feature should be advertised as active until Nextendo announces a replacement.

## 3. SMM2 production routing and matchmaking

The NEX source contains the public SMM2 profile, LoginEx/ticket codecs, PRUDP-Lite state machine and a standalone auth-to-secure session harness. The harness is not yet integrated with the guest game's native socket lifecycle. The live deployment values are not published in the public source repositories.

The current Nextendo game-compatibility page lists Super Mario Maker 2 as **Experimental / In preparation**, 70% supported on version `3.0.3`, with **matchmaking and presence untested**. Its published progress is for Course World features; it is not a promise that versus rooms or peer sessions work. This is an independent server-side blocker: a perfect NEX client cannot create working SMM2 rooms until Nextendo enables and validates matchmaking.

Nextendo must confirm or provide through an authorized developer channel:

- The production SMM2 auth hostname and IPv4 destination.
- The exact production SNI hostname and whether the deployed SMM2 endpoint expects standard HTTP/1.1 or a title-requested NPLN/gRPC ALPN.
- The production secure StationURL host and port format returned by LoginEx; NEX will follow the server response rather than guess a secure endpoint.
- The negotiated auth and secure PRUDP virtual station types, ports and supported-function masks.
- Whether SMM2 is enabled for live LoginEx and secure-session testing on the public deployment.
- Which SMM2 RMC methods and payload schemas are implemented server-side for matchmaking, room creation/join/leave, course/session state, and player presence.
- Whether matchmaking and presence are actually enabled for the target game/version. Course World/DataStore support alone is not evidence that online rooms work.

The public profile currently associates the SMM2 title key `fdf6617f` with game-server ID `22306D00`; the corresponding candidate hostname is recorded in `NEXTENDO_SMM2_PROFILE.md`. The production IP, live SNI route and deployed matchmaking support remain unconfirmed. The NEX DNS/TLS changes are client plumbing only; they do not prove that the public SMM2 service accepts the route or exposes working rooms.

## 3.1. Shared DNS routing now implemented in the NEX client

The Android account panel now exposes **Configurar rede**. It accepts the main Nextendo IPv4 and a second, distinct NAT-vantage IPv4; both values are encrypted at rest with Android Keystore (AES-GCM), and neither is compiled into the APK.

At emulator startup, NEX restores the saved configuration and installs exact-host DNS mappings in Eden's native resolver. The current registry covers the public game auth host pattern for SMM2, Mario Kart 8 Deluxe, Splatoon 2, Smash Ultimate, Luigi's Mansion 3, ARMS, Mario Tennis Aces, Super Mario Bros. 35, PAC-MAN 99, Tetris 99 and Animal Crossing: New Horizons, plus the exact DAUTH hosts listed in the online architecture. The same registry can configure OkHttp's DNS for the standalone PRUDP WSS session client, so it no longer has to rely on the phone's public DNS for those exact hosts. Eden records each exact redirected host/IP/service-port result. On TLS handshake, it verifies the connected peer matches the configured route, preserves game-provided SNI or restores it from that DNS record when absent, and sets HTTP/1.1 ALPN for normal Nextendo endpoints. NPLN/gRPC hosts may retain only h2/http/1.1 requested by the game. Redirected Nextendo connections force peer-chain verification and hostname verification against the original SNI name; the overlay never sets SSL_VERIFY_NONE or clears the hostname check. A CI test checks this invariant. No wildcard Nintendo-domain redirect or global trust-all TLS mode is installed.

**Important boundary:** the configured main and NAT-vantage IPv4 values are used only for exact-host routing; they do not set a public player address. The guest game now drives its own NNCS requests through the emulated BSD UDP sockets. NEX inspects the actual 16-byte replies in `BSD::RecvFromImpl` and only enables the outgoing Station URL rewrite after receiving valid message-type-101 replies from both remote ports `10025` and `10125`, on the same local game UDP port, with one matching public IPv4 and two distinct responder IPv4s. The separate Kotlin `NextendoNatProbeClient` is not used to configure the game mapping. This is protocol plumbing based on the DEN2 implementation; the production SMM2 NNCS endpoints and behavior still require validation. The game-host registry is routing metadata, not proof that every listed game has a complete Nextendo server, title key, matchmaking contract or P2P path. SMM2 remains the first validation target.

The user must obtain the two correct deployment IPv4 values from Nextendo before using this setting. Documentation example addresses, the Android device IP, or the SMM2 auth hostname are not substitutes for the actual server and NAT vantage addresses.

## 4. NNCS, NAT and P2P

NEX now captures NNCS observations from the guest game's real BSD UDP socket, following the receive-side integration used by DEN2. `BSD::RecvFromImpl` accepts only the 16-byte message-type-101 reply from remote source ports `10025` and `10125`; it records the responder IPv4, external mapped port and external IPv4, and requires both distinct responders to report the same public IPv4 on the same local guest UDP port before it enables the Station URL rewrite. A separate app-owned UDP socket is deliberately not used for this mapping because it would observe a different NAT port. The native UDP socket keeper also parks and re-adopts the guest's unconnected UDP socket for a short close/reopen interval, preserving that local port. The outgoing SSL-layer rewriter updates private addresses only in the known SecureConnection and MatchmakeExtension request methods and rebuilds the WebSocket/PRUDP/RMC lengths. These pieces reproduce the relevant client-side NAT plumbing; they do not provide matchmaking signaling, peer discovery, periodic keepalives or UDP hole-punching by themselves. The standalone `NextendoNatProbeClient` remains diagnostic-only and must not be used as the game's advertised mapping.

Required title-specific information:

- Confirmation that production SMM2 uses the NNCS message-type-101, 16-byte reply observed by the DEN2 client, and that remote source ports `10025` and `10125` identify the two deployed vantage points.
- Confirmation that the two SMM2 responders have distinct public IPv4 addresses and return a consistent external IPv4 for the same guest UDP mapping.
- Any SMM2-specific NNCS request timing, retry and keepalive requirements. NEX currently detects the two game-driven replies but does not send its own periodic probes or NAT keepalives.
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

NEX now has an Android Keystore-backed network configuration screen and an exact-host multi-title auth/DAUTH DNS installer. Supported DEN2 account features are now ported to the NEX public Client ID, with profile gallery selection and actual game presence capture. The public-server route gaps are called out in §2.1 rather than presented as implemented. This closes client-side account features and hostname plumbing, but it does not supply production addresses or complete the title login path.

NEX no longer contains a client-side BAAS signer, PEM import or JWK registration flow. The game-visible ACC cache is cleared even when a valid unexpired `nx2.` token exists, because Nextendo has not published an approved third-party signing/exchange contract for NEX. NEX cannot invent claims, issuer/audience, PID mapping, signing keys, attestation, production IPs, certificate deployment data or missing SMM2 matchmaking.

The previous per-installation JWK registration proposal is retired. The file docs/NEXTENDO_BAAS_REGISTRATION_REQUEST.md is a historical record only and must not be sent as a current request.
