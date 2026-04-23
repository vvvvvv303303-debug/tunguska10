# Threat Model

## Scope

This document covers Tunguska's current Android VPN product:

- local import of VLESS + REALITY profiles
- encrypted local profile storage
- Android `VpnService` runtime sessions
- xray+tun2socks and sing-box embedded runtime lanes
- routing policy, regional direct rules, and route test
- token-gated automation
- backup and redacted audit export

## Assumed Adversaries

Tunguska primarily defends against:

- a hostile co-installed Android app without root
- a malicious or malformed profile payload
- runtime regressions that open local proxy, debug, or management surfaces
- split-routing mistakes that route an app or destination through the wrong path
- accidental secret leakage through diagnostics or exports
- external automation requests without user opt-in or a valid token

The model does not assume protection against:

- rooted attackers
- a compromised OS or kernel
- malicious firmware
- a VPN server operator
- network observers detecting that a VPN exists

## Assets

- profile UUIDs, server endpoints, public keys, short IDs, SNI, and routing intent
- active runtime strategy and runtime state
- automation token and status metadata
- backup and audit export artifacts
- public IP observations from validation runs
- build and release integrity

## Security Goals

- No unauthenticated localhost proxy.
- No reachable xray or sing-box management API.
- No release-path debug listener.
- No silent downgrade of profile security semantics.
- No raw secrets in diagnostic bundles.
- Fail closed on real runtime exposure or health failure.
- Keep automation opt-in and token-gated.
- Make routing decisions explicit and testable.
- Treat generic VPN visibility as informational, not as a blocker by itself.

## Trust Boundaries

- `:app`: UI, import, storage, route test, export, automation relay.
- `:vpnservice`: `VpnService`, Binder runtime control, runtime session, exposure check, watchdog, egress IP probe.
- Binder: intended internal control channel.
- Exported automation activity: explicit token-gated bridge to the internal control path.
- Loopback: treated as a hostile shared-device surface unless it is expected, authenticated, and scoped.
- GitHub Packages/local Maven: source for the pinned `libbox-android` runtime dependency.

## Implemented Controls

### Import

- Share links and JSON profiles are validated before storage.
- Import is staged and previewed before confirmation.
- Unsupported harmless URI parameters become warnings.
- Unsafe TLS flags, debug endpoints, and compatibility localhost proxy flags are rejected.
- REALITY `spx` / `spiderX` is preserved as shared profile semantics.

### Runtime

- Runtime control service is not exported.
- Both runtime strategies use the same sealed profile and Binder contract.
- Management APIs and debug endpoints are not enabled.
- `XRAY_TUN2SOCKS` uses a scoped internal bridge when required by that lane.
- `SINGBOX_EMBEDDED` uses pinned libbox and staged runtime assets.
- Health failure stops the session and tears down the VPN.

### Routing

- Split routing is enforced through Android `VpnService.Builder` package policy.
- Full tunnel, allowlist, and denylist are explicit policy modes.
- Regional direct rules are generated before normal direct/proxy/default rules.
- Custom direct domains are normalized suffix rules.
- Route test is an offline simulation of the current policy and does not send traffic.

### Exposure Check

When Android allows socket inventory reads, Tunguska audits local sockets for the current app UID and fails closed on forbidden listeners.

When Android SELinux denies `/proc/net` socket inventory to the app-sandboxed VPN process, Tunguska reports a limited exposure check:

- it does not claim full listener-audit confidence
- it reports declared runtime topology
- it avoids repeated read attempts after detecting the restriction
- it does not show a product-level failure unless there is an actual security-impacting condition

### Automation

- Disabled by default.
- Exported activity requires the current automation token.
- Token rotation invalidates the previous token.
- Invalid token, disabled integration, missing profile, invalid profile, missing VPN permission, and runtime failures are explicit outcomes.
- Automation uses the same stored profile and runtime strategy as the UI.

### Storage And Diagnostics

- Profile storage is encrypted and app-private.
- Automation status storage is encrypted and app-private.
- Backup exports are encrypted full-profile envelopes.
- Audit exports are encrypted redacted bundles.
- Android share sheet is launched only after a successful export.
- Automation tokens are not exported.

## Residual Risks

- Generic VPN visibility remains observable through Android APIs and interface state.
- Physical-device detector validation is still the stronger gate for runtime/security claims.
- Rooted or privileged apps can perform checks ordinary Android apps cannot.
- Server-side block rules cannot catch destinations intentionally routed direct by client policy.
- Subscription and notification code still exists but is not the primary product surface.
- The project is still a sideload-oriented build, not a store-distributed production channel.

## Release Blockers

These block a stronger security claim:

- unauthenticated localhost SOCKS/HTTP proxy
- reachable xray or sing-box management API
- debug or pprof-style listener
- confirmed split-routing bypass on a non-rooted device
- confirmed underlying-network leak while VPN is active
- raw profile secrets in exported diagnostics
- automation start/stop accepted without enablement and valid token

## Validation Reference

Use focused emulator proofs during development. Use joint Anubis E2E and physical-device validation before commit/release checkpoints that change runtime, routing, or security behavior.

See [docs/e2e-testing.md](./docs/e2e-testing.md) and [docs/device-validation.md](./docs/device-validation.md).
