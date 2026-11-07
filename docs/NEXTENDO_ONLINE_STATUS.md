# NEX — Nextendo online implementation status

Updated: 7 November 2026.

A successful APK build proves that the configured Android source and native toolchain compile. It does not prove that the live account service, BAAS, SMM2 game service or multiplayer works.

## Verified build evidence

- Latest verified Android APK: [NEX v0.1.0-alpha.121](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.121), from successful [Actions run #121](https://github.com/XDavi196/NEX/actions/runs/42401376607).
- Direct APK: [Download alpha.121](https://github.com/XDavi196/NEX/releases/download/v0.1.0-alpha.121/NEX-Android-v0.2.0-rc1-optimized.apk). APK size 27,285,729 bytes; release includes `SHA256SUMS.txt`.
- Run #121 built source commit `88df712fa09c83b4a4b8fbd1d02af20eeac9ff1e`. Native tests, Kotlin tests, Android optimized APK build, artifact upload and prerelease publication all passed. This APK includes the BAAS builder and public SMM2 profile but predates the ACC bridge, LoginEx/ticket codecs and session coordinator.
- [Run #132](https://github.com/XDavi196/NEX/actions/runs/42405574408) passed the native friend-cache/BAAS-cache test and all Kotlin/Nextendo unit tests, and is still building the Android Optimized APK from commit `3359151b51fd88632fc642f2ead204ef669acac0`. It predates the AuthenticationInfo framing correction and account-switch cache clear; no APK from this run is verified.
- Runs #133 and #134 were automatically cancelled/superseded as newer commits entered the single-branch concurrency queue; these are not test failures.
- [Run #135](https://github.com/XDavi196/NEX/actions/runs/42406074897) is pending. It includes the corrected single-Buffer AuthenticationInfo DataHolder framing, explicit native `<algorithm>` include and immediate BAAS cache clearing on account switch.
- Current branch: `nex/eden-v0.2.0-rc1-nextendo`, head `4e21ddcf3f4aa7a29235b75ec49971bee4c8fa76`.
- Pinned Eden source: `v0.2.0-rc1`, commit `8e373eb714aa01117ec885680477501cdb572112`.
- Android application ID: `com.nex.nx`.
- OAuth callback: `com.nex.nx://oauth/callback`.
- NEX public OAuth Client ID: `nxc_yAdR-14bOEq7`.

A successful build proves that the selected source compiles and its automated tests pass. It does not prove that the live account APIs, BAAS signer, SMM2 server, or multiplayer path work end to end.

## Implemented in NEX

### Android account identity

- NEX uses its own public OAuth Authorization Code + PKCE (S256) client registration, callback, Android Keystore-backed session storage and account panel.
- Requested scopes now match the owner-provided portal URL exactly: `identity friends presence profil game.matchmaking emulator.status`.
- OAuth access tokens and \`nx2.\` game tokens are separate credential types.
- The game-token parser requires the documented \`nx2.\` prefix, positive PID, username and positive \`expires_in\`. The token remains opaque; NEX does not forge or decode its internal signature.
- Refresh-token rotation is serialized and the returned token set is persisted as a whole. If a refresh token is absent, NEX does not invent one.
- Presence publishing uses an unexpired OAuth access token with the \`presence\` scope and a 60-second heartbeat. It never substitutes the \`nx2.\` game token for the documented OAuth credential.
- Game friends are fetched separately with the \`nx2.\` token. The HOME Menu publisher does not fabricate AppField data.

### Runtime diagnostics

The account panel shows the exact scopes returned in the OAuth token response and whether `game.matchmaking` is present. Presence publishing and `GET /api/nex-token` keep separate safe result labels: accepted 2xx, HTTP 401/403/404, other HTTP status, missing scope, network error, session unavailable or response-contract error. The last game-token result is persisted inside the encrypted Android Keystore-backed session, so it remains available after closing and reopening NEX. Tokens, authorization codes and request headers are never included.

### Public Nextendo Network statistics

- The NEX account panel requests `GET /api/stats` without player credentials.
- The response parser accepts only the documented non-negative integer counters: `online_players`, `total_registered` and `games_online`.
- The optional request uses a short timeout; a stats endpoint failure does not block account sign-in or profile display.
- Unit tests cover the documented response shape and reject missing, malformed, non-integer or negative counters.

### Client application attestation

- The Nextendo reference-emulator changelog says v1.8.6 began requiring a registered application identity and required application headers for account/network endpoints; v1.8.7 added mandatory cryptographic client attestation and an update gate for older clients.
- NEX sends its own public OAuth Client ID in the application header, but it does not currently sign requests with a provisioned NEX attestation key.
- The public third-party Developer Documentation describes public OAuth/PKCE apps but does not publish the attestation signing algorithm, required headers, or whether the same attestation gate applies to third-party public clients such as NEX.
- Treat protected game-token and emulator endpoints as unverified until the NEX client-attestation contract and any required public-key registration are documented/provisioned. Do not invent a signature scheme or borrow another emulator's private key.

### ACC / BAAS id_token path

- A bounded native `NextendoBaasTokenCache` stores only a three-segment compact-JWS-shaped token (maximum 8 KiB); malformed input clears the cache. The cache is mutex-protected and returns defensive copies.
- The Eden overlay injects matching JNI methods and copies the cache header into Eden's ACC service.
- `IManagerForApplication::CheckAvailability` now reports whether the cache contains a token. `LoadIdTokenCache` and the deprecated async interface return the exact compact-JWT bytes. They return a zero byte-count if no token exists or the guest output buffer is too small; the previous fake 0x100-byte zero-filled result is removed.
- `NextendoBaasRuntime` connects the RS256 builder and `AuthenticationInfo` DataHolder serializer to the ACC cache only when a NEX-specific provisioned signing provider exists and the current `nx2.` game token is valid. It clears ACC state on logout, absent/expired token, missing signer or invalid claims.
- No production signer/provider is installed in this branch. The bridge is implemented and fail-closed, but the guest receives no BAAS token until the NEX key/JWKS, `kid`, `iss`, `aud`, `sub`/PID and any required attestation are provisioned.
- Eden's local profile UUID remains the offline fallback. For a linked Nextendo account, the game-facing ACC NetworkServiceAccountId is the persistent Nextendo PID, matching the reference emulator's account binding. A Switch UUID is not derived from PID.

### Native friend cache and nn::friends HLE

- Android transfers a bounded friend snapshot through JNI to a mutex-protected NEX-owned native cache.
- Each entry may contain PID, explicit NetworkServiceAccountId (NSA), username, presence status, current application ID and bounded AppField.
- Game-facing list/profile/detail/presence handlers and qLaunch V2 serializers are wired to that cache in the Eden overlay.
- Records are exposed only when the API provides an explicit non-zero NSA. PID is not silently substituted for a friend's NSA.
- Switch account UUIDs and last-seen timestamps are not synthesized. Unknown fields remain zero.
- Presence status is preserved as supplied. Ordinary online status is not promoted to OnlinePlay, and a title ID is written only when the friend is explicitly in-game and the API supplied a valid title ID.
- The public friend response may omit NSA and Switch UUID fields. Nextendo's reference emulator explicitly binds its persistent player PID to the Switch NetworkServiceAccountId. NEX now follows that same service-specific mapping when NSA is absent, while preserving a valid explicit NSA if one is returned. Switch UUID and last-seen fields are still not synthesized.
- The C++ host test validates cache bounds, duplicate filtering, explicit-NSA filtering and qLaunch V2 record serialization. Guest-visible IPC behavior still needs validation inside Eden/qLaunch and a supported game.

### PRUDP, RMC and SMM2 protocol primitives

- A strict PRUDP-Lite frame codec handles the 12-byte header, option blocks, payload lengths, concatenated frames and incomplete stream reads.
- `NextendoPrudpLiteAuthenticator` implements the public Nextendo Lite CONNECT proof (MD5 of the runtime-supplied title access key, then HMAC-MD5 over that digest plus the server 16-byte connection signature).
- `NextendoPrudpLiteSession` is a deterministic client state machine for SYN, SYN+ACK, CONNECT, CONNECT+ACK, reliable DATA, single/multi ACK, duplicate suppression, fragment assembly, PING and DISCONNECT.
- `NextendoPrudpLiteWebSocketTransport` adapts the state machine to binary OkHttp WSS frames, retaining platform TLS hostname and certificate verification.
- The RMC envelope codec handles request, success-response and error-response headers. Outbound client calls are restricted to RMC requests.
- The SMM2 DataStore protocol identifier (115) and public method identifiers are recorded from the public schema.
- Unit tests cover the HMAC proof vector, PRUDP handshake/reliability, fragmentation, RMC framing, LoginEx extraData framing, LoginEx response decoding, client-ticket decryption, secure CONNECT payload construction and the secure server check response.

The public SMM2 profile records title key `fdf6617f`, NEX version `40000`, auth WSS `:443`, secure default `:60007`, auth PRUDP minor `5`, secure minor `0` and runtime StationURL parsing. The title key is public per-title material, not a private server credential.

`NextendoSmm2LoginCodec` builds TicketGranting protocol `0x0A`, method `2` requests with the username NEX String followed by the raw `AuthenticationInfo` DataHolder. `NextendoSmm2TicketCodec` parses the LoginEx response, decrypts the outer ClientTicket using the one-time source key, keeps the inner server ticket opaque, constructs the secure CONNECT buffers containing the encrypted PID/CID/check tuple and verifies the `check + 1` reply. `NextendoSmm2SessionClient` coordinates auth WSS → LoginEx → secure StationURL WSS with explicit runtime host and station parameters.

The session coordinator is an explicit protocol client/test harness. It is **not yet called by the guest game's native socket lifecycle**; the guest should use Eden's ordinary emulated socket/NEX path and receive its BAAS token through ACC once provisioned. The original SMM2 auth hostname, DNS destination, TLS certificate/SNI route and virtual station parameters are not confirmed, so none are guessed.

The BAAS builder is connected through `NextendoBaasRuntime` to Eden's ACC token cache, but it has no production signer/provider. As a result, ACC currently reports no BAAS token and the guest cannot complete BAAS-backed LoginEx. Complete title-specific RMC methods, matchmaking and live NAT/P2P are still unfinished. See [NEXTENDO_SMM2_PROFILE.md](NEXTENDO_SMM2_PROFILE.md).

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

- The public \`nextendo-nex/authbinding.go\` extracts \`nnex\` from the BAAS JWT inside the \`AuthenticationInfo\` DataHolder carried by \`LoginEx.extraData\`.
- The public server test confirms the token payload's \`nnex\` string claim, \`hm=true\`, and the \`AuthenticationInfo\` fields \`NGSVersion=3\`, \`TokenType=1\`, \`ServerVersion=40000\`. Optional \`tv\` identifies the installed title-update version.
- The public BAAS-JWKS source uses RS256, a matching \`kid\`, and the JWS \`jku\` URL to publish the corresponding public key.
- NEX now builds this compact JWT shape and encodes the AuthenticationInfo DataHolder; its unit tests use a fake signer and do not validate a live BAAS login.
- NEX still has no production RSA key/JWKS registration, provisioned \`iss\`, \`aud\`, \`sub\`/PID mapping or required client-attestation material. The builder is not connected to Eden ACC/LoginEx.
- No Den2 or other emulator key, attestation, token or session is used.

**Blocker:** NEX needs its own BAAS signer/key registration, exact identity claims and client attestation contract provisioned, then the tested payload must be wired to the game's actual ACC/LoginEx flow. No production JWT or attestation is fabricated or shipped.

### Super Mario Maker 2 NEX service

Super Mario Maker 2 is the first intended live test target. The current [Nextendo compatibility page](https://wiki.nextendo.network/games) lists SMM2 as **Experimental / In preparation**, at **70%**, with matchmaking and presence marked untested. This is the public service's current compatibility indication; it means the server side is not yet advertised as fully validated. Even a fully integrated NEX client would still need a live SMM2 test, and a failed matchmaking attempt could be a server-side limitation rather than an NEX client regression.

The public [Nextendo architecture guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/ARCHITECTURE.md) still diagrams only MK8D, Splatoon 2 and Smash as per-game NEX services, and the [deployment guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/DEPLOYMENT.md) does not list SMM2 in its example port table. The separate public SMM2 server repository is evidence of active implementation work, not proof of a production hostname or completed matchmaking service.

The organization publishes a dedicatedThe organization publishes a dedicated [SMM2 NEX server repository](https://github.com/NextendoNetwork/super-mario-maker-2). Its public server-side DataStore layer and the `nextendo-nex` core document selected SMM2 methods (RegisterUser 47, GetUsers 48, SyncUserProfile 49, CanPostCourse 60, GetMiiClothes 63, SearchCoursesPostedBy 74 and GetEventCourseStatus 154). The handler returns an explicit not-implemented response for other DataStore methods. That is useful protocol evidence, but it is not a complete SMM2 client handshake specification or proof of end-to-end Course World/matchmaking.

The public server source does provide the public per-title PRUDP access key \`fdf6617f\`, NEX version \`40000\`, auth WSS port \`:443\`, secure server default port \`:60007\`, auth PRUDP minor \`5\` and secure minor \`0\`. The secure server can override its port and advertises the actual destination in a \`prudps:/address=...;port=...\` StationURL returned by LoginEx. The NEX profile now records these values and follows that StationURL rather than assuming a deployment endpoint.

The public SMM2 server source confirms that its process binds the auth endpoint on `:443` and secure endpoint on `:60007` by default, but both ports and the host are environment-configurable. Its `NEXTENDO_HOST` default is loopback (`127.0.0.1`) for local development; it is not the public production address. The LoginEx `StationURL` is built from the deployment's configured host and secure port. The source also sets secure PRUDP minor version `0`, because its documented client test shows that negotiating the default version can cause the client to omit the Kerberos ticket; the NEX SMM2 profile now separates auth minor `5` from secure minor `0`.

The public source still does not publish the live deployment's actual SMM2 auth hostname, public service IP/DNS mapping, BAAS registration for this NEX application, or a completed client login/session contract. It does not expose every RMC payload needed by the game. NEX does not guess the host, invent a ticket or embed server-only Kerberos passwords.

**Current split:** the public service homepage indicates SMM2 activity, but the published deployment documents do not expose its production endpoint configuration. The NEX client can implement the public protocol profile and payload structure; actual login, course interactions and matchmaking still need to be validated from this Android client against the live deployment.

### Title DNS and TLS/SNI

- The Nextendo deployment guide describes title-scoped hostname routing, SNI passthrough and certificates valid for the original title hostnames.
- The public SMM2 source supplies an auth endpoint port and a secure StationURL format, but not the original production hostname or deployed service IP.
- The secure port can be overridden by server deployment configuration; the runtime StationURL is authoritative.
- NEX preserves the hostname supplied to the WSS URI so normal TLS hostname verification and SNI can work; DNS must route that hostname to the correct service IP.
- NEX does not add guessed hostnames, IP addresses, wildcard redirects or disabled certificate checks.

**Blocker:** the SMM2 auth hostname, its deployment target IP/DNS mapping, certificate coverage and live StationURL values remain unconfirmed. Platform TLS certificate validation remains enabled.

### NAT, UDP mapping, discovery and P2P

- The public Nextendo NNCS implementation documents a 16-byte probe that reports the observed external port/address and server address.
- The deployment guide requires observations from two distinct, client-reachable vantage addresses.
- NNCS ports and service endpoints are deployment/title-specific; the inspected public examples do not confirm the SMM2 values.
- The new codec/tracker does not bind or preserve the emulator's UDP socket, run a keepalive schedule, discover peers or execute hole-punching.
- No SMM2-specific NAT-check endpoints, probe ports, discovery messages or P2P lifecycle have been verified.

**Blocker:** obtain the SMM2 NAT-check endpoints and ports, probe/keepalive timing, session-discovery flow and socket-lifetime requirements.

## Test and release gate

Run #108 successfully built and published the PRUDP-Lite/WSS transport and adaptive NEX branding. It did not include the later BAAS/SMM2 profile or public network-statistics code.

Run #121 has passed its native friend-cache and Android Nextendo unit-test steps and is currently in the Android APK build step. Run #123 is the pending validation for the public network-statistics UI and its off-main-thread implementation. Neither run is a live service or multiplayer test.

These are build/test results only. They do not prove live account-service acceptance or in-game multiplayer.

Required before claiming in-game online support:

1. Connect the new BAAS JWT builder and AuthenticationInfo serializer to Eden ACC/LoginEx, then validate claims and RS256 signatures against NEX's provisioned live JWKS.
2. Confirmed OAuth/game-token/refresh/presence API behavior in the live deployment.
3. A verified NEX PID ↔ game account/NSA mapping and guest-visible HLE IPC tests.
4. Derive the secure Kerberos ticket/check payload from LoginEx, connect both SMM2 PRUDP sessions to Eden's title lifecycle, and pass live authentication/reliability/RMC integration tests.
5. SMM2 title-specific DNS/TLS/SNI tests with certificate validation enabled.
6. NAT state, live UDP mapping retention, keepalive, peer discovery, hole-punching and cleanup tests.
7. End-to-end testing with two NEX accounts, two Android devices and Super Mario Maker 2.

Until those checks pass, NEX in-game multiplayer remains **not implemented / not verified**.


## NEX UI and current device-facing status

- The launcher uses NEX adaptive-icon resources, with the original logo inset into the system-mask safe area and the original black background. Android applies the user's circular or squircle launcher mask, like Eden's adaptive icon.
- The Games header keeps Eden's original MaterialTextView title and styling; the app-name resource displays the text `NEX` instead of a large logo image.
- The fragment-wide glass treatment was removed. The Android app keeps Eden's original Material styling for bars, buttons, cards and switches.
- The account panel separates account sign-in from game readiness. It displays the scopes actually returned by OAuth, whether `game.matchmaking` was granted, and a token-free diagnostic for the latest presence request (HTTP status, missing scope or network/session failure). A valid `nx2.` token is only a prerequisite; SMM2 remains disconnected until BAAS/nnex signing, title endpoint and PRUDP session are implemented and verified.
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

`NextendoBaasIdTokenBuilder` builds the RS256 compact JWT with the NEX-provisioned `kid` and JWS `jku` header, externally supplied `iss`, `aud` and `sub`, `hm=true`, and the current server-issued `nx2.` token in the `nnex` claim. `NextendoBaasAuthenticationInfo` encodes the JWT as the `Token` field in an `AuthenticationInfo` DataHolder for `LoginEx.extraData` (SMM2 server version `40000`).

The DataHolder encoding matches the public Nextendo helper: NEX String `AuthenticationInfo`, followed by one NEX Buffer containing the version-1 structure header and its content-length-prefixed fields. The Android runtime feeds the exact compact JWT into Eden's ACC cache only when a NEX-specific signer/claims provider exists.

The bridge is now connected, but no production signer or private key is included. The cache therefore remains empty until NEX's BAAS/JWKS and required attestation are provisioned. Unit tests use a fake signer only for local serialization and rejection checks. See [NEXTENDO_SMM2_PROFILE.md](NEXTENDO_SMM2_PROFILE.md).



## Latest source update — 7 November 2026

The development branch now includes the ACC cache bridge, the fail-closed Android BAAS provider boundary, the public SMM2 profile, LoginEx request/response codecs, client-ticket decryption, secure CONNECT proof construction and an explicit auth/secure WSS coordinator. The AuthenticationInfo DataHolder encoder was corrected to match the public server helper and public NEX structure writer: one outer Buffer length followed by a version-1 structure containing its own content length. A new-login callback also clears the previous account's BAAS cache before doing more network work.

Run #132 passed its native cache and Kotlin/Nextendo test steps and is still building the Android APK; this run predates the DataHolder framing correction, explicit native `<algorithm>` include and account-switch cache clear. Run #135 is pending with those final corrections. Neither a successful build nor the session harness proves a live BAAS login or successful SMM2 matchmaking. The current branch remains a development branch, not a complete online release.

The public [Nextendo Developer Documentation](https://wiki.nextendo.network/developers) describes OAuth PKCE, a separate `nx2.` game token, the `nnex` BAAS claim and public aggregate `/api/stats`. The official [architecture](https://github.com/NextendoNetwork/nextendo-docs/blob/main/ARCHITECTURE.md) and [deployment guide](https://github.com/NextendoNetwork/nextendo-docs/blob/main/DEPLOYMENT.md) show that online play additionally requires title DNS redirection, TLS/SNI, device authentication, a per-game auth/secure server, NNCS NAT checks and P2P. Addresses and signing secrets are runtime configuration; the public deployment guides use placeholders rather than publishing production IPs or private keys.

## Client attestation source note

The Nextendo public [Ryujinx-Nextendo changelog](https://wiki.nextendo.network/changelog) records a closed-API/application-identity update in v1.8.6 and a mandatory cryptographic client-attestation update in v1.8.7. The reference emulator's README says its official build signs API requests with a private key baked in at build time. Those notes establish that attestation exists for the official emulator client; they do not publish the signing contract for third-party OAuth public clients. NEX therefore keeps the standard public PKCE flow and its own Client ID, but must not claim that protected game endpoints accept the NEX client until this distinction is resolved and any required attestation is provisioned.


## Latest ACC and LoginEx wiring — 7 November 2026

The source overlay now adds the native BAAS cache to Eden, exposes JNI update/clear methods and patches application ACC. The cache accepts bounded compact-JWS-shaped bytes; it does not itself verify the RSA signature. The signing provider and Nextendo's live BAAS server/JWKS perform that trust function.

The Android session manager refreshes the ACC cache through `NextendoBaasRuntime`. The runtime clears the cache when there is no valid `nx2.` game token, no installed NEX signing provider or invalid provisioned claims. It also clears the previous BAAS identity immediately after a new OAuth grant is stored, before further network calls. Since the NEX production signer/JWKS has not been provisioned, the expected diagnostic remains `signer_not_provisioned` and no JWT is currently exposed to the guest.

The LoginEx and secure ticket codecs have independent unit tests. The auth/secure WSS coordinator requires the original auth hostname and PRUDP station values from a real client/server negotiation; it is not automatically invoked by SMM2's guest socket lifecycle.
