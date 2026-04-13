# Release Process

Tunguska uses git tags plus GitHub Releases as the release source of truth.

Current published release: `v0.1.0`

## Version Rules

- Git tag format: `vMAJOR.MINOR.PATCH`
- Android `versionName`: `MAJOR.MINOR.PATCH`
- Android `versionCode`: must increase on every installable release

## SemVer Guidance

- Patch: fixes, hardening, validation improvements, or UX polish without intentional compatibility break
- Minor: backward-compatible feature additions
- Major: breaking changes to profile format, runtime assumptions, or compatibility expectations

## Files To Update

Version changes are made in [app/build.gradle.kts](/C:/src/tunguska/app/build.gradle.kts).

Update:

- `versionName`
- `versionCode`

## Release Steps

1. Update `versionName` and `versionCode`.
2. Commit and push the version bump to `main`.
3. Create an annotated tag, for example:
   `git tag -a v0.1.1 -m "Release v0.1.1"`
4. Push the tag:
   `git push origin v0.1.1`
5. GitHub Actions `Release` workflow builds the APKs and publishes them into the matching GitHub Release.

## Published Release Assets

For each tag, the workflow publishes:

- `tunguska-vX.Y.Z-internal.apk`
- `tunguska-vX.Y.Z-internal.apk.sha256`
- `tunguska-vX.Y.Z-debug.apk`
- `tunguska-vX.Y.Z-debug.apk.sha256`

The `internal` APK is the primary sideload artifact. The `debug` APK exists for troubleshooting only.

## Workflow Split

- [ci.yml](/C:/src/tunguska/.github/workflows/ci.yml): build and test on pushes and pull requests
- [release.yml](/C:/src/tunguska/.github/workflows/release.yml): publish APK assets into GitHub Releases on `v*` tags

