# Device Validation

This document defines the physical-device validation matrix for Tunguska.

## Purpose

Emulator tests are useful for UI, orchestration, and repeatable dataplane checks. Physical-device validation is still required before making stronger claims about runtime security, bypass resistance, and detector behavior on real Android hardware.

## Runtime Strategies Under Test

Validate both user-selectable runtime strategies when runtime behavior changes:

- `XRAY_TUN2SOCKS`
- `SINGBOX_EMBEDDED`

If a change is provably UI-only, focused emulator tests are enough. If a change affects routing, runtime startup, health, egress IP probing, regional bypass, or automation, run at least the relevant physical-device subset before release.

## Product Success Flow

For each runtime strategy:

1. Install the current `arm64-v8a` build on a physical Android device.
2. Import a real VLESS + REALITY profile by paste.
3. Import the same profile by camera QR.
4. Import the same profile by image QR.
5. Confirm profile review and save.
6. Grant Android VPN permission.
7. Connect.
8. Confirm Home shows an exit IP through the active engine path.
9. Confirm Chrome or another routed app uses the VPN egress IP.
10. Disconnect.
11. Confirm direct public IP is restored.

## Routing Validation

Validate:

- full tunnel
- allowlist
- denylist
- Russia direct enabled
- Russia direct disabled
- custom direct domain add/remove
- non-Russian public-IP endpoint still routed through VPN when policy says proxy
- Russian destination remains direct when Russia direct applies
- route test result matches expected policy for host, IP, port, protocol, and optional package simulation

Route test is not a live traffic probe. It is a policy simulation. Live routing still needs browser/helper-app validation.

## Security Validation

Blocking findings:

- unauthenticated local SOCKS/HTTP proxy
- reachable xray or sing-box management API
- debug or pprof-style endpoint
- split-routing bypass by a non-rooted app
- excluded app obtaining tunnel egress when it should not
- underlying-network leak while VPN is active
- runtime start/stop failure in normal use
- raw secrets in exported diagnostics
- automation start/stop accepted without enablement and valid token

Non-blocking by itself:

- Android `TRANSPORT_VPN`
- foreground notification visibility
- visible tunnel interface such as `tun0`
- generic VPN detector output that only proves a VPN exists

## Suggested External Checks

Use detector and bypass tools as references, not as unquestioned product requirements. A finding blocks release only when it demonstrates a practical bypass or leak.

Known useful references:

- `https://github.com/xtclovver/RKNHardering`
- `https://github.com/cherepavel/VPN-Detector`
- `https://github.com/runetfreedom/per-app-split-bypass-poc`
- Termux-style excluded-app public-IP checks

Helper scripts:

- [tools/device-validation/prepare-detector-workspace.ps1](../tools/device-validation/prepare-detector-workspace.ps1)
- [tools/device-validation/new-validation-report.ps1](../tools/device-validation/new-validation-report.ps1)

## Automation And Anubis

Validate automation after standalone Tunguska behavior is green.

1. Enable automation in Tunguska.
2. Copy or rotate the current token.
3. Confirm invalid-token requests fail.
4. Confirm disabled-integration requests fail.
5. Configure Anubis with Tunguska package and token.
6. Confirm Anubis unfreezes Tunguska before `START`.
7. Confirm Tunguska reaches a live runtime.
8. Confirm a routed app observes tunnel egress.
9. Confirm Anubis stops Tunguska.
10. Confirm direct IP is restored.
11. Confirm Anubis freezes Tunguska again.

Local emulator-side joint proof is documented in [docs/e2e-testing.md](./e2e-testing.md).

## Evidence To Capture

For each device/runtime validation run, capture:

- app version and commit
- Android version and device model
- runtime strategy
- profile type, without raw profile URL
- direct IP baseline
- tunneled IP
- direct IP after stop
- route policy tested
- detector/bypass tool results
- redacted audit export
- screenshots only when they do not expose live secrets

Keep live profile material and raw logs with secrets out of git.
