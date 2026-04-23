# Privacy

## Position

Tunguska keeps VPN configuration, routing policy, runtime state, and automation metadata local to the device unless the user explicitly connects, exports an artifact, or uses an optional integration.

The app does not include analytics, advertising identifiers, or telemetry by default.

## Local Data

Tunguska stores:

- the active imported profile
- routing preferences and custom direct domains
- selected runtime strategy
- runtime status metadata
- egress IP observation state
- encrypted profile backups
- encrypted redacted audit bundles
- encrypted automation settings and last automation status
- optional secondary subscription state if that frozen surface is used

Primary storage is app-private and encrypted where it contains profile, automation, or export material.

## Network Activity

Tunguska sends network traffic in these cases:

- VPN traffic to the configured server while connected.
- Public-IP probes used to show current IP while idle and exit IP while connected.
- Optional subscription fetches if the secondary subscription surface is used.
- Developer/build tooling outside the Android app, such as downloading dependencies or releases.

The public-IP probe is a product feature, not telemetry. It is used to show the user whether the observed IP is direct or tunneled. If all probe fallbacks fail, the UI shows a clear "can't detect" style state instead of inventing a value.

## Permissions

Current app permissions:

- `INTERNET`: required for VPN traffic and IP probes.
- `CAMERA`: required only for live QR scanning.

The core VPN flow does not require camera permission. Image QR import uses Android's picker flow rather than broad storage permission.

## Import Privacy

Import happens locally:

- pasted profile payloads are parsed and validated on-device
- camera QR decoding is on-device
- image QR decoding is on-device after the user picks an image
- import is staged and reviewed before storage changes

Unsupported safe parameters can be ignored with warnings. Unsafe parameters are rejected.

## Runtime Privacy

The runtime is designed to avoid accidental local exposure:

- no unauthenticated localhost proxy surface
- no enabled xray or sing-box management API
- no release-path debug listener
- loopback is treated as shared local surface and audited when Android permits socket inventory
- regional direct rules intentionally keep matching destinations outside the VPN tunnel

If Android restricts low-level socket inventory, Tunguska reports a limited exposure check instead of treating the OS restriction as a user-data event.

## Export Privacy

There are two export classes:

- encrypted full-profile backup
- encrypted redacted audit bundle

The redacted audit bundle is intended for troubleshooting without exposing raw secrets. It contains summarized profile/runtime/storage/routing/automation state and hashes where possible.

After successful export, Android's share sheet is opened with a `FileProvider` content URI and read permission. The user chooses where the exported file goes.

Automation tokens are not included in exports.

## Automation Privacy

Automation is disabled by default. When enabled:

- the token is stored locally
- token rotation invalidates the previous token
- last status and caller hint are stored locally in encrypted form
- requests operate only on the current sealed Tunguska profile

The automation relay does not accept unauthenticated background start/stop requests.

## What Tunguska Does Not Promise

Tunguska does not currently promise:

- VPN invisibility
- hidden Android VPN transport state
- hidden tunnel interface visibility
- anonymity from the configured VPN server
- protection on rooted or fully compromised devices
- user-authentication-gated reveal flows for every exported artifact

## User Control

The user controls:

- whether to import or replace a profile
- whether to connect or disconnect
- whether to enable Russia direct and custom direct domains
- whether to grant camera permission
- whether to export backup or audit files
- whether to enable automation
- whether to share exported files outside the app
