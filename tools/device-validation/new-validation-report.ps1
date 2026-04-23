param(
    [string]$DeviceLabel = "unassigned-device",
    [string]$RuntimeStrategy = "XRAY_TUN2SOCKS",
    [string]$OutputDir = (Join-Path (Join-Path $PSScriptRoot "..\..") "build\device-validation")
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$fileName = "validation-$timestamp-$DeviceLabel.md"
$path = Join-Path $OutputDir $fileName

$content = @"
# Tunguska Device Validation Report

- Date: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz")
- Device: $DeviceLabel
- Runtime strategy: $RuntimeStrategy
- Commit: pending
- App version: pending

## Inputs

- Profile import by paste: pending
- Profile import by QR camera: pending
- Profile import by QR image: pending
- VPN permission granted: pending

## Runtime Checks

- Connect: pending
- Home exit IP shown: pending
- Routed app public IP changed: pending
- Disconnect: pending
- Direct public IP restored: pending
- Reconnect: pending
- Runtime health remains stable: pending

## Routing Checks

- Full tunnel behavior: pending
- Allowlist behavior: pending
- Denylist behavior: pending
- Russia direct enabled: pending
- Russia direct disabled: pending
- Custom direct domain: pending
- Route test outcome matches expected policy: pending

## Detector Checks

- RKNHardering bypass verdict: pending
- VPN-Detector generic visibility: pending
- per-app-split-bypass-poc result: pending
- Termux excluded-app public-IP result: pending

## Blocking Findings

- Open unauthenticated localhost proxy: pending
- Reachable management or debug API: pending
- Confirmed split-routing bypass: pending
- Underlying-network leak: pending
- Raw secret in diagnostic export: pending
- Automation accepted without valid token: pending

## Evidence

- Redacted audit export path: pending
- Screenshots/logs path: pending
- Notes: pending
"@

Set-Content -Path $path -Value $content -Encoding UTF8
Write-Host "Validation report template created at $path"
