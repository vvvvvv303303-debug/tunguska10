# Security Policy

## Supported Code Lines

Security fixes should land in:

- `main`
- the latest maintained GitHub release line

Old local APKs, ignored local artifacts, and stale workflow outputs are not supported release channels.

## Reporting A Vulnerability

Report suspected vulnerabilities privately.

Preferred path:

- GitHub Security Advisories for this repository

Fallback:

- direct maintainer contact through an already trusted channel

Do not open a public issue first for bugs that could expose users, credentials, routing policy, or runtime control surfaces.

## Security-Sensitive Areas

Tunguska treats these as security-critical:

- localhost or LAN listeners
- xray or sing-box management APIs
- debug or pprof-style endpoints
- split-routing bypasses
- underlying-network leaks while VPN is active
- profile import validation
- encrypted profile storage
- backup and audit export redaction
- automation token validation
- runtime strategy dependency provenance
- GitHub Release artifact integrity

## Report Contents

Include as much as possible:

- affected version or commit
- Android version and device model
- runtime strategy (`XRAY_TUN2SOCKS` or `SINGBOX_EMBEDDED`)
- sanitized profile or import steps
- whether it reproduces on emulator, physical device, or both
- expected behavior and actual behavior
- whether VPN was connected, connecting, idle, or fail-closed
- logs or redacted audit export when safe

Do not include raw live profile URLs or automation tokens in public channels.

## Handling Goals

The project aims to:

- acknowledge credible reports promptly
- keep triage private until remediation is clear
- land regression coverage with fixes
- document security-relevant behavior changes in release notes or release documentation

## Current Security Position

The project currently claims:

- no intentional unauthenticated localhost proxy surface
- no enabled management API in supported runtime lanes
- token-gated external automation
- encrypted local profile and automation storage
- redacted diagnostic export by default
- fail-closed teardown on real runtime exposure or watchdog failure

The project does not claim:

- VPN invisibility
- complete physical-device detector clearance
- protection against rooted or fully compromised devices
