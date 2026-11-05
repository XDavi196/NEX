# NEX

**NEX** is an Android-only Nintendo Switch emulator project based on the Eden source tree, with a first-party integration for the NEX application registered on Nextendo Network.

- **Platforms:** Android phones and tablets only.
- **Emulator source:** Eden tag `v0.2.0-rc1`.
- **Requested Android build variant:** `Eden-Android-v0.2.0-rc1-optimized.apk`.
- **Android application ID:** `com.nex.nx`.
- **Nextendo Client ID:** `nxc_yAdR-14bOEq7`.
- **OAuth redirect URI:** `com.nex.nx://oauth/callback`.

NEX is based on Eden, not Den2. Its Nextendo registration and application identity belong to NEX; no other developer's Client ID, client secret, signing key or attestation material is reused.

## Project status

The repository currently contains the first Android Nextendo integration scaffold and project documentation. The complete Eden source tree has **not yet been imported**, the Kotlin scaffold has **not yet been compiled against Eden**, and there is **no NEX APK yet**. Do not treat login or multiplayer as implemented until they are wired into the emulator and tested.

## Nextendo permissions

The NEX public application requests these scopes:

`identity friends presence profil game.matchmaking`

Native Android authentication uses OAuth 2.0 Authorization Code + PKCE (S256). No client secret is embedded in the APK.

See [the Android integration plan](docs/NEXTENDO_ANDROID.md), [the pinned Eden source baseline](docs/EDEN_BASE.md), and [the Nextendo online/multiplayer architecture and attestation plan](docs/NEXTENDO_ONLINE_ARCHITECTURE.md).

## Source base and license

Upstream source: https://git.eden-emu.dev/eden-emu/eden

Pinned baseline: tag `v0.2.0-rc1`, commit `8e373eb714aa01117ec885680477501cdb572112`.

Eden is licensed under GPLv3-or-later. NEX must preserve the applicable license notices and provide corresponding source under the same license when distributing modified builds. See the upstream [license](https://git.eden-emu.dev/eden-emu/eden/src/tag/v0.2.0-rc1/LICENSE.txt).

## Scope

NEX is Android-only. This repository does not target Windows, Linux desktop, macOS, iOS, visionOS, or headset operating systems.

NEX is an independent community project and is not affiliated with or endorsed by Nintendo.
