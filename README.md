# NEX

**NEX** is an Android-only Nintendo Switch emulator project based on the Eden source tree, with a first-party Nextendo Network integration registered for this project.

- **Platforms:** Android phones and tablets only.
- **Emulator base:** Eden. NEX is not based on Den2.
- **Network identity:** NEX uses the public Nextendo application ID registered for this project. It does not borrow another developer's Client ID, application credential, signing key, or attestation.
- **Online goal:** account sign-in, friends, presence, and real in-game multiplayer for titles supported by Nextendo.

## Project status

The repository is being structured and the first Android OAuth/PKCE integration layer is being prepared. The Eden source tree has not yet been imported into this repository, and no NEX APK is available yet.

## Nextendo application

The NEX public application Client ID supplied for this project is:

nxc_6BozwtiXy9n

Native Android authentication uses OAuth 2.0 Authorization Code + PKCE. No client secret is embedded in the APK. API requests identify this application with the X-Nextendo-Client-Id header.

See [the Android integration plan](docs/NEXTENDO_ANDROID.md) for the auth flow, token separation, redirect URI, and remaining game-authentication work.

## Source base

NEX is intended to track the official Eden source repository:

https://git.eden-emu.dev/eden-emu/eden.git

Den2 is not the source base. It may be consulted only as a technical reference when needed; NEX must use its own application identity and implementation.

## Scope

NEX is Android-only. This repository does not target Windows, Linux desktop, macOS, iOS, visionOS, or headset operating systems.

## Legal

NEX is an independent community project. Nintendo Switch is a trademark of Nintendo. NEX is not affiliated with or endorsed by Nintendo.
