param(
    [string]$ApkPath = "C:\src\tunguska\dist\tunguska-0.1.0-internal.apk",
    [string]$PackageName = "io.acionyx.tunguska"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"

Invoke-Adb -Arguments @("install", "-r", $ApkPath) | Out-Null
Invoke-Adb -Arguments @("shell", "monkey", "-p", $PackageName, "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 2
Export-UiHierarchy | Out-Null
Write-Host "App installed and launched."
