# Nextendo Network integration — Android

## Application identity

NEX uses the public application Client ID supplied for the NEX registration:

nxc_6BozwtiXy9n

This is the NEX application's identity, not a player token and not a client secret. Requests to the Nextendo API must include:

X-Nextendo-Client-Id: nxc_6BozwtiXy9n

Do not copy the Client ID, application token, private signing key, BAAS signing key, release attestation or any other private build material from another emulator developer. The NEX release must use only credentials and attestations issued/configured for the NEX application itself.

## Native app type and OAuth

NEX is a distributed native Android application. It must be treated as a public OAuth application and use Authorization Code with PKCE (S256). Do not embed a Client Secret in the APK.

- Authorization endpoint: https://nextendo.network/api/oauth/authorize
- Token endpoint: https://nextendo.network/api/oauth/token
- Game-token endpoint: https://nextendo.network/api/nex-token
- Registered redirect URI (initial NEX package): com.xdavi196.nex://oauth/callback
- Scopes requested: identity friends presence game.matchmaking

The redirect URI must be registered in the NEX developer application settings and must match the Android intent filter and the token exchange byte-for-byte. If the portal has a different redirect configured, change the NEX constant and manifest to match the portal rather than silently accepting a different callback.

## Sign-in sequence

1. Generate a cryptographically random PKCE code_verifier (43–128 URL-safe characters), an S256 code_challenge, and a random state.
2. Open the authorization URL in the system browser or a Chrome Custom Tab. Never collect the Nextendo password inside NEX.
3. Receive the callback through the registered Android deep link.
4. Compare the returned state with the pending state. Reject missing or mismatched values.
5. Exchange the one-time code at /api/oauth/token using grant_type=authorization_code, the same client_id, the exact redirect_uri, and the original code_verifier.
6. Save access and refresh tokens only in encrypted storage backed by Android Keystore. Never log tokens or include them in URLs.
7. Refresh the access token using grant_type=refresh_token when required. Persist the newly returned refresh token immediately because refresh tokens rotate.

All requests to Nextendo endpoints must carry the NEX application identity header. Requests that require a player session also carry Authorization: Bearer <access_token>.

## Account token vs game token

These credentials are not interchangeable:

- OAuth access token: account APIs such as identity, friends and presence.
- NEX game token (nx2): obtained from GET /api/nex-token with the OAuth access token and the NEX application identity header. It is the credential for supported online game authentication.

Fetch or renew the game token at game launch / expiry and keep its lifetime separate from the OAuth access-token lifetime. Never put an OAuth access token in the game's nnex claim.

## In-game authentication still required

A successful OAuth login proves only that the user authorized the NEX application. It does not prove that a game can log in.

The emulator's ACC/BAAS path must construct the game identity using the NEX account PID and the freshly issued nx2 credential in the nnex claim. The BAAS ID token must be correctly signed and accepted by the current Nextendo gateway. Any application attestation, signing key, application token, request headers or release proof required by the current Nextendo gateway must be provisioned for NEX itself by the Nextendo application owner/operator. Do not reuse another developer's private key or attestation.

The NEX project must not claim in-game multiplayer support until this path and a real supported-title session have passed testing.

## Android callback manifest

Register an exported callback activity (or an equivalent verified deep-link handler) with an intent filter for:

- scheme: com.xdavi196.nex
- host: oauth
- path: /callback

Validate state inside the application before exchanging the code. Do not treat a deep-link callback as authenticated merely because it was opened.

## Initial implementation checklist

- [x] Record the NEX-owned public Client ID in the project plan.
- [x] Define the NEX application-identity header.
- [x] Define OAuth PKCE endpoints and the NEX callback contract.
- [ ] Confirm the exact redirect URI in the developer portal.
- [ ] Import the Eden Android source tree into this repository.
- [ ] Wire the auth manager into the Eden Android activity/navigation lifecycle.
- [ ] Add Android Keystore encrypted session storage and tests.
- [ ] Implement refresh-token rotation.
- [ ] Implement /api/nex-token and validate the returned token shape.
- [ ] Complete NEX-owned BAAS/application attestation requirements with Nextendo.
- [ ] Integrate the nx2 token into ACC/BAAS game login.
- [ ] Test login, friends, presence and at least one real multiplayer title.
