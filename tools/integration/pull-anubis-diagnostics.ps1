param(
    [string]$RemotePath = "/sdcard/Download/anubis-smoke",
    [string]$OutputRoot = "C:\src\tunguska\logs"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\emulator\UiAutomatorTools.ps1"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$destination = Join-Path $OutputRoot "anubis-smoke-$timestamp"
New-Item -ItemType Directory -Force -Path $destination | Out-Null

Invoke-Adb -Arguments @("pull", $RemotePath, $destination) | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Failed to pull Anubis diagnostics from $RemotePath."
}
Write-Host "Anubis diagnostics pulled to $destination"
