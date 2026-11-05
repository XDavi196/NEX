# Eden source baseline

## Selected upstream release

NEX is based on the official Eden Android release source:

- Upstream repository: https://git.eden-emu.dev/eden-emu/eden
- Release/tag: `v0.2.0-rc1`
- Pinned commit: `8e373eb714aa01117ec885680477501cdb572112`
- Requested artifact flavor: `Eden-Android-v0.2.0-rc1-optimized.apk`
- Release page: https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.0-rc1

The `optimized` suffix identifies an Android release/build variant, not a different Eden source repository or tag. The corresponding source must come from the pinned `v0.2.0-rc1` tag, then be built with the matching Android Optimized configuration.

## Import requirements

The upstream release provides a source archive containing the source tree, submodules and CPM cache. When importing it into NEX:

1. Use the exact tag and commit above; do not substitute current Eden `master`.
2. Preserve the full upstream Git history where practical, or record the exact upstream tag/commit in the imported tree and build metadata.
3. Preserve Eden's GPLv3-or-later license and every third-party license/notice.
4. Keep NEX changes as separate, reviewable commits or patches.
5. Do not copy release signing keys, build secrets or developer credentials from any other project.
6. Build the Android Optimized variant in CI and archive build logs/checksums.

## Compatibility note

The upstream release notes describe the Android Legacy build as intended for Adreno A6xx and other older GPUs, while Android Optimized is described for devices with frame-generation or other per-device features. NEX will honor the requested Optimized source/build baseline initially, but it must validate its chosen artifact on target hardware. An Adreno 610 device should also be tested against the Legacy variant before deciding which APK to recommend for that device.

## Import status

The NEX repository currently contains integration scaffolding and project documentation only. The complete Eden source tree has not yet been imported and no NEX APK has been built.
