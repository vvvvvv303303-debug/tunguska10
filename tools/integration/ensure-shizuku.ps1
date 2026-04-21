param(
    [string]$ShizukuApkPath = ""
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\emulator\UiAutomatorTools.ps1"

if (-not $ShizukuApkPath) {
    $ShizukuApkPath = Get-ShizukuApkPath
}

function Test-ShizukuInstalled {
    $packages = Invoke-Adb -Arguments @("shell", "pm", "list", "packages", "moe.shizuku.privileged.api")
    return ($packages -join "`n") -match "package:moe\.shizuku\.privileged\.api"
}

function Test-ShizukuRunning {
    $serverPid = (Invoke-Adb -Arguments @("shell", "pidof", "shizuku_server") 2>$null) -join "`n"
    return -not [string]::IsNullOrWhiteSpace($serverPid)
}

function Install-ShizukuApk {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest"
        $asset = $release.assets | Where-Object { $_.name -like "shizuku-*-release.apk" } | Select-Object -First 1
        if (-not $asset) {
            throw "Unable to resolve the latest Shizuku release APK."
        }
        New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $Path
    }

    Invoke-Adb -Arguments @("install", "-r", $Path) | Out-Null
}

function Start-ShizukuServer {
    $startScript = "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
    $scriptExists = $false
    try {
        $listing = (Invoke-Adb -Arguments @("shell", "ls", $startScript) 2>$null) -join "`n"
        $scriptExists = $listing -match [regex]::Escape($startScript)
    }
    catch {
        $scriptExists = $false
    }
    if ($scriptExists) {
        Invoke-Adb -Arguments @("shell", "sh", $startScript) | Out-Null
        return
    }

    $apkPath = (Invoke-Adb -Arguments @("shell", "pm", "path", "moe.shizuku.privileged.api")) `
        | Where-Object { $_ -match "^package:" } `
        | Select-Object -First 1
    if (-not $apkPath) {
        throw "Unable to resolve the installed Shizuku APK path."
    }
    $apkPath = ($apkPath -replace "^package:", "").Trim()
    $packageDir = $apkPath.Substring(0, $apkPath.LastIndexOf("/"))
    $candidateAbis = @("x86_64", "arm64", "arm64-v8a", "x86", "armeabi-v7a", "arm")
    foreach ($abi in $candidateAbis) {
        $libPath = "$packageDir/lib/$abi/libshizuku.so"
        try {
            $listing = (Invoke-Adb -Arguments @("shell", "ls", $libPath) 2>$null) -join "`n"
            if ($listing -match [regex]::Escape($libPath)) {
                Invoke-Adb -Arguments @("shell", $libPath) | Out-Null
                return
            }
        }
        catch {
        }
    }

    throw "Unable to resolve a runnable libshizuku.so inside $packageDir"
}

if (-not (Test-ShizukuInstalled)) {
    Install-ShizukuApk -Path $ShizukuApkPath
}

Invoke-Adb -Arguments @("shell", "monkey", "-p", "moe.shizuku.privileged.api", "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 3

if (-not (Test-ShizukuRunning)) {
    Start-ShizukuServer
    Start-Sleep -Seconds 3
}

if (-not (Test-ShizukuRunning)) {
    Start-ShizukuServer
    Start-Sleep -Seconds 3
}

if (-not (Test-ShizukuRunning)) {
    throw "Shizuku server did not start."
}

Write-Host "Shizuku is installed and running."
