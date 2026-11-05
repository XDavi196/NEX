# Eden source baseline

## Selected upstream release

NEX is based on the official Eden Android release source:

- Upstream repository: https://git.eden-emu.dev/eden-emu/eden
- GitHub mirror (same upstream source): https://github.com/eden-emulator/mirror
- Release/tag: `v0.2.0-rc1`
- Pinned commit: `8e373eb714aa01117ec885680477501cdb572112`
- Requested artifact flavor: `Eden-Android-v0.2.0-rc1-optimized.apk`
- Release page: https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.0-rc1

The `optimized` suffix identifies an Android build flavor, not a separate Eden source branch. The source must come from the pinned `v0.2.0-rc1` tag, then be built with the Android Optimized flavor.

## Required NEX identity override

The upstream Android Optimized flavor is named `genshinSpoof` in `src/android/app/build.gradle.kts`. Eden's original configuration sets:

- `applicationId = "com.miHoYo.Yuanshen"`
- app label `Eden Optimized`

NEX must **not** retain that spoofed package identity. The NEX build must preserve the Optimized native/build configuration while overriding:

- `applicationId = "com.nex.nx"`
- app label `NEX`
- OAuth callback `com.nex.nx://oauth/callback`

The Android namespace used internally by Eden's existing source can remain `org.yuzu.yuzu_emu`; it is not the same thing as the installable application ID. The callback handler and NEX-owned Kotlin integration code live under the NEX namespace `com.nex.nx`.

## Import requirements

The upstream release provides a source archive containing the source tree, submodules and CPM cache. When importing it into NEX:

1. Use the exact tag and commit above; do not substitute current Eden `master`.
2. Preserve the full upstream Git history where practical, or record the exact upstream tag/commit in the imported tree and build metadata.
3. Preserve Eden's GPLv3-or-later license and every third-party license/notice.
4. Keep NEX changes as separate, reviewable commits or patches.
5. Do not copy release signing keys, build secrets or developer credentials from any other project.
6. Build the Android Optimized flavor in CI and archive build logs/checksums.

## Hardware compatibility note

The upstream release notes describe Android Legacy as intended for Adreno A6xx and other older GPUs, while Android Optimized is the flavor for devices with frame-generation or other per-device features. NEX will retain the requested Optimized baseline initially, but its APK must be validated on target devices. An Adreno 610 should also be tested against the Legacy flavor before deciding which build to recommend for that device.

## Import status

The NEX repository currently contains integration scaffolding and project documentation only. The complete Eden source tree has not yet been imported, the NEX package override has not yet been applied to the Eden build files, and no NEX APK has been built.
