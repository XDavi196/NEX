# NEX — Nextendo external blockers and integration contract

Updated: 8 November 2026.

This document separates NEX-owned implementation work from values and service behavior that only Nextendo can confirm. The NEX Android application remains a public OAuth client:

- Application ID: `com.nex.nx`
- OAuth redirect: `com.nex.nx://oauth/callback`
- Public Client ID: `nxc_yAdR-14bOEq7`
- OAuth flow: Authorization Code + PKCE (S256)
- Requested scopes: `identity friends presence profil game.matchmaking emulator.status`

Do not add a client secret, another emulator's credential, or any service-side secret to the APK.

## 1. BAAS implementation decision: Citron format, authorized signer still required

**Decision: use the Citron BAAS format in NEX.** The current code already implements `NextendoCitronBaasSigner`, `NextendoCitronBaasProfile` and the Citron claim provider. DEN is retained only as a protocol reference; its ACC implementation uses the same fixed RS256/JWKS identity but can fall back to placing an OAuth session token in `nnex` when `/api/nex-token` fails. NEX deliberately rejects that fallback because Nextendo requires a real `nx2.` game token. The profile uses the Citron values iss=https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com, aud=ed9e2f05d286f7b8, jku=https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com/1.0.0/certificates and kid=nextendo-baas-key-1. The payload includes the Citron Nintendo-device metadata and binds the exact Nextendo-issued nx2. game token in nnex. It never places the OAuth access token in nnex.

**The private signing key is not in the public Citron source.** Citron loads it from the operator-provided NEXTENDO_BAAS_SIGNING_KEY environment variable or nextendo_baas.pem; its locally auto-generated fallback only creates a different RSA identity and must not be assumed to match the live JWKS. The Nextendo baas-jwks service publishes the public JWK corresponding to the same private key used by the signer. A valid signature therefore requires the matching private key and the matching kid/public JWK pair.

NEX adds **Importar chave BAAS** to the Nextendo menu. The user must select a PEM supplied through an authorized Nextendo channel. NEX checks that its derived RSA public key matches the exact nextendo-baas-key-1 entry at the HTTPS JWKS endpoint, using the configured exact-host route and ordinary TLS verification. Only after that check does it encrypt the PEM with Android Keystore AES-GCM and activate the signer. NEX does not generate a replacement key, expose the PEM, copy a DEN/Citron private key from another installation, or fall back to an OAuth token when the nx2. game token is unavailable.

This format compatibility does **not** by itself authorize the NEX application to use Citron's BAAS identity. The Nextendo founder previously stated that third-party client-generated BAAS tokens are not accepted and that per-installation JWK registration is unavailable. The Citron integration therefore uses only the fixed Nextendo JWKS identity; the operator must still confirm that this signer may be used by NEX's public Client ID nxc_yAdR-14bOEq7 and that the NEX API credential/attestation contract permits it. A successful local JWKS match proves only that the correct public key is published; it does not prove that a game accepts the resulting id_token or completes LoginEx.

DEN is not an alternative trust mechanism: it uses RS256, the same fixed issuer/audience/JWKS URL/kid and an externally supplied PEM. Its persisted auto-generated fallback creates a different key that will not match the published JWKS. Copying the DEN signer would therefore not remove the key requirement; copying its OAuth-token fallback would make the `nnex` claim invalid.

**Validation status:** the signer, encrypted PEM import path and live-JWKS key-match gate are implemented; automated signature/profile/JWKS tests run in CI. Live BAAS acceptance, ACC/AuthenticationInfo, LoginEx and secure-session are still unverified because the required Nextendo authorization/key is not available to this client. The NEX code never silently generates a substitute signer.

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

The NEX Android client separates its OAuth access token from the server-issued `nx2.` game token. The live Developer Documentation is the intended public-client contract; the public account source has several older endpoint/token discrepancies documented in `NEXTENDO_ANDROID.md`. OAuth access tokens remain confined to the OAuth/userinfo and `/api/nex-token` exchange. Implemented client features include:

- Friends: list/refresh, add by Friend Code, accept/decline requests and remove; game-facing friend cache/IPC and real guest `UpdateUserPresence` capture.
- Presence: online/playing/offline heartbeat; actual 0xC0 title AppKeyValueStorage forwarded as Base64; DEN2's SMM2/Splatoon private-battle `InGame` fix is gated by confirmed two-vantage NAT; ARMS `JoinMode` 1–4 maps to joinable presence.
- Account/profile: profile read, username and console nickname updates, JPEG/Mii import, public gallery avatar listing/selection, and avatar/profile field preservation.
- History and transfer: real Eden playtime sync, history read, BCAT seed download/export and SHA-256. Cloud-save upload/download is not offered: Nextendo removed the cloud-save service in September 2026.
- Diagnostics: backend health ping, online eligibility check, public online-counts feed, exact-host DNS/TLS routing and payload-blind PRUDP/NAT diagnostics.

The public server source contains account/profile/history/BCAT/save/health/gallery handlers, but the current Nextendo documentation marks cloud saves as removed and reserves account management to official applications. A route remaining in source is not proof that the live deployment accepts a third-party client. DEN2 also contains calls for `/api/my-lobby`, `/api/recent-players`, `/api/report-player` and `/api/avatar?pid=...`, but these handlers are not registered in the public `nextendo-account` server route table checked for this integration. NEX therefore displays the lobby/recent-player functions as unavailable rather than sending speculative requests. Player reporting and PID avatar download remain blocked until Nextendo publishes/authorizes those routes. The NZP player-count call belongs to the separate NZP game service, not Nextendo, and is not represented as a Nextendo feature.

## 2.2. Cloud-save service status

The current Nextendo cloud-save guide says the service was **removed in September 2026**. NEX therefore no longer exposes cloud-save upload/download actions. BCAT schedule bundles are a separate service and remain available as public title data where the server has published a bundle. No cloud-save feature should be advertised as active until Nextendo announces a replacement.

## 3. SMM2 production routing and matchmaking

The NEX source contains the public SMM2 profile, LoginEx/ticket codecs, PRUDP-Lite state machine and a standalone auth-to-secure session harness. The harness is not yet integrated with the guest game's native socket lifecycle. The live deployment values are not published in the public source repositories.

The current Nextendo game-compatibility page lists Super Mario Maker 2 as **Experimental / In preparation**, 70% supported on version `3.0.3`, with **matchmaking and presence untested**. Its published progress is for Course World features; it is not a promise that versus rooms or peer sessions work. This is an independent server-side blocker: a perfect NEX client cannot create working SMM2 rooms until Nextendo enables and validates matchmaking.

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

NEX selects Citron BAAS and requires an operator-authorized PEM whose public key matches the live Nextendo JWKS; no local/random fallback is permitted. The game-visible cache remains empty until a valid unexpired `nx2.` token and the authorized signer are available. Even then, a JWKS match does not solve client attestation or prove live LoginEx acceptance. NEX cannot invent official client credentials, production IPs, certificate deployment data or missing SMM2 server matchmaking.

The previous per-installation JWK registration proposal is retired following the founder's explicit response. The file docs/NEXTENDO_BAAS_REGISTRATION_REQUEST.md is retained only as a historical record and must not be sent as a current request.
