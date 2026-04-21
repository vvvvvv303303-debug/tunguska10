param(
    [string]$ApkPath = "",
    [string]$PackageName = "io.acionyx.tunguska"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"

if (-not $ApkPath) {
    $ApkPath = Get-ChildItem -Path (Get-DistDirectory) -Filter "*.apk" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
    throw "APK not found. Pass -ApkPath or build an internal APK into $(Get-DistDirectory)."
}

Invoke-Adb -Arguments @("install", "-r", $ApkPath) | Out-Null
Invoke-Adb -Arguments @("shell", "monkey", "-p", $PackageName, "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 2
Export-UiHierarchy | Out-Null
Write-Host "App installed and launched."
