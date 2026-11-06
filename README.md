# NEX

<div align="center">
  <img src="assets/nex-logo.jpg" alt="NEX official logo" width="384">
  <p><strong>An independent Android Nintendo Switch emulator project built from Eden's open-source code.</strong></p>
  <p><a href="https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.48">Get the first experimental APK</a></p>
</div>

NEX is a community project for Android phones and tablets. It started from the Eden codebase, and the work here is about giving that foundation a separate identity, maintaining an Android-focused build, and developing a first-party NEX integration with Nextendo Network.

The project is still young. Some parts are already in the app, some are being tested, and some of the online work still needs to be built. This README aims to show what is available today and what is still planned, without promising features that are not ready.

> **Please read before installing:** NEX is an experimental preview. The first APK builds successfully, but that does not mean every feature has been tested on real devices or against the live Nextendo service. In-game online multiplayer is not ready.

## What NEX is

NEX is a **community fork of Eden**. Eden's source code is the starting point for the emulator. NEX has its own Android application identity, its own changes, and its own Nextendo client registration. It is developed separately from upstream Eden.

The current source base is Eden `v0.2.0-rc1`, pinned to commit `8e373eb714aa01117ec885680477501cdb572112`.

- **Platform:** Android phones and tablets.
- **Application name:** NEX.
- **Android application ID:** `com.nex.nx`.
- **Nextendo OAuth callback:** `com.nex.nx://oauth/callback`.
- **Nextendo public Client ID:** `nxc_yAdR-14bOEq7`.
- **Build variant:** Eden Android Optimized, customized for NEX.

NEX is not based on Den2, and it does not reuse another emulator's client ID, private credentials, client secret, signing keys, or attestation material.

## Nextendo Network in NEX

The goal is to let NEX players connect their own Nextendo account, view their profile, and see their friends from inside the emulator.

NEX uses its **own public Client ID** from the Nextendo Developer Portal. The Android sign-in flow uses OAuth 2.0 Authorization Code with PKCE (S256). The app creates a fresh PKCE verifier and challenge for each login; no client secret is embedded in the APK.

The current requested scopes are:

`identity friends presence profil game.matchmaking mods.favorites`

### Where to find it

The first `v0.1.0-alpha.48` APK placed the initial Nextendo entry inside the app's Settings list. It was too easy to miss, so the development branch is adding a clearly labeled **Nextendo Network** card directly to the main Games screen. That card will open the NEX account connection flow and, after sign-in, the account panel.

### Connecting your account

1. Open NEX and tap **Nextendo Network** on the main Games screen in a build that includes the new home-screen card. In `alpha.48`, open the app's Settings list and choose **Nextendo Network**.
2. NEX opens the Nextendo authorization page in your browser.
3. Sign in on Nextendo and approve the permissions shown there.
4. The browser returns to NEX through `com.nex.nx://oauth/callback`.
5. After the connection completes, tap **Nextendo Network** again to open your account panel.

The account panel can show the username, Friend Code, and PID when those details are available. Choose **Friends** to request the friend list.

### Friends and presence

The current friend-list screen is **read-only**. It displays the friends returned by the authenticated Nextendo user-info endpoint and shows their reported presence when the service supplies it, such as online, offline, or in-game.

Adding friends, accepting or declining requests, removing friends, blocking, and other social actions are not wired yet. A presence label is not proof that a game session is connected.

### What online does not mean yet

**Playing a game online is not available in this preview.** The Android account and presence code is only one part of the work. Real game networking still needs:

- BAAS/ACC game authentication and a correctly signed `id_token` containing the NEX `nx2.` token in the `nnex` claim.
- Game-side `nn::friends` services backed by the NEX social data.
- Per-host Nextendo DNS mapping and verified TLS/SNI routing.
- NEX/PRUDP game-service communication and matchmaking.
- NAT checks, UDP mapping preservation, peer discovery, and P2P hole-punching.
- End-to-end tests with two NEX accounts and a supported game.

Until those pieces work together and have been tested, please do not expect online play to work. The technical details are tracked in [the online implementation status](docs/NEXTENDO_ONLINE_STATUS.md).

## Downloads

The first successful Android build is available as an experimental GitHub prerelease:

