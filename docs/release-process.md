# Release Process

Tunguska uses git tags plus GitHub Releases as the release source of truth.

## Version Rules

- Git tag format: `vMAJOR.MINOR.PATCH`
- Android `versionName`: `MAJOR.MINOR.PATCH`
- Android `versionCode`: must increase for every installable release

Version values live in [app/build.gradle.kts](../app/build.gradle.kts).

## Release Gates

Before tagging:

- compile and unit tests pass
- focused UI/runtime instrumentation passes
- Chrome/IP proofs pass for both runtime strategies
- joint Tunguska + Anubis proof passes for both runtime strategies when automation/runtime behavior changed
- physical-device validation is updated when runtime, routing, or security claims changed
- docs match the behavior being released
- no live profile URLs, automation tokens, raw diagnostics, or local ignored artifacts are staged

Do not use Anubis as an inner-loop test. Use it as a pre-commit/pre-release gate for runtime and automation changes.

## Release Steps

1. Update `versionName` and `versionCode` in [app/build.gradle.kts](../app/build.gradle.kts).
2. Run release gates.
3. Commit and push the release changes to `main`.
4. Create an annotated tag:

```powershell
git tag -a vX.Y.Z -m "Release vX.Y.Z"
```

5. Push the tag:

```powershell
git push origin vX.Y.Z
```

6. Verify GitHub Actions release workflow completes.
7. Verify GitHub Release assets and checksums are present.

## Published Assets

The release workflow publishes ABI-specific APKs and checksums, including:

- `tunguska-vX.Y.Z-arm64-v8a-internal.apk`
- `tunguska-vX.Y.Z-arm64-v8a-internal.apk.sha256`
- `tunguska-vX.Y.Z-x86_64-emulator-internal.apk`
- `tunguska-vX.Y.Z-x86_64-emulator-internal.apk.sha256`
- debug APKs and checksums for troubleshooting

The `arm64-v8a internal` APK is the primary sideload artifact. The `x86_64 emulator` APK exists to keep local emulator validation aligned with the packaged native runtime.

## Workflows

- [.github/workflows/ci.yml](../.github/workflows/ci.yml): build and test on pushes and pull requests.
- [.github/workflows/release.yml](../.github/workflows/release.yml): publish APK assets into GitHub Releases on `v*` tags.

## Runtime Dependency Release Notes

If the release changes sing-box/libbox:

- note the `libbox-android` coordinate from `gradle/libs.versions.toml`
- confirm GitHub Packages contains the pinned package
- confirm `.tmp/maven` was not accidentally used as the only source
- confirm `geoip-ru.srs` is staged and included in the runtime assets
