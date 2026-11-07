# Nextendo Network integration — Android (NEX)

Updated: 7 November 2026.

This document describes the code present in the NEX branch and separates it from the game-network work that still depends on Nextendo's service contract and NEX-specific credentials.

## NEX public client identity

- Nextendo public Client ID: \`nxc_yAdR-14bOEq7\`
- Android application ID: \`com.nex.nx\`
- Redirect URI: \`com.nex.nx://oauth/callback\`
- Requested scopes (matching the owner's generated portal URL): `identity friends presence profil game.matchmaking emulator.status`
- Application identity header: \`X-Nextendo-Client-Id: nxc_yAdR-14bOEq7\`

NEX is a public Android OAuth client. It uses Authorization Code + PKCE (S256); no client secret or bot token is embedded in the APK. No Den2 or other emulator credential, token, session, signing key or attestation is reused.

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
| App/game presence | \`POST /api/presence\` | OAuth access token with \`presence\` |

Every REST request made by \`NextendoAuthClient\` carries the NEX Client ID header. Player-protected requests also carry the appropriate Bearer credential.

The sign-in coordinator generates a new PKCE verifier, S256 challenge and state for each authorization. It saves the pending transaction using the Android Keystore-backed session store before opening the browser. The callback validates the NEX scheme, host and path, then exchanges the one-time code. OAuth access tokens and the separate game token are stored as different credential types.

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

The account panel reads the signed-in identity from OAuth userinfo and displays the available PID, username and Friend Code. It also shows the scopes actually returned by OAuth and the latest token-free HTTP diagnostic from presence publishing. The OAuth friends view is read-only.

The game-facing friend snapshot is requested separately with the \`nx2.\` token. The parser accepts only fields actually present in the response: PID, username, explicit NSA, presence status, title/application ID and bounded AppField data. Missing identifiers are not synthesized.

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

**Not yet connected to a live title:** NEX still needs the provisioned NEX RSA signer/JWKS registration and issuer/audience/subject values; the original SMM2 auth hostname and its DNS/TLS route; and the secure CONNECT ticket/check payload that follows LoginEx. The title key above is public, but it is not a substitute for the server-issued ticket. Complete SMM2 RMC arguments, retry policy, matchmaking and NAT/P2P lifecycle also remain unfinished.

See [the detailed SMM2 BAAS and PRUDP profile](NEXTENDO_SMM2_PROFILE.md).

## NAT and P2P

NEX includes a strict 16-byte NNCS probe codec and a two-vantage NAT observation tracker. The tracker rejects unknown vantage IDs, duplicate observations and ambiguous server addresses; closing it clears the observations and prevents reuse.

The tracker owns no UDP socket. NEX does not yet preserve the game's socket mapping, schedule NAT keepalives, discover peers, coordinate a P2P session, perform hole-punching or verify cleanup of a live P2P connection. The SMM2-specific NNCS endpoints, ports and timing remain unconfirmed.

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


## BAAS signing boundary

The Android integration now contains a NEX-owned compact-JWT builder boundary for the documented \`nnex\` claim. It accepts a complete claim set from a verified NEX provisioning provider and a \`NextendoBaasSigner\` interface for RS256. It does not embed or generate a private key, invent issuer/audience/JWKS values, or connect to ACC until Nextendo provisions the client-specific signer and claim contract. Tests use a fake signer only for serialization and fail-closed validation.