**[NEX v0.1.0-alpha.48 — Download APK](https://github.com/XDavi196/NEX/releases/tag/v0.1.0-alpha.48)**

The APK asset is named `NEX-Android-v0.2.0-rc1-optimized.apk`. The release also includes `SHA256SUMS.txt), which can be used to verify the file's SHA-256 checksum.

This is an early preview, not a stable release. The newer home-screen Nextendo card and the updated NEX app branding are being built into a subsequent version; they are not part of the original `alpha.48` package.

You can follow the [GitHub Actions runs](https://github.com/XDavi196/NEX/actions) to see build progress and the [Releases page](https://github.com/XDavi196/NEX/releases) for published APKs. A release is created only after the complete build and artifact upload succeed.

## Account and security notes

Please keep your account safe:

- Do not post OAuth access tokens, refresh tokens, game tokens, private keys, or other credentials in issues, screenshots, or public chat.
- NEX is a public Android OAuth client. It must not contain a client secret.
- The integration stores session data using Android Keystore-backed encryption.
- Review the permissions on the Nextendo authorization page before approving access.
- If you think a token has been exposed, revoke the affected session through the service where possible.

The OAuth authorization endpoint is `/oauth/authorize`; the token exchange endpoint is `/api/oauth/token`. The game token returned by `/api/nex-token` is separate from the OAuth access token and must not be substituted with it.

## Games and system files

NEX does not include commercial games, game dumps, firmware, console keys, or copyrighted game data. Users are responsible for obtaining and using any files they need lawfully and in line with the laws that apply to them. The project does not host or distribute copyrighted game files.

## Compatibility and troubleshooting

NEX is built for Android, but compatibility can vary with the phone, graphics driver, Android version, available memory, and the game being tested. A successful CI build only confirms that the configured source and toolchain compiled; it does not guarantee that every device or game will run well.

When reporting a problem, include the NEX release number, device model, Android version, game title, and a description of what happened. Never attach tokens, private account data, or other secrets to a bug report.

For sign-in problems, first check that the Nextendo Developer Portal registration uses the exact redirect URI `com.nex.nx://oauth/callback` and that the NEX client has the required scopes. If login succeeds but profile or friends cannot load, that may be a service/API issue rather than an emulator rendering issue.

## Development notes

The repository contains the Android Nextendo client, encrypted session handling, OAuth callback activity, profile/friends views, app-presence publisher, and the repeatable overlay that applies NEX changes to the pinned Eden checkout.

Useful technical documents:

- [Android authentication and account integration](docs/NEXTENDO_ANDROID.md)
- [Online networking architecture](docs/NEXTENDO_ONLINE_ARCHITECTURE.md)
- [Current online implementation status](docs/NEXTENDO_ONLINE_STATUS.md)
- [Pinned Eden source revision](docs/EDEN_BASE.md)

The open [integration pull request](https://github.com/XDavi196/NEX/pull/1) contains the current development work.

## Independence and trademarks

### Eden

NEX is a **fork of Eden's open-source codebase**, not an official Eden build. It is maintained separately and is not affiliated with, sponsored by, maintained by, or endorsed by the Eden project or its developers. The original Eden project and its contributors deserve credit for the code that NEX builds upon.

### Nextendo Network

NEX is an independent third-party client integration. It is not an official Nextendo Network application, and it is not affiliated with, operated by, sponsored by, or endorsed by Nextendo Network or its operators. API access, account permissions, and service availability are controlled by the service provider and may change independently of NEX.

### Nintendo

NEX is not affiliated with, sponsored by, authorized by, or endorsed by Nintendo. Nintendo, Nintendo Switch, and related names and marks belong to their respective owner. NEX is a community project, not an official Nintendo product.

All third-party names and trademarks belong to their respective owners. Their use here identifies the software or service being discussed; it does not imply an official partnership.

## Source code and license

NEX includes and modifies code from Eden `v0.2.0-rc1`. Eden's source code is licensed under **GPL-3.0-or-later**. Anyone redistributing NEX binaries or modified source must comply with the licenses that apply to the included components, including preserving required copyright notices and providing corresponding source code where required.

- [Eden source code](https://git.eden-emu.dev/eden-emu/eden)
- [Eden `v0.2.0-rc1` release](https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.0-rc1)
- [Eden license](https://git.eden-emu.dev/eden-emu/eden/src/tag/v0.2.0-rc1/LICENSE.txt)

NEX is provided as-is, without warranty. Features may be incomplete, unstable, or changed as development continues.

## Project links

- [Source repository](https://github.com/XDavi196/NEX)
- [Integration pull request](https://github.com/XDavi196/NEX/pull/1)
- [Build history](https://github.com/XDavi196/NEX/actions)
- [Releases and APK downloads](https://github.com/XDavi196/NEX/releases)
- [Nextendo Developer Portal](https://nextendo.network/developers)
