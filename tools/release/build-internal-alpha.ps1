param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-24"
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$apkRelativePath = "app\\build\\outputs\\apk\\internal\\app-internal.apk"
$distDir = Join-Path $root "dist"

Push-Location $root
try {
    if ($JavaHome) {
        $env:JAVA_HOME = $JavaHome
    }

    .\gradlew.bat --stop | Out-Null
    Get-ChildItem -Path . -Directory -Recurse -Filter build -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path ".kotlin") {
        Remove-Item -Recurse -Force ".kotlin"
    }

    .\gradlew.bat :app:testDebugUnitTest :app:assembleInternal --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache

    $sourceApk = Join-Path $root $apkRelativePath
    if (-not (Test-Path $sourceApk)) {
        throw "Internal APK was not produced at $sourceApk"
    }

    $versionMatch = Select-String -Path "app/build.gradle.kts" -Pattern 'versionName = "([^"]+)"'
    if (-not $versionMatch.Matches.Count) {
        throw "Unable to resolve versionName from app/build.gradle.kts"
    }
    $baseVersion = $versionMatch.Matches[0].Groups[1].Value
    $distVersion = "$baseVersion-internal"

    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    $distApk = Join-Path $distDir "tunguska-$distVersion.apk"
    Copy-Item -Force $sourceApk $distApk

    $sha = (Get-FileHash -Algorithm SHA256 $distApk).Hash.ToLowerInvariant()
    Set-Content -Path "$distApk.sha256" -Value "$sha  $(Split-Path -Leaf $distApk)"

    $apksigner = "C:\Users\vladi\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat"
    if (Test-Path $apksigner) {
        & $apksigner verify --print-certs $distApk | Set-Content -Path "$distApk.signing.txt"
    }

    Write-Host "Internal APK ready: $distApk"
    Write-Host "SHA-256: $sha"
}
finally {
    Pop-Location
}
