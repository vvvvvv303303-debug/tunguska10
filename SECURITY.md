# Security Policy

## Supported Code Lines

Security fixes are expected to land in:

- the latest GitHub release line
- the `main` branch

Older local builds and stale workflow artifacts should not be treated as supported release channels.

## How To Report A Vulnerability

Please report suspected security issues privately.

Preferred path:

- GitHub Security Advisories for this repository

Fallback:

- direct maintainer contact if you already have a trusted channel

Do not open a public issue first for vulnerabilities that may expose users.

## What Counts As Security-Sensitive

Tunguska treats these areas as security-critical:

- localhost or LAN listeners
- Xray or sing-box management APIs and debug endpoints
- split-routing bypasses
- underlying-network leaks while VPN is active
- profile import validation and storage
- export artifact secrecy and redaction
- build and release integrity

## What To Include In A Report

Please include as much of the following as possible:

- affected version or commit
- Android version and device model
- exact import string or sanitized repro profile
- whether the issue reproduces on a physical device, emulator, or both
- expected behavior and actual behavior
- logs or diagnostic bundle with secrets redacted when possible

## Handling Goals

The project aims to:

- acknowledge real reports promptly
- keep triage private until there is a clear remediation path
- land regression coverage with the fix
- document security-relevant behavior changes in release notes or release documentation

## Current Security Position

The project currently claims:

- no unauthenticated localhost surface in the active runtime lane
- no enabled management API in the active runtime lane
- fail-closed teardown on listener-audit or watchdog failure

The project does not yet claim:

- full real-device detector clearance across the complete validation matrix
- VPN invisibility

