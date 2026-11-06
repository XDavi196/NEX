# Nextendo Network integration — Android

## NEX application identity

- Public Client ID: `nxc_yAdR-14bOEq7`
- Android application ID: `com.nex.nx`
- OAuth redirect URI: `com.nex.nx://oauth/callback`
- Requested scopes: `identity friends presence profil game.matchmaking mods.favorites`
- Required application header: `X-Nextendo-Client-Id: nxc_yAdR-14bOEq7`

NEX is a public Android OAuth client. It uses Authorization Code with PKCE (S256). Do not embed a Client Secret, bot token, another emulator's Client ID, or another developer's signing/attestation material in the APK.

## Endpoints used by the integration

- Authorization: `https://nextendo.network/oauth/authorize`
- Token exchange and refresh: `https://nextendo.network/api/oauth/token`
- Account identity: `GET https://nextendo.network/api/oauth/userinfo`
- Friend list: `GET https://nextendo.network/api/friends`
- Player profile: `GET https://nextendo.network/api/profile?user={pid}`
- App presence: `POST https://nextendo.network/api/presence`
- Separate game token: `GET https://nextendo.network/api/nex-token`

The Android client sends the NEX client-identity header with REST calls. Player-protected requests also use `Authorization: Bearer <access_token>`. Live service acceptance of the friends endpoint with the NEX public client still requires end-to-end verification.

## OAuth sign-in sequence

1. Generate a cryptographically random PKCE `code_verifier`, an S256 SHA-256 Base64URL `code_challenge`, and a random `state`.
2. Persist the pending verifier and state using encrypted Android Keystore-backed storage.
3. Open the authorization URL in the system browser or a Chrome Custom Tab. NEX must never collect the player's Nextendo password.
4. Receive the redirect through `com.nex.nx://oauth/callback`.
5. Validate the callback scheme, host, path, and pending transaction. Reject invalid or expired callbacks.
6. Exchange the one-time authorization code at `/api/oauth/token` using the same Client ID, exact redirect URI, and original PKCE verifier.
7. Persist the access token and rotating refresh token encrypted with Android Keystore. Save a newly issued refresh token immediately.
8. Load the account identity through `/api/oauth/userinfo`.
9. Request the game token separately through `/api/nex-token` when the game-authentication flow is ready.

No OAuth access token, refresh token, authorization code, or PKCE verifier may be written to logs or placed in a URL.

## Profile, friends, and presence

The NEX account panel can display profile fields returned by the identity/profile APIs, such as username, PID, and Friend Code.

The development friends screen uses `GET /api/friends` with the NEX OAuth access token and NEX client-identity header. It can display friend avatars, names, Friend Codes, presence states, reported game activity, and incoming requests when those fields are returned by the service.

The current friends UI is read-only. Sending, accepting, declining, removing, and blocking friend requests are not enabled until the mutation routes and authorization contract have been confirmed for the NEX application.

App presence publishing is implemented as an integration layer with a periodic heartbeat and offline publication on logout. The game-title hook can publish the current title ID when the emulator lifecycle provides it. This is informational presence only; it does not prove that the game's network stack is connected.

## OAuth token and game token are different

- OAuth `access_token`: authorizes account API requests within the scopes approved by the player.
- OAuth `refresh_token`: renews the account session; refresh tokens may rotate.
- Game token (`nx2.`): returned separately by `/api/nex-token` for supported game authentication.

Never put the OAuth access token into a game's `nnex` claim. Never use a fabricated game token or credentials belonging to another application.

## In-game authentication and networking still missing

NEX is not multiplayer-ready. A real game session requires more than OAuth and account presence:

1. Implement the BAAS/ACC game-authentication flow and construct the correctly signed game `id_token`, including the NEX `nx2.` game token in the `nnex` claim.
2. Implement game-facing `nn::friends` IPC and back it with the NEX friends/presence cache.
3. Add title-scoped Nextendo DNS mappings.
4. Validate TLS certificate checks and SNI routing for the supported Nextendo hosts.
5. Implement the required NEX/PRUDP service communication and matchmaking.
6. Handle NAT checks, preserve the required UDP mapping, and implement peer discovery/P2P hole-punching.
7. Run end-to-end tests with two NEX accounts, two devices, and a supported title.

The pinned Eden source still requires HLE and network-stack work for those paths. Do not advertise in-game multiplayer as working until the full flow has passed real-title testing.

## Build and release status

- Eden baseline: tag `v0.2.0-rc1`, commit `8e373eb714aa01117ec885680477501cdb572112`.
- Published experimental build: `v0.1.0-alpha.48`.
- The `alpha.48` APK contains the initial Nextendo Settings entry, not the newer Games-screen card or updated logo.
- The updated Nextendo card, NEX logo, and friends UI are in [integration pull request #1](https://github.com/XDavi196/NEX/pull/1). They must pass CI and be published as a new APK before being considered released.

## Source locations

The Android implementation in the integration branch is under:

`integration/android/src/main/java/com/nex/nx/nextendo/`

Relevant files include:

- `NextendoAuthClient.kt` — OAuth, identity, API requests, and game-token requests.
- `NextendoAuthManager.kt` — account flow and navigation.
- `NextendoSessionStore.kt` — encrypted session and pending authorization storage.
- `NextendoSocialApi.kt` — friend-list parsing and social models.
- `NextendoFriendsDialog.kt` — read-only friend-list UI.
- `NextendoPresencePublisher.kt` — app/game presence publishing.
- `NextendoCallbackActivity.kt` — OAuth callback handling.

## Security checklist

- Keep all OAuth and game tokens out of source control, screenshots, URLs, and CI logs.
- Do not embed a client secret in the Android APK.
- Use only the NEX public Client ID and credentials provisioned for NEX.
- Confirm the redirect URI and scopes in the Nextendo Developer Portal before testing sign-in.
- Verify the friends and presence API contracts against the live service; do not assume that source code compiling proves service access.
