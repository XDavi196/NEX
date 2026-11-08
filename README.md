# NEX

**An independent Android Nintendo Switch emulator project based on Eden, with its own Nextendo Network client integration.**

NEX is being developed for Android phones and tablets. The project uses the Eden source code as its emulator foundation and maintains a separate Android application identity and a first-party Nextendo integration for NEX.

> **Current preview status — 6 November 2026:** NEX has one published experimental APK, `v0.1.0-alpha.48`. It is an early build, not a stable release. Account sign-in and social features are still being validated, and in-game online multiplayer is **not available**.

## Project overview

- **Platforms:** Android phones and tablets.
- **Emulator base:** Eden `v0.2.0-rc1`, pinned to commit `8e373eb714aa01117ec885680477501cdb572112`.
- **Android application ID:** `com.nex.nx`.
- **OAuth redirect URI:** `com.nex.nx://oauth/callback`.
- **Nextendo public Client ID:** `nxc_yAdR-14bOEq7`.
- **Build target:** Eden Android Optimized, customized for NEX.

NEX is a fork of Eden, not a fork of Den2. Den2 may be used as a technical reference for publicly observable behavior, but NEX has its own implementation and must use only the application identity and credentials registered for NEX. No other emulator's Client ID, client secret, private signing keys, or attestation material are reused.

## Nextendo Network

NEX is building a native account connection so players can authorize their own Nextendo account, view their profile, and see their friends from inside the emulator.

The Android sign-in flow uses OAuth 2.0 Authorization Code with PKCE (S256). NEX opens the authorization page in the system browser and does not collect the player's Nextendo password. No client secret is embedded in the APK. API requests identify the application with the `X-Nextendo-Client-Id` header.

**Requested scopes:** `identity friends presence profil game.matchmaking mods.favorites`.

### Where to find Nextendo

The published `alpha.48` APK contains the first Nextendo entry in the app's Settings list.

