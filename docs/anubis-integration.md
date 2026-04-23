# Anubis Integration

This document describes Tunguska's optional automation contract for Anubis-style orchestration.

## Product Rule

Automation is a secondary control surface. The normal user path remains the Tunguska UI. Automation must never create a second profile slot, bypass VPN permission requirements, or expose an unauthenticated start/stop API.

## Security Model

The supported integration surface is:

- explicit exported activity
- disabled by default
- gated by a rotatable token
- backed by the same Binder/runtime path used by the UI
- operating on the current sealed Tunguska profile
- using the current selected runtime strategy

The runtime control service itself is not exported.

## Tunguska Setup

1. Import and confirm a real VLESS + REALITY profile in Tunguska.
2. Start Tunguska manually once and grant Android VPN permission.
3. Open Security.
4. Open Automation.
5. Enable automation.
6. Copy or rotate the generated token as needed.

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

## Outcomes

Automation requests can produce these outcomes:

- `SUCCESS`
- `AUTOMATION_DISABLED`
- `INVALID_TOKEN`
- `VPN_PERMISSION_REQUIRED`
- `NO_STORED_PROFILE`
- `PROFILE_INVALID`
- `CONTROL_CHANNEL_ERROR`
- `RUNTIME_START_FAILED`
- `RUNTIME_STOP_FAILED`

If Android VPN permission has not been granted, the request fails explicitly. Tunguska does not try to obtain first-run VPN permission invisibly on behalf of another app.

## Diagnostics

Redacted audit exports include:

- whether automation is enabled
- whether VPN permission is ready
- selected runtime strategy
- last automation status
- last automation error
- last caller hint

The automation token is not exported.

## Validation

The canonical combined proof lives in `jointtesthost`, not the production app module:

- [jointtesthost/README.md](../jointtesthost/README.md)
- [tools/integration/run-anubis-e2e.ps1](../tools/integration/run-anubis-e2e.ps1)

Run it before committing automation/runtime changes and before cutting a release. Do not run it as the normal inner loop.

The runner:

- uses a headed emulator by default
- expects a real VLESS share link through `-ShareLink`, `TUNGUSKA_REAL_SHARE_LINK`, or a pre-staged emulator setting
- installs Tunguska, `trafficprobe`, `jointtesthost`, and Anubis
- prepares Tunguska once per runtime strategy
- runs the joint proof for `XRAY_TUN2SOCKS` and `SINGBOX_EMBEDDED`
- defaults to fast diagnostics on green runs

Acceptance bar:

- Tunguska is frozen while idle under Anubis control.
- Anubis unfreezes Tunguska before `START`.
- Tunguska reaches a live VPN runtime.
- A routed helper app observes a tunneled public IP different from direct baseline.
- Anubis issues `STOP`.
- Tunguska returns to idle.
- Direct public IP is restored.
- Anubis freezes Tunguska again after shutdown.

The broader local validation flow is documented in [docs/e2e-testing.md](./e2e-testing.md).
