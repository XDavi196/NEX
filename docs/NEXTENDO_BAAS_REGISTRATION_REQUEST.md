# NEX — retired BAAS public-key registration proposal

<badge>SUPERSEDED — DO NOT SUBMIT</badge>

This document is retained as a historical record only. The per-installation public JWK registration proposal is no longer a valid implementation path for NEX.

## Why it was retired

The Nextendo founder explicitly answered the NEX project that:

- Client-generated public keys cannot be registered in the Nextendo JWKS.
- Third-party RS256 BAAS tokens signed by client applications will not be accepted.
- The JWKS is not open to per-installation registrations.
- Game tokens are issued by Nextendo's servers after the player signs in.
- There is currently no third-party client certification process or BAAS token contract for NEX to implement.

This means the previously proposed Android Keystore RSA-2048 key and public-JWK request must not be used to seek registration, and the NEX runtime must not sign or deliver a client-generated BAAS JWT.

## Current NEX identity (unchanged)

- Android application ID: `com.nex.nx`
- NEX public OAuth Client ID: `nxc_yAdR-14bOEq7`
- OAuth redirect: `com.nex.nx://oauth/callback`
- OAuth flow: Authorization Code + PKCE (S256)

NEX continues using its own application identity. No DEN/Citron Client ID, access token, refresh token, account file, signing key or attestation material is used.

## What is needed instead

NEX needs a new official Nextendo-side game-authentication mechanism, such as a server-issued game credential or a future documented third-party certification flow. Nextendo would need to specify how that credential is bound to the NEX application and player account, and how it is passed into ACC/LoginEx.

Until Nextendo provides such a mechanism, BAAS/ACC game authentication remains blocked and the NEX runtime must stay fail-closed.

Never send access tokens, refresh tokens, nx2 tokens, BAAS JWTs, authorization codes, PKCE verifiers, passwords or private keys in a support ticket or chat.