The development branch in [pull request #1](https://github.com/XDavi196/NEX/pull/1) adds a more visible **Nextendo Network** card to the main Games screen and the updated NEX logo. Those changes are not part of `alpha.48`; they will be included in a new APK only after the updated Android build and artifact upload succeed.

### Connecting an account

1. Open **Nextendo Network**. In `alpha.48`, find it in the Settings list; in a newer build with the home-screen card, open it from the Games screen.
2. NEX opens the Nextendo authorization page in your browser.
3. Sign in and review the permissions requested by the NEX application.
4. After authorization, the browser returns to NEX through `com.nex.nx://oauth/callback`.
5. Open **Nextendo Network** again to view the account panel.

The account panel is designed to show the available username, PID, and Friend Code. The Friends action opens the player list.

### Friends and presence

The development branch contains a NEX-owned friends screen inspired by the compact player-list layout familiar to Den2 users. It uses the NEX OAuth session and NEX Client ID to request `GET /api/friends`. When the service returns the relevant fields, a friend row can show an avatar, display name, Friend Code, online state, and the game reported by presence.

The current screen is **read-only**. It can display incoming requests returned by the API, but sending, accepting, declining, removing, or blocking friends is not enabled. The endpoint and response format still need to be verified against the live service with the NEX public client; the presence display must not be treated as proof that a game session or peer-to-peer connection is working.

### Online multiplayer is not ready

Connecting an account is only one part of online support. NEX does not currently provide working in-game Nextendo multiplayer. The remaining work includes:

- Implementing the BAAS/ACC game-authentication path and a correctly signed game `id_token` with the NEX `nx2.` token in the `nnex` claim.
- Implementing game-facing `nn::friends` services backed by NEX account and presence data.
- Adding title-scoped Nextendo DNS mapping and validating TLS/SNI routing.
- Implementing the required NEX/PRUDP game-service communication and matchmaking.
- Handling NAT checks, UDP mapping preservation, peer discovery, and P2P hole-punching.
- Testing a supported title end to end with two NEX accounts and two devices.

An OAuth access token is not a game token, and a successful account login does not establish multiplayer compatibility. Do not expect online play to work until these components have been implemented and tested together.

## Downloads

The first successfully published experimental Android build is:

**[NEX v0.1.0-alpha.48 — Releases](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.48)**

The APK asset is named `NEX-Android-v0.2.0-rc1-optimized.apk`. The release also includes `SHA256SUMS.txt` for checksum verification.

This package predates the updated Games-screen card, friends interface, and NEX logo change. The next build is being tracked in [GitHub Actions](https://github.com/XDavi196/NEX/actions). The current updated integration is in [pull request #1](https://github.com/XDavi196/NEX/pull/1); do not treat it as released until a new APK appears on the [Releases page](https://github.com/XDavi196/NEX/releases).

## Security and account safety

- Never share OAuth access tokens, refresh tokens, game tokens, authorization codes, PKCE verifiers, or private keys in issues or screenshots.
- NEX is a public Android OAuth client and must not contain a client secret.
- The integration is designed to keep session data encrypted with Android Keystore.
- Review the permissions shown by Nextendo before authorizing NEX.
- Revoke a session through the service if you believe its credentials have been exposed.

The OAuth authorization endpoint is `/oauth/authorize`, and the token endpoint is `/api/oauth/token`. The separate game token endpoint is `/api/nex-token`; its token must never be substituted for an OAuth access token or vice versa.

## Games and system files

NEX does not include commercial games, firmware, console keys, or copyrighted game data. Users are responsible for obtaining and using their own files lawfully. The project does not host or distribute game dumps.

## Compatibility and bug reports

Compatibility depends on the Android device, GPU and driver, Android version, available memory, and the game being tested. A successful CI build confirms that the configured source compiled; it does not guarantee that every device or game will run correctly.

When reporting a problem, include the NEX release number, device model, Android version, game title, and the steps that reproduce the issue. Do not attach account credentials or tokens.

## Development documentation

- [XDavi196 Projects & Builds portal](https://xdavi196.github.io/NEX/builds/) (GitHub Pages site source: `docs/builds/`)

- [Android authentication and Nextendo account integration](docs/NEXTENDO_ANDROID.md)
- [NEX architecture and source integration](docs/ARCHITECTURE.md)
- [Current integration pull request](https://github.com/XDavi196/NEX/pull/1)
- [Build history](https://github.com/XDavi196/NEX/actions)
- [Releases and APK downloads](https://github.com/XDavi196/NEX/releases)
- [Nextendo Developer Portal](https://nextendo.network/developers)

## Independence, attribution, and trademarks

NEX is an independent community fork of Eden's open-source code. It is not an official Eden release and is not affiliated with or endorsed by the Eden project or its developers. The original Eden project and contributors deserve credit for the source code on which NEX is based.

NEX is an independent third-party Nextendo client integration. It is not an official Nextendo Network application and is not affiliated with, operated by, sponsored by, or endorsed by Nextendo Network or its operators. Service access, API behavior, and account permissions are controlled by the service provider and may change independently of NEX.

NEX is not affiliated with, sponsored by, authorized by, or endorsed by Nintendo. Nintendo, Nintendo Switch, and related names and marks belong to their respective owner. All third-party names and trademarks belong to their respective owners; their use identifies the software or service being discussed and does not imply an official partnership.

## Source code and license

NEX uses Eden `v0.2.0-rc1` as its source baseline. Eden's code is licensed under GPL-3.0-or-later. Redistribution of NEX source or binaries must comply with the licenses and notices that apply to Eden and every included component, including providing corresponding source code where required.

- [Eden source repository](https://git.eden-emu.dev/eden-emu/eden)
- [Eden `v0.2.0-rc1` release](https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.0-rc1)
- [Eden license](https://git.eden-emu.dev/eden-emu/eden/src/tag/v0.2.0-rc1/LICENSE.txt)

NEX is provided as-is, without warranty. Features may be incomplete, unstable, or changed as development continues.


## Self-hosted Forgejo mirror

A deployment bundle for a dedicated NEX Forgejo instance is available in [deploy/forgejo](deploy/forgejo/README.md). It includes Docker Compose, Caddy HTTPS configuration, GitHub pull-mirror instructions, and a release synchronizer for release notes and APK assets.

The intended URL format is `https://git.YOUR-DOMAIN/XDavi196/NEX/releases`. This is deployment configuration only: the Forgejo service is not publicly live until a server and a domain controlled by the project owner are configured.
