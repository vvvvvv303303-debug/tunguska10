# Anubis Integration

This document describes the current Tunguska automation contract intended for Anubis-style orchestration.

## Security Model

Tunguska does not expose a free-floating broadcast receiver for VPN start or stop.

The supported integration surface is:

- an explicit exported activity
- disabled by default
- gated by a rotatable automation token
- backed by the same internal Binder/runtime path that the UI uses

The runtime control service itself remains non-exported.

## Tunguska Setup

1. Import and save a real VLESS + REALITY profile in Tunguska.
2. Start Tunguska manually once and grant Android VPN permission.
3. Open the `Anubis Integration` card.
4. Enable automation.
5. Copy the generated token.

Disabling automation invalidates the current token. Rotating the token invalidates the previous token immediately.

## External Contract

Component:

- `io.acionyx.tunguska/.app.AutomationRelayActivity`

Actions:

- `io.acionyx.tunguska.action.AUTOMATION_START`
- `io.acionyx.tunguska.action.AUTOMATION_STOP`

Required extra:

- `automation_token`

Optional extra:

- `caller_hint`

Example shell commands:

```text
am start -W -n io.acionyx.tunguska/.app.AutomationRelayActivity -a io.acionyx.tunguska.action.AUTOMATION_START --es automation_token <token> --es caller_hint anubis
am start -W -n io.acionyx.tunguska/.app.AutomationRelayActivity -a io.acionyx.tunguska.action.AUTOMATION_STOP --es automation_token <token> --es caller_hint anubis
```

## Behavior

Automation requests operate on the currently sealed Tunguska profile.

They can return these outcomes internally and in the encrypted app-private status store:

- `SUCCESS`
- `AUTOMATION_DISABLED`
- `INVALID_TOKEN`
- `VPN_PERMISSION_REQUIRED`
- `NO_STORED_PROFILE`
- `PROFILE_INVALID`
- `CONTROL_CHANNEL_ERROR`
- `RUNTIME_START_FAILED`
- `RUNTIME_STOP_FAILED`

If Android VPN permission has not been granted yet, the automation request fails explicitly. Tunguska does not try to obtain first-run VPN permission invisibly on behalf of another app.

## Diagnostics

Redacted diagnostic bundles include:

- whether automation is enabled
- whether VPN permission is ready
- last automation status
- last automation error
- last caller hint

The automation token is not included in diagnostic exports.
The automation status record is no longer written to external app storage.

## Validation

The canonical combined Tunguska + Anubis proof lives outside the production app inside the neutral host module documented in [jointtesthost/README.md](../jointtesthost/README.md).

The runner for that proof is [tools/integration/run-anubis-e2e.ps1](../tools/integration/run-anubis-e2e.ps1).

That runner:

- expects a real VLESS share link via CLI or `TUNGUSKA_REAL_SHARE_LINK`
- uses a headed emulator by default
- resolves the Android SDK from `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or the standard local SDK location
- resolves the Anubis checkout from `-AnubisRepo` or a sibling `../anubis` repo

The broader local validation flow, supporting scripts, and privacy rules are documented in [docs/e2e-testing.md](./e2e-testing.md).

The intended acceptance bar for that harness is:

- Tunguska package frozen while idle
- Anubis unfreezes Tunguska before `START`
- Tunguska reports successful automation start and reaches a live VPN runtime
- a routed app observes a different public IP through the tunnel
- Anubis issues `STOP`
- Tunguska returns to `IDLE`
- direct public IP is restored
- Anubis freezes Tunguska again after shutdown
