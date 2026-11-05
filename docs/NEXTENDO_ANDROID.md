# Nextendo Network integration — Android

## NEX application identity

- Public Client ID: `nxc_yAdR-14bOEq7`
- Android application ID: `com.nex.nx`
- Registered redirect URI: `com.nex.nx://oauth/callback`
- Requested scopes: `identity friends presence profil game.matchmaking`

NEX is a public Android OAuth client. It uses Authorization Code + PKCE (S256); no Client Secret or Bot Token is embedded in the APK.

## Endpoints verified in Nextendo's developer documentation

- Authorization: `https://nextendo.network/api/oauth/authorize`
- Token exchange and refresh: `https://nextendo.network/api/oauth/token`
- Player profile: `https://nextendo.network/api/profile?user={pid}`
- Presence publishing: `POST https://nextendo.network/api/presence`
- Game token: `GET https://nextendo.network/api/nex-token`

The Android authorization sample and endpoint specification use the `/api/oauth/*` paths. Do not use the desktop-only short paths in the NEX Android client.

## Sign-in sequence implemented in the NEX scaffold

1. Generate a cryptographically random PKCE `code_verifier` (43–128 URL-safe characters), an S256 SHA-256 Base64URL `code_challenge`, and a random `state`.
2. Persist verifier and state encrypted with AES-GCM using Android Keystore. The pending transaction expires after 10 minutes.
3. Open the authorization URI in a Chrome Custom Tab. NEX never collects the player's Nextendo password.
4. Receive the callback through `com.nex.nx://oauth/callback`.
5. Validate the callback scheme, host, path and one-time code. Validate `state` with a constant-time comparison when the server echoes it; the published Android example does not explicitly guarantee a state parameter, so the required PKCE verifier remains the code-binding protection.
6. Exchange the code at `/api/oauth/token` with `grant_type=authorization_code`, the same `client_id`, the exact `redirect_uri`, and original `code_verifier`.
7. Persist access and refresh tokens encrypted with Android Keystore.
8. Request the separate game token from `/api/nex-token`; save it separately from OAuth credentials.

The auth/session code is in `integration/android/src/main/java/com/nex/nx/nextendo`. The repeatable source overlay adds a **Nextendo Network** entry to Eden's root settings.

## Account profile

After the game-token endpoint returns a PID, NEX can request:

`GET /api/profile?user={pid}`

using `Authorization: Bearer <access_token>`. The current NEX account panel displays the username, Friend Code, PID and avatar URL when the profile API responds. Profile editing through the `profil` scope is not yet implemented because the developer reference currently does not specify the write endpoint and payload.

## Friends and presence

The public developer guide documents `GET /api/oauth/userinfo` for OAuth Bearer tokens. With the `identity` scope it returns PID, username and Friend Code; with `friends` it also returns the basic friend list. The public account API source additionally exposes `GET /api/friends` with friend profile fields and presence, plus `POST /api/friends` and routes for accepting, declining, removing, blocking and favoriting friends. The NEX social client should use typed models, bounded caching and background I/O; it must never make network requests from the emulation/PRUDP thread.

The public developer guide documents `POST /api/presence` and a heartbeat at least every 90 seconds. The public account-service implementation documents this JSON shape:

```json
{
  "status": 2,
  "app_field": "",
  "app_id": "0100000000000000",
  "app_detail": ""
}
```

Status values in the service are 0 (offline), 1 (online) and 2 (online/in-game). `app_field` is a game/HLE-specific binary field encoded as Base64 when a title needs it; NEX must not fabricate it. The app should publish online/game state from the emulation lifecycle, refresh it in the background, and let the server TTL expire the status after a crash. On a normal shutdown it should send offline status.

**Authentication contract must be confirmed before wiring these calls:** the public developer guide says OAuth access tokens are used for account APIs, but the public account-service source currently shows `/api/friends`, `/api/presence` and `/api/nex-token` using `accountFromBearer`, which accepts a web session token or an `nx2` game token rather than an OAuth access token. Only `/api/oauth/userinfo` explicitly validates the OAuth access token in that source snapshot. Do not silently substitute a bot token or another user's game token. The NEX account/presence client will remain behind an API adapter until Nextendo confirms the live production bearer-token contract.

## Account token vs game token

These credentials are not interchangeable:

- OAuth `access_token`: account APIs such as identity, friends, presence and profile.
- OAuth `refresh_token`: renews the account session; refresh tokens rotate.
- NEX game token (`nex_token`, with an `nx2.` token format): obtained from `GET /api/nex-token` with the OAuth bearer token and the `game.matchmaking` scope.

