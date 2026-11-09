# Nextendo Network integration — Android (NEX)

Updated: 8 November 2026.

This document describes the code present in the NEX branch and separates it from the game-network work that still depends on Nextendo's service contract and NEX-specific credentials.

## NEX public client identity

- Configured Nextendo public Client ID: `nxc_yAdR-14bOEq7` (production registration and attestation permission must be confirmed by Nextendo)
- Android application ID: \`com.nex.nx\`
- Redirect URI: \`com.nex.nx://oauth/callback\`
- Requested scopes (matching the owner's generated portal URL): `identity friends presence profil game.matchmaking emulator.status`
- Application identity header: \`X-Nextendo-Client-Id: nxc_yAdR-14bOEq7\`

NEX is a public Android OAuth client. It uses Authorization Code + PKCE (S256); no client secret or bot token is embedded in the APK. NEX does not import another emulator's Client ID, build secret, signing key or attestation material. A player may explicitly import DEN/Citron's nextendo_account.txt session file; its bearer token is validated and re-encrypted locally, while NEX continues to identify every API request with its own Client ID.

The app owner's current portal-generated URL selects six scopes: `identity friends presence profil game.matchmaking emulator.status`. NEX requests those exact names, including the portal's spelling `profil`. A requested scope is not assumed to be granted: the authorization response's `scope` field remains the source of truth. The meaning and live API contract for `profil` and `emulator.status` still require confirmation; requesting them does not imply that NEX already calls their APIs.

## OAuth and account API flow

The current NEX client uses these routes:

| Operation | Route | Credential |
|---|---|---|
| Browser authorization | \`GET /oauth/authorize\` | NEX Client ID + PKCE |
| Code exchange / refresh | \`POST /api/oauth/token\` | Authorization code + verifier, or rotating refresh token |
| Account identity and basic friends | \`GET /api/oauth/userinfo\` | OAuth access token |
| Game token | \`GET /api/nex-token\` | OAuth access token with \`game.matchmaking\` |
| Game-facing friend snapshot | \`GET /api/friends\` | Nextendo \`nx2.\` game token |
| Add friend by Friend Code | POST /api/friends | nx2 game token + friend_code |
| Accept friend request | POST /api/friends/accept | nx2 game token + target pid |
| Decline friend request | POST /api/friends/decline | nx2 game token + target pid |
| Remove friend | POST /api/friends/remove | nx2 game token + target pid |
| App/game presence | \`POST /api/presence\` | OAuth access token with \`presence\` |

Every REST request made by `NextendoAuthClient` carries the configured NEX Client ID header. Presence publishing uses the OAuth access token with the `presence` scope as described by the Developer Documentation; the `nx2.` game token is not sent to `/api/presence`. The current code does not yet add the cryptographic client-attestation signature described by the official Ryujinx-Nextendo v1.8.7 security update; this requires a NEX-issued credential and a documented signing contract.

The sign-in coordinator generates a new PKCE verifier, S256 challenge and state for each authorization. It saves the pending transaction using the Android Keystore-backed session store before opening the browser. The callback validates the NEX scheme, host and path, then exchanges the one-time code. OAuth access tokens and the separate game token are stored as different credential types.

### Importing a DEN/Citron account file

The account panel's **Servidores e backup** section opens the Android document picker for a user-selected `nextendo_account.txt`. The parser accepts DEN's `pid`, `username`, `friend_code`, `token`, optional `refresh_token` and `token_expiry` fields. The access token is checked against `/api/oauth/userinfo`; the PID returned by Nextendo must exactly match the file before the import replaces the current session. The plaintext file is not copied into shared storage or logged. Accepted data is stored in the same AES-GCM / Android Keystore session store as a NEX PKCE login.

The import does **not** import DEN's application Client ID or any local BAAS signing key. The game-facing `nx2.` token is requested again from `/api/nex-token` using NEX's `X-Nextendo-Client-Id`. A capability such as `game.matchmaking` or `presence` is only recorded after the corresponding protected operation succeeds. A refresh token created for another OAuth client may not be redeemable with NEX's Client ID; if it is rejected or expires, sign in through NEX's own browser/PKCE flow.

The current Developer Documentation states:

- OAuth access token lifetime: one hour.
- OAuth refresh token lifetime: 30 days; successful refresh rotates the token, so NEX persists the full response immediately.
- Game token lifetime: 24 hours. The token is an opaque \`nx2.\` value and is refreshed by requesting a new game token.

\`NextendoGameTokenParser\` requires the documented \`nx2.\` prefix, positive PID, username and positive \`expires_in\`. It calculates expiry from the response and does not decode or forge the token.

## Public API contract discrepancy

The live [Nextendo Developer Documentation](https://wiki.nextendo.network/developers) describes the public OAuth flow, the \`game.matchmaking\` scope, \`/api/nex-token\`, the separate game token and OAuth-scoped presence.

The public [nextendo-account source](https://github.com/NextendoNetwork/nextendo-account/tree/61845fa2742f2053d2aa75bbc5d6b3a888eb2d1a) does not currently match all of that documentation:

- The public OAuth scope allow-list contains \`identity\` and \`friends\`, not \`presence\` or \`game.matchmaking\`.
- The public third-party authorization-code exchange does not return a refresh token.
- The public \`/api/nex-token\` handler validates a web-session token and omits \`expires_in\`, unlike the Developer Documentation's OAuth Bearer contract.
- The public \`/api/presence\` handler accepts a web-session token or \`nx2.\` token, not the OAuth access token described by the Developer Documentation.
- The public \`/api/friends\` handler accepts a web-session token or \`nx2.\` game token. NEX therefore uses the game token for the game-facing friend snapshot and never sends an OAuth access token to this route.
- The public friend response does not expose a confirmed NetworkServiceAccountId (NSA) or Switch account UUID.

NEX follows the published public-client documentation where it is explicit. It does not switch to a private web-session login or substitute one token type for another. The production deployment may be ahead of the public source; that has not been independently confirmed.

## Android UI and social data

The account panel reads the signed-in identity from OAuth userinfo and displays the available username and Friend Code. It shows the scopes actually returned by OAuth and the latest token-free HTTP diagnostic from presence publishing. The internal PID is not displayed because Nextendo treats it as a credential.

When a valid nx2 game token is available, the Friends screen requests the game-facing GET /api/friends snapshot. It displays accepted friends and incoming requests, supports adding a friend by Friend Code, accepting or declining requests, and removing a friend. If the game-facing route is unavailable, the UI can fall back to the OAuth userinfo friend list. Friend mutations require a valid server-issued game token and a successful response from Nextendo.

The parser accepts records without a presence object and marks their presence as unknown. It preserves server-provided PID, Friend Code, avatar, presence, title/application ID and bounded AppField data. A PID is never converted into a Switch NSA. Friends with missing NSA may still appear in the Android UI but are filtered out before entering the game-facing native cache.

## Native cache and game-facing nn::friends HLE

The Eden overlay now:

- Adds a JNI bridge for replacing and clearing the NEX friend snapshot and signed-in PID.
- Copies the NEX-owned bounded friend cache and record serializers into the pinned Eden source tree.
- Connects the read-only cache to the game-facing \`nn::friends\` service handlers, including list/profile/detail/presence and qLaunch V2 paths.
- Leaves unknown Switch UUIDs and last-online timestamps at zero.
- Exposes a friend record only when the API provides an explicit non-zero NSA. PID is not silently converted into a friend's NSA.

The current public friend response has PID and presence data but no confirmed NSA/UUID mapping. With that response, the native game-facing friend list intentionally remains empty rather than sending guessed account identifiers.

The ACC adapter no longer substitutes a Nextendo PID for Eden's account ID. The PID-to-NSA/account-ID equivalence has not been confirmed by Nextendo, so Eden retains its existing local ACC identity until an explicit mapping and game-auth contract are issued. The NEX PID remains available to NEX's own friend cache and must not be treated as a BAAS identity proof.

The C++ host test checks cache bounds, duplicate filtering, explicit-NSA filtering, presence fields and serialized qLaunch V2 record layouts. This does not replace IPC tests inside a running game or qLaunch.

## PRUDP, RMC and the SMM2 target

NEX now owns a testable PRUDP-Lite client and WebSocket transport layer:

- The HMAC-MD5 CONNECT proof follows the public Nextendo Lite algorithm.
- The SMM2 profile records public title values from the public server source: NEX version \`40000\`, PRUDP title access key \`fdf6617f\`, auth WSS port \`443\`, public secure-port default \`60007\`, auth minor version \`5\`, and secure minor version \`0\`.
- The secure endpoint is not guessed: \`NextendoSmm2Profile\` parses the \`prudps:/address=...;port=...\` StationURL advertised by LoginEx, then constructs the WSS endpoint.
- The client state machine handles SYN/SYN-ACK/CONNECT/CONNECT-ACK, reliable DATA, ACK/MULTI-ACK, duplicate suppression, fragment reassembly, PING and DISCONNECT.
- The OkHttp transport sends and receives binary WebSocket frames over \`wss://\`; ordinary TLS certificate and hostname verification remain enabled.
- The RMC envelope codec supports request, success and error messages. Outbound sends are restricted to requests.
- The SMM2 DataStore protocol identifier 115 and publicly documented method identifiers are recorded in code.

The BAAS side now has a JWT builder and an \`AuthenticationInfo\` DataHolder encoder for the \`LoginEx.extraData\` field. Unit tests cover the public title profile, StationURL parsing, the \`nnex\` binding and local serialization. They do not prove acceptance by a live Nextendo service.

**Not yet connected to a live title:** NEX now contains the Citron BAAS token format and a PEM import path, but no authorized Nextendo signing PEM has been provisioned or verified for NEX. The code never generates a replacement private key and never falls back to OAuth in `nnex`. A matching public JWKS entry alone does not confirm server-side acceptance. Separately, client attestation is not implemented: the client currently sends the Client ID header but no signed application proof. Nextendo must register/authorize NEX and issue its own attestation credential/contract before protected online calls can be relied on. After that, LoginEx, secure CONNECT ticket/check payload, live SMM2 StationURL, complete RMC matchmaking and NAT/P2P still need end-to-end validation.

See [the detailed SMM2 BAAS and PRUDP profile](NEXTENDO_SMM2_PROFILE.md).

## Cloud-save status

Nextendo's current Cloud Saves guide says the service was removed in September 2026. The NEX transfer screen no longer offers cloud-save upload or download. The BCAT seed endpoint is separate; it remains a schedule-data download, not a cloud-save backup.

## NAT and P2P

NEX now reads NNCS responses from the game's real emulated BSD UDP socket, not from an app-owned socket. The Eden overlay hooks `BSD::RecvFromImpl` and inspects only successful 16-byte datagrams from remote source ports `10025` and `10125`. A response is accepted only when its big-endian test ID is `101`, its external port is valid, and its external IPv4 is public. The two observations must arrive on the same local guest UDP port, report the same external IPv4, and identify distinct responder IPv4s. Only then does the native rewriter receive a usable public address. The native UDP socket keeper preserves the same local UDP port across the game's brief close/reopen sequence.

The SSL-layer Station URL rewriter adapts the DEN2 behavior for outbound SecureConnection Register/ReplaceURL and MatchmakeExtension Create/Join/AutoMatchmake RMC requests. It changes only a private station `address=` with nonzero `natf`, retains the port and other parameters, rebuilds the length fields, and keeps the rewritten TLS write buffer stable across non-blocking retries. It does not rewrite inbound peer Station URLs because substituting this client's own public IP for a remote peer would be incorrect.

`NextendoNatProbeClient` remains a separate diagnostic harness; it is not called by the app's network settings and must not be used to set the guest game's NAT mapping. The observed NNCS source ports and message layout are based on DEN2 and still need confirmation against the live SMM2 deployment. NEX does not yet implement periodic NNCS keepalives, peer discovery, matchmaking signaling, UDP hole-punching or live two-device P2P cleanup tests. The public Nextendo compatibility page currently marks SMM2 v3.0.3 as Experimental (70%) with matchmaking and presence untested.

## BAAS / ACC game authentication

The public Nextendo core's \`authbinding.go\` and \`authbinding_test.go\` now confirm the payload placement: the signed compact JWT is the \`Token\` field of an \`AuthenticationInfo\` DataHolder carried in TicketGranting \`LoginEx.extraData\`. Its payload carries the string-valued \`nnex\` claim with the \`nx2.\` game token; the service also recognizes optional \`tv\`. The public BAAS-JWKS service selects the matching RSA key with \`kid\` and publishes it at the URL carried in the JWS \`jku\` header.

NEX now has:
- An RS256 JWT builder with provisioned \`kid\` and HTTPS \`jku\`, plus externally supplied \`iss\`, \`aud\` and \`sub\`; it sets the public profile's \`hm=true\` claim and the current \`nnex\` token.
- An NEX DataHolder encoder for \`AuthenticationInfo\` with the SMM2 NEX server version fields.
- Unit tests for the token-to-\`nnex\` binding and LoginEx extraData serialization.

**Still not provisioned or wired:** the NEX-specific RSA private key, its registered public JWK, the issuer/audience/subject mapping, any required client-attestation material, and the Eden ACC/LoginEx lifecycle hook. The code uses test signers in unit tests only; it does not invent a production key or token.

The BAAS JWT belongs in LoginEx extraData, **not** in the PRUDP CONNECT packet. Secure CONNECT happens after LoginEx and requires the issued Kerberos ticket plus the encrypted check request. The generic PRUDP session does not yet construct that payload.

No BAAS JWT or attestation is fabricated. The ACC HLE identity bridge must not be treated as completed game authentication.

## DNS, TLS and SNI

NEX has not installed a guessed SMM2 hostname map or server IP. The Nextendo deployment documentation describes TLS/SNI routing and certificates for the original hostnames, but its published port examples cover other game services and do not provide a complete SMM2 route.

When title routing is implemented, it must preserve the original hostname for SNI and normal certificate validation. NEX must not disable certificate checks or redirect unrelated domains.

## Security rules

- Never embed a client secret, bot token, OAuth token, game token, private key or attestation in source code.
- Never copy another emulator's credentials, tokens, sessions or signing material.
- Never put an OAuth access token in the BAAS \`nnex\` claim.
- Never synthesize a \`nx2.\` token, PID, NSA, UUID, AppField, server address, port or acceptance response.
- Keep platform TLS certificate validation enabled.
- Do not claim in-game multiplayer works until the complete flow has been tested with two NEX accounts, two devices and a Nextendo-supported game.

## Source locations

- Android OAuth, session and social code: \`integration/android/src/main/java/com/nex/nx/nextendo/\`
- Native friend cache and serializers: \`integration/android/src/main/cpp/\`
- Native host tests: \`integration/android/src/test/cpp/nextendo_friend_cache_test.cpp\`
- Android unit tests: \`integration/android/src/test/java/com/nex/nx/nextendo/\`
- Repeatable Eden integration: \`scripts/apply-eden-overlay.py\`
- Full status and blockers: [NEXTENDO_ONLINE_STATUS.md](NEXTENDO_ONLINE_STATUS.md)


## NEX visual layer

The launcher uses NEX adaptive-icon resources with the original artwork inset in the Android safe area; Android applies its launcher mask. The Games header uses Eden's original MaterialTextView title and displays the text `NEX`, not a large image. The fragment-wide glass treatment has been removed. Buttons, bars, switches and panels keep Eden's original Material styling.


## BAAS signing boundary — Citron profile implemented; production authorization blocked

NEX has a Citron-compatible BAAS path in code: `NextendoCitronBaasSigner` parses an operator-supplied RSA PEM, `NextendoCitronBaasProfile` creates the Citron claim set, and `NextendoBaasRuntime` verifies the derived public modulus/exponent against the live Nextendo JWKS before encrypting the PEM with Android Keystore. On process restart, it revalidates the stored key. No private key is bundled, generated as a fallback, copied from another emulator, or accepted without a live JWKS match. The BAAS JWT is cached to Eden ACC only when both the signer and a valid server-issued `nx2.` token are present.

This is a protocol implementation, not proof of production authorization. NEX does not currently possess an operator-authorized BAAS PEM, and Nextendo has not confirmed in this integration that the fixed Citron `kid=nextendo-baas-key-1` identity may be used with NEX's configured public Client ID. A matching JWKS entry proves only that the public key is published; it does not prove that the account gateway or game server accepts NEX's token. The earlier Nextendo response about rejecting third-party client-generated BAAS keys and refusing per-installation JWK registration remains an external blocker. Do not import or distribute an unapproved PEM and do not copy DEN/Citron local fallback keys.

The official v1.8.7 client-attestation requirement is a separate blocker from BAAS. NEX currently sends its Client ID header but has no NEX-issued cryptographic attestation credential or documented signing contract. The registration portal is currently limited to the Nextendo team; Nextendo must confirm that this Client ID is registered/allowed and issue or authorize the required NEX-specific attestation path. No official Ryujinx/Citron private key, client secret or attestation material may be reused.

Even after those credentials are authorized, the live title path still needs validation: the ACC hook must deliver the signed JWT in `LoginEx.extraData`, the auth service must accept LoginEx and issue its ticket/StationURL, and the secure client must complete CONNECT using the service-issued ticket plus encrypted check request. The current PRUDP harness and DataHolder codec do not prove those live steps.

The BAAS JWT belongs in LoginEx extraData, not in the PRUDP CONNECT packet. Secure CONNECT happens after LoginEx and requires the ticket issued by the service plus the encrypted check request.
