# NEX

**A community-made Android Nintendo Switch emulator project, built from Eden's open-source code and with Nextendo Network support in development.**

NEX is being developed for Android phones and tablets. The goal is to build on Eden's work while giving the project its own identity and its own Nextendo integration. This is an ongoing community project, so things are still changing and not everything is ready yet.

> **Please keep in mind:** NEX is experimental. The first APK will be an early preview, not a finished or stable release. In-game online multiplayer is not working yet.

## A little about the project

NEX is a **fork of the Eden project**. It uses Eden's open-source code as its starting point, with changes and additions being developed for NEX. Eden deserves credit for the work that made this project possible, but NEX is maintained separately and is not an official Eden release.

The project currently targets Android only. The source base is Eden `v0.2.0-rc1`, pinned to commit `8e373eb714aa01117ec885680477501cdb572112`.

Some technical details:

- Android application ID: `com.nex.nx`
- OAuth callback: `com.nex.nx://oauth/callback`
- Nextendo public Client ID: `nxc_yAdR-14bOEq7`
- Build variant: Eden Android Optimized, customized for NEX

## Where things stand

The Android side of the Nextendo integration is being put together. The current code includes the OAuth sign-in flow, protected session storage, a basic account/profile view, a read-only friends list, and app presence updates. These features still need to be tested against the live service, so they should be considered experimental too.

There is an important difference between showing that someone is online and actually connecting two players inside a game. **NEX does not support in-game online multiplayer yet.** The emulator-side work for game authentication, friend services, DNS/TLS routing, matchmaking, NAT traversal, and peer-to-peer networking is still ahead of us.

We would rather be clear about what is unfinished than promise features that players cannot use. The [online development notes](docs/NEXTENDO_ONLINE_STATUS.md) explain the current work and what remains.

## Nextendo Network

NEX is being connected to Nextendo Network using its own public application registration. The Android sign-in flow uses OAuth 2.0 Authorization Code with PKCE (S256), with these requested permissions:

`identity friends presence profil game.matchmaking mods.favorites`

The sign-in callback is `com.nex.nx://oauth/callback`. The authorization page is `/oauth/authorize`, and the token exchange uses `/api/oauth/token`. PKCE values are generated for each sign-in; placeholder challenge values are never meant to be used as real credentials.

OAuth login and game authentication are separate parts of the integration. In particular, the Nextendo game token (`nx2.`) has a different purpose from the normal OAuth access token. NEX must handle it according to the service's game-authentication requirements; one token cannot simply replace the other.

If you are interested in the technical side, these notes go into more detail:

- [Android sign-in and account integration](docs/NEXTENDO_ANDROID.md)
- [Online networking architecture](docs/NEXTENDO_ONLINE_ARCHITECTURE.md)
- [Current implementation status](docs/NEXTENDO_ONLINE_STATUS.md)

## Builds and downloads

When a build is ready, experimental APKs will be published on the [Releases page](https://github.com/XDavi196/NEX/releases). You can also follow the [GitHub Actions page](https://github.com/XDavi196/NEX/actions) to see whether a build has passed.

The automated workflow is set up to publish a prerelease only after the full Android build succeeds. A failed or incomplete build will not be presented as a release. The expected APK name is `NEX-Android-v0.2.0-rc1-optimized.apk`.

Please treat early builds as test versions. Compatibility, performance, supported Android versions, and device requirements must be confirmed from each release's notes rather than assumed from Eden.

## A note about accounts and security

Please do not post access tokens, refresh tokens, game tokens, private keys, or other account credentials in issues or public chats. NEX should never need a client secret inside its APK. The Android integration is designed to keep session information protected with Android Keystore-backed encryption, but it is still under testing.

When signing in, use the Nextendo sign-in page and take a moment to review the permissions being requested.

## Games and system files

NEX does not come with commercial games, game dumps, firmware, console keys, or other copyrighted game data. You are responsible for obtaining and using any files you need in a lawful way. The project does not host or distribute copyrighted game files.

## A few important things to make clear

### About Eden

NEX is a **fork of Eden**, not an official Eden build. It is developed independently and is not affiliated with, sponsored by, maintained by, or endorsed by the Eden project or its developers. The original Eden project remains separate, and its contributors deserve credit for their work.

### About Nextendo Network

NEX is an independent third-party integration. It is not an official Nextendo Network app and is not affiliated with, operated by, sponsored by, or endorsed by Nextendo Network or its operators. Access to the service and its APIs is controlled by the service provider and may change at any time.

### About Nintendo

NEX is not affiliated with, sponsored by, authorized by, or endorsed by Nintendo. Nintendo, Nintendo Switch, and related names and marks belong to their respective owner. NEX is a community project, not an official Nintendo product.

All third-party names and trademarks belong to their respective owners. Their use here is only to describe the software and services that the project works with; it does not suggest an official partnership.

## Source code and license

NEX includes and modifies code from Eden `v0.2.0-rc1`. Eden's source code is licensed under **GPL-3.0-or-later**. When sharing builds or modified source, the project and anyone redistributing it must follow the licenses that apply to the included components, including the required copyright notices and corresponding source code.

- [Eden source code](https://git.eden-emu.dev/eden-emu/eden)
- [Eden `v0.2.0-rc1` release](https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.0-rc1)
- [Eden license](https://git.eden-emu.dev/eden-emu/eden/src/tag/v0.2.0-rc1/LICENSE.txt)

NEX is provided as-is, without warranty. Features may be incomplete, unstable, or changed as development continues.

## Find your way around

- [Source code](https://github.com/XDavi196/NEX)
- [Development pull request](https://github.com/XDavi196/NEX/pull/1)
- [Builds and checks](https://github.com/XDavi196/NEX/actions)
- [Downloads and prereleases](https://github.com/XDavi196/NEX/releases)
- [Nextendo Developer Portal](https://nextendo.network/developers)
