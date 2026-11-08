# NEX — DEN2 and Citron Nextendo integration audit

Updated: 8 November 2026.

This audit compares the public [DEN2 repository](https://github.com/Dmr77680/Den2) and the official [NextendoNetwork/citron-nextendo repository](https://git.nextendo.network/NextendoNetwork/citron-nextendo) with NEX. They are references for architecture and observable API behavior only. NEX keeps its own application registration, client identity, session and native implementation.

## NEX identity — do not replace it

NEX continues to use:

- Android application ID: com.nex.nx
- Public OAuth Client ID: nxc_yAdR-14bOEq7
- OAuth redirect: com.nex.nx://oauth/callback
- Authorization Code + PKCE (S256)
- No embedded client secret.

DEN2 declares a separate Client ID, nxc_z_fetCuVO1qx, in src/web_service/nextendo_api.cpp. The Citron Nextendo source loads its application identity from its own build-time secrets header. Neither value belongs in NEX. NEX must not adopt their Client IDs, build secrets, private signing keys, attestation material or service credentials. The only credential migration is the explicit player-selected nextendo_account.txt import described below; its session token is validated as user-supplied account data and re-encrypted locally, not compiled into NEX.

## What the reference projects demonstrate

| Project/source | What the public source shows | NEX implementation decision |
|---|---|---|
| DEN2 — src/web_service/nextendo_api.cpp | Separate application Client ID, browser OAuth/PKCE, token exchange/refresh and account/game-token API requests | Keep NEX's own Client ID and its existing PKCE callback and API client. |
| DEN2 — src/common/nextendo_account.cpp | Local PID/profile/session persistence in nextendo_account.txt | Support an explicit user-selected import of the key=value file, validate its bearer token/PID with Nextendo, then re-encrypt the session in NEX Android Keystore storage. Do not copy DEN's Client ID or local signing path. |
| DEN2 — src/core/hle/service/acc/acc.cpp | Local BAAS RS256 JWT construction, nnex claim, private-key file/environment lookup and an auto-generated RSA fallback | Do not port the local-signing fallback. Nextendo explicitly rejects client-generated BAAS keys and third-party RS256 signatures. |
| DEN2 — src/core/hle/service/ssl and service/sockets | Nextendo-specific TLS/SNI, DNS and socket compatibility fixes | Use NEX-owned exact-host DNS and TLS code; port only protocol behavior verified against the service. |
| Citron Nextendo — src/web_service/nextendo_api.cpp | Browser OAuth/PKCE, an application identity injected from a private build header, account/profile APIs, friend list and friend-request operations | Keep NEX's own Client ID and NEX OAuth contract; implement the supported account actions in NEX's own client. |
| Citron Nextendo — src/core/hle/service/acc/acc.cpp | BAAS id_token construction and local RS256 signing logic | Do not assume the presence of local signing code proves a key is trusted or accepted. Keep NEX BAAS fail-closed. |
| Citron Nextendo — src/core/hle/service/ssl and service/sockets | Emulator-side DNS redirection, SNI/TLS and transport adaptations | NEX keeps its own Android-focused DNS, TLS/SNI, PRUDP and NAT implementation. |

The Citron Nextendo README describes browser sign-in, hostname redirection, TLS, auth/secure connection, NAT checks, peer-to-peer, presence and account/friend management. It also warns that online support is new, incomplete and lightly tested. That README is useful architecture evidence, not proof that every flow is compatible with this NEX build or device.

## Account and friend integration now in NEX

NEX already owns:

- Its own OAuth Authorization Code + PKCE login and callback validation.
- The NEX Client ID header and exact NEX redirect URI.
- Encrypted Android Keystore session storage and serialized refresh-token rotation.
- Separate parsing/storage for the server-issued nx2 game token.
- NEX-only encrypted session backup/restore. It is intentionally not compatible with another application's bearer-token file.
- Account profile, presence publishing and public network statistics.
- A NEX-owned friends UI and native game-friend cache/HLE bridge.

The current integration adds the game-facing friend actions using NEX's own nx2 token:

- GET /api/friends: read accepted friends and incoming requests.
- POST /api/friends with friend_code: send a friend request by Friend Code.
- POST /api/friends/accept with pid: accept a request.
- POST /api/friends/decline with pid: decline a request.
- POST /api/friends/remove with pid: remove a friend.

The Android Friends screen displays accepted friends and incoming requests, supports add/accept/decline/remove actions, and refreshes the list after a mutation. These routes follow the publicly visible Citron Nextendo client behavior. They still require live validation with NEX's registered Client ID and the actual scopes/token contract returned by the deployed Nextendo service; source-level implementation is not proof that production accepted each request.

Missing presence stays unknown, not offline. A friend can remain visible in the Android UI without an NSA. The NEX Android bridge and native HLE cache only accept entries with an explicit non-zero NSA from the service. NEX never substitutes a friend's PID for an NSA. The signed-in player's PID is also hidden in the NEX account panel because the Citron Nextendo README warns that a PID acts as a credential.

NEX supports a player-selected import of DEN/Citron's nextendo_account.txt. The importer accepts the documented key=value fields (pid, username, friend_code, token, optional refresh_token and token_expiry), checks the access token against Nextendo userinfo, and requires the returned PID to match the file before saving anything. The plaintext file is not copied into NEX storage; accepted fields are re-encrypted by Android Keystore. The imported token is only an account bearer credential: all NEX API calls continue to send NEX's own X-Nextendo-Client-Id, and any game token is requested afresh through that NEX client. A refresh token from another client may be rejected by the server; in that case the player must complete NEX's own PKCE sign-in. The file is never bundled or logged.

## BAAS / ACC — explicitly blocked by the founder's answer

DEN2 and Citron both contain code that constructs a BAAS RS256 id_token locally. DEN2's source loads a key from NEXTENDO_BAAS_SIGNING_KEY or nextendo_baas.pem and can generate an RSA-2048 fallback when no key is present. The existence of that code does not show that an installation-generated key is registered or accepted by the live Nextendo service.

The Nextendo founder explicitly told NEX that client-generated keys will not be registered in the JWKS, third-party RS256 BAAS tokens will not be accepted, per-installation JWKS registration is not available, and there is currently no third-party certification or BAAS token contract. The founder also said that game tokens are issued by Nextendo's own servers after the player signs in.

Therefore:

- Do not copy DEN2's or Citron's private signing key, application secret, attestation material, issuer, audience, jku, kid or claims.
- Do not activate NEX's Android Keystore RSA signer or submit the old per-installation JWK request as a workaround.
- Do not put a locally generated RS256 token into Eden ACC.
- Keep the NEX BAAS runtime fail-closed until Nextendo supplies a new official server-side game-authentication mechanism for NEX.

The NEX JWT and AuthenticationInfo DataHolder code remains protocol/test scaffolding. It is not a live BAAS integration and must not be described as working in-game authentication.

## Remaining requirements for actual in-game multiplayer

1. **Official game authentication:** Nextendo must provide a server-issued or explicitly approved game credential for the NEX application, with the account/PID binding, token lifetime/refresh and ACC/LoginEx contract. The founder's current answer says this is not available to third-party clients today.
2. **Public-client API contract:** Nextendo must confirm that NEX's registered Client ID may use the requested OAuth scopes and the game-token, friends and presence routes. If protected calls require client attestation, Nextendo must provide an approved third-party mechanism; NEX cannot borrow DEN/Citron credentials.
3. **SMM2 live service configuration:** production auth hostname and route IP, trusted TLS certificate/SNI behavior, the live StationURL and negotiated secure PRUDP parameters, plus confirmation that SMM2 LoginEx and secure sessions are enabled.
4. **SMM2 gameplay services:** supported RMC methods and payloads for room creation/join/leave, player/session state and matchmaking; actual server-side availability for the target game version.
5. **NAT and P2P:** SMM2 NNCS/NAT endpoints and ports for both vantage points, probe/keepalive timing, peer-discovery/signaling fields, UDP socket lifetime and hole-punch/cleanup behavior.
6. **End-to-end verification:** two distinct NEX accounts, two Android devices, the same supported game/update version, live login on both clients, matchmaking, session entry and sustained P2P play.

NEX's DNS routes, PRUDP-Lite tests, LoginEx codecs and NAT probe utilities are client-side groundwork. They are not a connected guest-game lifecycle and do not prove multiplayer.

## What is needed from the project owner

No DEN/Citron Client ID, build secret, password, private key or attestation file is imported. The player may explicitly select nextendo_account.txt; the access/refresh token inside it is handled only as a user-supplied session and is re-encrypted locally, never copied into the APK.

The owner-side checks are limited to:

1. Verify in the Nextendo Developer Portal that NEX's Client ID nxc_yAdR-14bOEq7 and redirect com.nex.nx://oauth/callback remain active.
2. Verify which scopes are actually granted in the OAuth response; do not assume requested scopes were granted.
3. When testing, share only token-free diagnostics: NEX build/commit, game and update version, UTC time, endpoint hostname/path, HTTP status or game error code, and the step that failed.

Never send access/refresh tokens, nx2 tokens, BAAS JWTs, authorization codes, PKCE verifiers, PIDs, passwords, private keys or client-attestation material in chat or logs.

## Current implementation status

The NEX account/social client now has NEX-owned OAuth/session handling, game-token-backed friend list and friend mutations, presence plumbing, and a guarded native friend cache. The BAAS trust step remains blocked by Nextendo's explicit policy response. The SMM2 PRUDP/RMC/NAT pieces remain incomplete and unverified against a two-device live match.
