# Privacy

## Product Position

Tunguska is designed to keep VPN configuration and runtime state local to the device unless the user explicitly connects to a VPN server, exports an artifact, or uses the frozen subscription surface.

The app does not include analytics or telemetry by default.

## Data The App Stores Locally

Tunguska stores the following categories locally:

- imported VPN profiles
- regional bypass preferences and the one-time decision for existing profiles
- derived runtime metadata such as config hashes and route-preview state
- encrypted profile backups and encrypted redacted diagnostic bundles
- encrypted automation status records for the Anubis-style control path
- optional subscription configuration and related trust state for the frozen secondary surface

Profile and artifact storage uses app-private encrypted files. The primary product path does not rely on external storage.

## Data The App Sends Over The Network

The app sends data only in these cases:

- when the user connects to a configured VPN server
- when the user explicitly uses the frozen subscription-update surface
- when the user downloads a release or source dependency outside the app itself

Tunguska does not send usage analytics, crash telemetry, or advertising identifiers.

## Permissions

The current app manifest requests:

- `INTERNET`
- `CAMERA`

Permission intent:

- `INTERNET`: required for VPN traffic and optional subscription fetches
- `CAMERA`: only for live QR scanning

The core VPN flow does not require camera permission.

## Import Privacy

Import happens locally on the device.

- pasted share links are validated locally
- QR payloads are decoded locally with on-device ML Kit
- imported profiles are staged before confirmation rather than written immediately

Unsupported parameters may be ignored with warnings, but unsafe flags are rejected.

New and newly imported profiles default to `Russia direct`. Existing encrypted profiles are not silently changed; the app records a one-time local decision if the user accepts or declines the prompt.

## Diagnostic Privacy

Tunguska supports two export classes:

- encrypted full-profile backup
- encrypted redacted diagnostic bundle

The redacted diagnostic bundle is designed to avoid exposing raw secrets. It includes hashes and summarized runtime state rather than dumping plain profile credentials into plaintext files.

## Runtime Privacy Controls

The current runtime lane is designed to minimize local leakage.

- no unauthenticated localhost proxy in the active lane
- no enabled Xray or sing-box management API in the active lane
- no release-path debug endpoints
- runtime listener audit to detect unexpected local exposure
- regional bypass is enforced client-side, so direct destinations do not traverse the VPN server path

## What Tunguska Does Not Promise Yet

- complete invisibility from VPN detection
- a finished real-device detector report across every supported device class
- user-authentication-gated reveal flows for every exported artifact

## Subscription Surface

Tunguska still contains a secondary subscription-management surface from earlier implementation work.

- it is not the primary product path
- it remains frozen
- it can perform HTTPS-only fetches with trust controls if used
- it is intentionally de-emphasized in the current UI and documentation

## User Control

The user controls:

- whether to import a profile
- whether to connect
- whether to grant camera permission
- whether to export encrypted backup or redacted diagnostics
- whether to use the frozen subscription surface at all
