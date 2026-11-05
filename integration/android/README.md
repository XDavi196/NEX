# Android Nextendo integration scaffold

This folder contains the first NEX-owned Android integration components:

- NextendoAuthClient.kt — OAuth Authorization Code + PKCE, refresh-token exchange, NEX application identity header, and the separate nx2 game-token request.
- NextendoSessionStore.kt — encrypted token persistence using Android Keystore and AES-GCM, with no plaintext fallback.

## Integration status

These files are a source scaffold. They have not yet been compiled against the Eden Android project because the Eden source tree has not yet been imported into this repository.

Before wiring them into the emulator:

1. Confirm the redirect URI in the NEX developer portal is exactly com.xdavi196.nex://oauth/callback.
2. Add the matching Android intent filter to the callback activity.
3. Add browser/Custom Tabs UI and a lifecycle-safe pending OAuth transaction store.
4. Call the auth client only from a background dispatcher.
5. Persist the OAuth result immediately, then fetch and persist the nx2 token separately.
6. Integrate the nx2 credential into the NEX ACC/BAAS game-auth path using only NEX-owned application credentials and signing/attestation material.
7. Compile and test on Android before calling the integration complete.

Never print access tokens, refresh tokens, nx2 tokens, callback codes, PKCE verifiers, or app-attestation material to Android logs.
