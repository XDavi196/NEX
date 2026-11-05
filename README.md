# NEX

**NEX is an independent Android Nintendo Switch emulator project derived from the Eden open-source codebase, with an in-progress integration for Nextendo Network.**

<sub>Experimental project — not a stable release and not ready for online gameplay.</sub>

## Important status notice

NEX is under active development. The Android integration and build pipeline are still being validated. A successful APK build does **not** mean that every emulator feature, account endpoint, or online game mode has been tested.

**Online gameplay is not available yet.** The current Nextendo work is an integration scaffold for account authentication, profile/friends display, and presence reporting. The native emulator and game-network work required for matchmaking and peer-to-peer sessions is not complete. Do not expect the first successful APK to support playing games online.

The first public APK, when the build is verified, will be an **experimental preview**. Sign-in and account-related features will also need live testing against the Nextendo service before they can be considered reliable.

## What is NEX?

NEX is an Android-focused community fork of Eden. It aims to maintain an Android emulator build and develop a separate, first-party NEX integration with Nextendo Network.

- **Platform:** Android phones and tablets.
- **Upstream codebase:** Eden `v0.2.0-rc1`.
- **Pinned upstream commit:** `8e373eb714aa01117ec885680477501cdb572112`.
- **Android application ID:** `com.nex.nx`.
- **OAuth redirect URI:** `com.nex.nx://oauth/callback`.
- **Nextendo public Client ID:** `nxc_yAdR-14bOEq7`.
- **Build target:** Eden Android Optimized flavor, customized for NEX.

NEX is based on Eden source code. It is not based on Den2, and it does not reuse another emulator's private credentials, client secrets, signing keys, or attestation material.

## Project status

| Component | Current status |
|---|---|
| Eden source pin and Android overlay | Implemented; CI validation in progress |
| NEX package identity (`com.nex.nx`) | Implemented in the build overlay |
| OAuth Authorization Code + PKCE (S256) | Android integration scaffold implemented; live sign-in not yet verified end to end |
| Account profile | Read-only integration implemented; requires live service validation |
| Friends list | Read-only integration implemented; friend-request actions are not wired |
| App online/offline presence | Heartbeat and offline publication implemented; service behavior still needs live testing |
| In-game presence | Current title-ID hook added; not a game-network connection |
| BAAS `id_token` and signed `nnex` claim | Not implemented |
| Game-side `nn::friends` integration | Not implemented |
| Nextendo DNS and TLS/SNI routing | Not implemented |
| NEX/PRUDP, matchmaking, NAT traversal and P2P | Not implemented |
| Verified online gameplay | Not available |

See [the online implementation status](docs/NEXTENDO_ONLINE_STATUS.md) for the detailed list of missing emulator-side work and source files.

## Nextendo Network integration

NEX uses its own public application registration. The Android client uses OAuth 2.0 Authorization Code with PKCE S256 and requests these scopes:

`identity friends presence profil game.matchmaking mods.favorites`

The configured callback is:

`com.nex.nx://oauth/callback`

The authorization request uses the Nextendo Developer Portal route `/oauth/authorize`. The OAuth token exchange uses the documented `/api/oauth/token` endpoint. PKCE verifier and challenge values are generated dynamically for each authorization flow; placeholder values must never be hard-coded.

The OAuth access token and the separate Nextendo game token (the `nx2.` token) serve different purposes. The game token must be handled according to the Nextendo game-authentication contract; an OAuth access token must not be substituted for it. No client secret is embedded in the APK.

For implementation details, see:

- [Android authentication and account integration](docs/NEXTENDO_ANDROID.md)
- [Online architecture](docs/NEXTENDO_ONLINE_ARCHITECTURE.md)
- [Online implementation status](docs/NEXTENDO_ONLINE_STATUS.md)

## Building

The GitHub Actions workflow checks out the pinned Eden source, applies the NEX Android overlay, builds the Android Optimized variant, renames the output, and generates SHA-256 checksums.

Build artifacts are published only after the complete build succeeds. The initial public preview is intended to be a prerelease, not a stable release. Check the [GitHub Actions runs](https://github.com/XDavi196/NEX/actions) and [Releases](https://github.com/XDavi196/NEX/releases) pages for verified outputs.

The expected APK filename is:

`NEX-Android-v0.2.0-rc1-optimized.apk`

The exact supported Android versions, ABI, signing details, and device compatibility must be confirmed from the actual build and release metadata. Do not assume that every device supported by upstream Eden is automatically supported by NEX.

## Security and account safety

- Never share OAuth access tokens, refresh tokens, game tokens, private keys, or other account credentials in issues, screenshots, chat messages, or public logs.
- NEX must not embed a client secret in the Android application.
- Session data is intended to be stored using Android Keystore-backed encryption.
- Use the official Nextendo sign-in page and review the permissions requested during authorization.
- Report suspected credential exposure immediately and revoke affected sessions through the service where possible.

## Games, firmware, keys and other files

NEX does not include commercial games, game dumps, firmware, console keys, or copyrighted game data. Users are responsible for obtaining and using any required files lawfully and in accordance with the laws that apply to them. The project does not provide instructions or downloads for obtaining copyrighted material without authorization.

## Independence and trademark notices

### Eden

NEX is a **community fork derived from Eden's open-source codebase**. It is not an official Eden build or an official Eden project. NEX is not sponsored by, affiliated with, maintained by, or endorsed by the Eden project or its developers. Upstream Eden remains a separate project.

### Nextendo Network

NEX is an independent third-party client integration. It is not an official Nextendo Network application, and it is not sponsored by, affiliated with, operated by, or endorsed by Nextendo Network or its operators. Network access, API availability, account approval, and service behavior are controlled by the service provider and may change independently of NEX.

### Nintendo

NEX is not affiliated with, sponsored by, authorized by, or endorsed by Nintendo. Nintendo, Nintendo Switch, and related names and marks are trademarks of their respective owner. NEX is an independent community project and is not an official Nintendo product.

All third-party names, logos, and trademarks remain the property of their respective owners. These notices are intended to make the project's independent status clear; they do not imply a partnership or authorization.

## Source code and licensing

NEX derives from Eden `v0.2.0-rc1` and includes or modifies upstream components. Eden's source is licensed under **GPL-3.0-or-later**; the repository retains applicable upstream copyright and SPDX notices. Any redistribution of NEX binaries or modified source must comply with the licenses that apply to the included components, including providing corresponding source and preserving required notices where the license requires it.

- [Pinned Eden upstream release](https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.0-rc1)
- [Eden upstream source](https://git.eden-emu.dev/eden-emu/eden)
- [Eden license](https://git.eden-emu.dev/eden-emu/eden/src/tag/v0.2.0-rc1/LICENSE.txt)

This project is provided without warranty. Features may be incomplete, unstable, or removed during development.

## Links

- [NEX source repository](https://github.com/XDavi196/NEX)
- [Open pull request and implementation branch](https://github.com/XDavi196/NEX/pull/1)
- [Build history](https://github.com/XDavi196/NEX/actions)
- [Releases](https://github.com/XDavi196/NEX/releases)
- [Nextendo Developer Portal](https://nextendo.network/developers)