The access token lasts one hour; the refresh token lasts 30 days. Each refresh returns a new refresh token, so NEX must persist the rotated value immediately. The game token lasts 24 hours according to the developer guide. Fetch it on game launch or expiry and never put the OAuth token or bare PID in the game's `nnex` claim.

## In-game authentication still required

A successful OAuth login proves only that the user authorized NEX. It does not prove that a game can log in.

The developer guide says the NEX game token (`nx2.`) belongs in the `nnex` claim of the BAAS `id_token`. The HLE ACC/BAAS layer must build the JWT with the correct NEX account PID and sign it with the **NEX-specific BAAS signing key** using the exact JWT parameters and public key/JWKS registration approved by Nextendo. A bare PID, OAuth access token or empty `nnex` claim is not valid.

The public guide documents `GET /api/nex-token` with OAuth Bearer and the `game.matchmaking` scope. However, the public account-service source snapshot currently validates this route through `verifyToken` (web-session token), not `verifyOAuthToken`. The live gateway may have moved on; confirm the currently accepted bearer type and refresh flow with Nextendo before marking this implemented.

A valid `nx2.` token alone is not enough: online play also depends on correct BAAS serialization/signing, approved client identity/attestation, DNS redirection, TLS/SNI behavior, game NEX services, matchmaking and NAT/P2P networking. NEX must not claim in-game multiplayer support until a real supported-title session passes testing.

## Nextendo application approval and client attestation

The Nextendo changelog reports that the online gateway was closed to generic clients in v1.8.6 and that cryptographic client attestation became mandatory in v1.8.7. It also says each emulator must use its own registered application identity and required application headers.

Therefore, the NEX Client ID and OAuth PKCE registration are not sufficient proof that the NEX emulator is approved for game/network endpoints. Before enabling online game traffic, the NEX application owner must confirm with Nextendo administrators that this Client ID is provisioned for an emulator client and obtain the NEX-specific application identity/header and attestation requirements. Do not reuse keys, attestations or headers from Ryujinx, Citron, Den2 or another developer.

Any NEX private attestation material provided by Nextendo must be stored as protected GitHub Actions secrets or a protected release environment, never committed to this repository or printed in build logs. The build must remain offline-only until the NEX application is accepted by the current gateway.

## Android callback manifest contract

The overlay registers an exported callback activity with an intent filter for:

- scheme: `com.nex.nx`
- host: `oauth`
- path: `/callback`

PKCE state is validated before exchanging the code. A deep link is never treated as authenticated by itself.

## Build and source overlay

- Pinned Eden source: tag `v0.2.0-rc1`, commit `8e373eb714aa01117ec885680477501cdb572112`.
- `scripts/apply-eden-overlay.py` applies NEX package identity, Custom Tabs dependency, callback activity, settings entry and Kotlin sources to that exact source tree.
- `.github/workflows/build-nex-android.yml` builds the Android Optimized variant and uploads an APK/AAB artifact.

## Implementation status

- [x] Record the NEX public Client ID, package and redirect URI.
- [x] Record all five approved scopes.
- [x] Add PKCE authorization, state validation and encrypted pending-transaction storage.
- [x] Add OAuth token exchange and refresh-token rotation handling.
- [x] Add encrypted OAuth/game-token session storage.
- [x] Add the separate `/api/nex-token` request and token-format check.
- [x] Add authenticated player-profile lookup and basic account panel.
- [x] Add a reproducible Eden overlay and Android Optimized CI workflow.
- [ ] Confirm the registered URI/scopes in the Nextendo developer portal before live sign-in.
- [ ] Run and pass the GitHub Actions Android build.
- [ ] Test OAuth end-to-end with a real NEX account.
- [ ] Implement friends API after exact endpoint contract is available.
- [ ] Implement presence publishing and heartbeat (at least every 90 seconds) after request schema is confirmed.
- [ ] Implement profile editing after exact write endpoint contract is available.
- [ ] Complete NEX-owned BAAS/app attestation requirements with Nextendo.
- [ ] Integrate `nex_token` into Eden ACC/BAAS game authentication.
- [ ] Verify DNS/TLS/SNI, game NEX calls and NAT/P2P behavior.
- [ ] Test an actual multiplayer session in a title listed as supported by Nextendo.

## Secret handling

Never print OAuth access tokens, refresh tokens, game tokens, callback codes, PKCE verifiers, private signing material or attestation data to logs, screenshots, issues or commits. Reset any credential accidentally disclosed.
