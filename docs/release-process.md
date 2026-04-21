# Release Process

Tunguska uses git tags plus GitHub Releases as the release source of truth.

Current published release: `v0.2.4`

## Version Rules

- Git tag format: `vMAJOR.MINOR.PATCH`
- Android `versionName`: `MAJOR.MINOR.PATCH`
- Android `versionCode`: must increase on every installable release

## SemVer Guidance

- Patch: fixes, hardening, validation improvements, or UX polish without intentional compatibility break
- Minor: backward-compatible feature additions
- Major: breaking changes to profile format, runtime assumptions, or compatibility expectations

## Files To Update

Version changes are made in [app/build.gradle.kts](../app/build.gradle.kts).

Update:

- `versionName`
- `versionCode`

## Release Steps

1. Update `versionName` and `versionCode`.
2. Commit and push the version bump to `main`.
3. Create an annotated tag, for example:
   `git tag -a v0.2.4 -m "Release v0.2.4"`
4. Push the tag:
   `git push origin v0.2.4`
5. GitHub Actions `Release` workflow builds the APKs and publishes them into the matching GitHub Release.

For `v0.2.x`, the release gate assumes:

- headed smoke on the chosen validation target is green before tagging
- Chrome direct-vs-VPN IP proof is green on the intended validation target for the published ABI
- the primary sideload artifact remains the `internal` APK

## Published Release Assets

For each tag, the workflow publishes:

- `tunguska-vX.Y.Z-arm64-v8a-internal.apk`
- `tunguska-vX.Y.Z-arm64-v8a-internal.apk.sha256`
- `tunguska-vX.Y.Z-x86_64-emulator-internal.apk`
- `tunguska-vX.Y.Z-x86_64-emulator-internal.apk.sha256`
- `tunguska-vX.Y.Z-arm64-v8a-debug.apk`
- `tunguska-vX.Y.Z-arm64-v8a-debug.apk.sha256`
- `tunguska-vX.Y.Z-x86_64-emulator-debug.apk`
- `tunguska-vX.Y.Z-x86_64-emulator-debug.apk.sha256`

The `arm64-v8a internal` APK is the primary sideload artifact. The `x86_64 emulator` APK exists to keep local emulator validation aligned with the packaged native runtime. `debug` APKs exist for troubleshooting only.

## Workflow Split

- [.github/workflows/ci.yml](../.github/workflows/ci.yml): build and test on pushes and pull requests
- [.github/workflows/release.yml](../.github/workflows/release.yml): publish APK assets into GitHub Releases on `v*` tags
