param(
    [string]$ShareLink = "",
    [string]$JavaHome = "",
    [string]$AvdName = "tunguska-api34",
    [string]$AnubisRepo = "",
    [switch]$Headless,
    [switch]$NoHardReset,
    [switch]$SkipInstall,
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. "$PSScriptRoot\..\common\PathTools.ps1"

$androidHome = Get-AndroidSdkRoot
$adb = Get-AdbPath

if (-not $JavaHome) {
    $JavaHome = Get-DefaultJavaHome
}

if (-not $AnubisRepo) {
    $siblingAnubisRepo = Join-Path (Split-Path $root -Parent) "anubis"
    if (Test-Path $siblingAnubisRepo) {
        $AnubisRepo = (Resolve-Path $siblingAnubisRepo).Path
    }
}

if (-not $AnubisRepo -or -not (Test-Path $AnubisRepo)) {
    $expectedSibling = Join-Path (Split-Path $root -Parent) "anubis"
    throw "Anubis repo not found. Pass -AnubisRepo or clone it as a sibling repo at $expectedSibling."
}

function Assert-EmulatorOnline {
    $deviceList = (& $adb devices) -join "`n"
    if ($deviceList -notmatch "emulator-\d+\s+device") {
        throw "No headed emulator device is attached to adb."
    }
    $emulatorProcess = Get-Process |
        Where-Object { $_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*" } |
        Select-Object -First 1
    if (-not $emulatorProcess) {
        throw "adb reports an emulator device, but no emulator process is running locally."
    }
}

function Assert-LastExitCode {
    param([string]$Step)

    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

function Invoke-CheckedInstrumentation {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$Step
    )

    $stdoutPath = Join-Path $env:TEMP ("tunguska-instrument-{0}.stdout.txt" -f ([guid]::NewGuid().ToString("N")))
    $stderrPath = Join-Path $env:TEMP ("tunguska-instrument-{0}.stderr.txt" -f ([guid]::NewGuid().ToString("N")))
    try {
        $process = Start-Process `
            -FilePath $adb `
            -ArgumentList $Arguments `
            -NoNewWindow `
            -Wait `
            -PassThru `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath
        $instrumentOutput = @()
        if (Test-Path $stdoutPath) {
            $instrumentOutput += Get-Content $stdoutPath
        }
        if (Test-Path $stderrPath) {
            $instrumentOutput += Get-Content $stderrPath
        }
        $instrumentOutput | ForEach-Object { Write-Host $_ }
        if ($process.ExitCode -ne 0) {
            throw "$Step failed with exit code $($process.ExitCode)."
        }
    } finally {
        Remove-Item $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    $instrumentText = ($instrumentOutput | Out-String)
    if (
        $instrumentText -match "Process crashed" -or
        $instrumentText -match "FAILURES!!!" -or
        $instrumentText -match "INSTRUMENTATION_FAILED" -or
        $instrumentText -match "shortMsg="
    ) {
        throw "$Step reported a runtime failure despite exit code 0."
    }
}

Push-Location $root
try {
    Write-Host "Phase: start emulator"
    & "tools\emulator\start-emulator.ps1" `
        -AvdName $AvdName `
        -Headless:$Headless `
        -HardReset:$(!$NoHardReset) `
        -MemoryMb $MemoryMb `
        -CpuCores $CpuCores
    Assert-EmulatorOnline
    Write-Host "Phase: ensure Chrome"
    & "tools\emulator\ensure-chrome.ps1"
    Assert-LastExitCode "Ensure Chrome"
    Assert-EmulatorOnline
    Write-Host "Phase: ensure Shizuku"
    & "tools\integration\ensure-shizuku.ps1"
    Assert-LastExitCode "Ensure Shizuku"
    Assert-EmulatorOnline

    if (-not $ShareLink -and $env:TUNGUSKA_REAL_SHARE_LINK) {
        $ShareLink = $env:TUNGUSKA_REAL_SHARE_LINK
    }
    if (-not $ShareLink) {
        throw "A real VLESS share link is required. Pass -ShareLink or set TUNGUSKA_REAL_SHARE_LINK."
    }

    $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ShareLink) | ForEach-Object {
        $_.ToString("x2")
    }) -join ""

    if (-not $SkipInstall) {
        Assert-EmulatorOnline
        Write-Host "Phase: install Tunguska + trafficprobe + jointtesthost"
        if ($JavaHome) {
            $env:JAVA_HOME = $JavaHome
        }
        & .\gradlew.bat `
            :app:installDebug `
            :app:installDebugAndroidTest `
            :jointtesthost:installDebug `
            :jointtesthost:installDebugAndroidTest `
            :trafficprobe:installDebug `
            --no-daemon `
            --no-build-cache `
            --no-configuration-cache `
            --no-parallel `
            "-Dkotlin.incremental=false"
        Assert-LastExitCode "Tunguska install"
    }

    Assert-EmulatorOnline
    Write-Host "Phase: prepare Tunguska automation fixture"
    & $adb shell am force-stop sgnv.anubis.app
    & $adb shell pm clear io.acionyx.tunguska | Out-Null
    & $adb shell pm clear io.acionyx.tunguska.trafficprobe | Out-Null
    & $adb shell cmd package enable io.acionyx.tunguska | Out-Null
    & $adb shell run-as io.acionyx.tunguska rm -rf files/tunguska-smoke | Out-Null
    & $adb shell run-as io.acionyx.tunguska mkdir -p files/tunguska-smoke | Out-Null
    Invoke-CheckedInstrumentation -Step "Tunguska fixture preparation" -Arguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "io.acionyx.tunguska.app.PrepareAutomationFixtureTest",
        "-e", "profile_share_link_hex", $shareLinkHex,
        "io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner"
    )
    Assert-EmulatorOnline

    & "tools\emulator\pull-diagnostics.ps1"
    Assert-EmulatorOnline

    Push-Location $AnubisRepo
    try {
        if ($JavaHome) {
            $env:JAVA_HOME = $JavaHome
        }
        $env:ANDROID_HOME = $androidHome
        $env:ANDROID_SDK_ROOT = $androidHome

        if (-not $SkipInstall) {
            Assert-EmulatorOnline
            Write-Host "Phase: install Anubis"
            & .\gradlew.bat `
                :app:installDebug `
                --no-daemon `
                --no-build-cache `
                --no-configuration-cache `
                --no-parallel `
                "-Dkotlin.incremental=false"
            Assert-LastExitCode "Anubis install"
        }
    }
    finally {
        Pop-Location
    }

    Assert-EmulatorOnline
    Write-Host "Phase: run Tunguska Anubis joint UI test"
    & $adb shell am force-stop sgnv.anubis.app
    & $adb shell pm clear sgnv.anubis.app | Out-Null
    & $adb shell pm clear io.acionyx.tunguska.jointtesthost | Out-Null
    & $adb shell pm clear io.acionyx.tunguska.trafficprobe | Out-Null
    & $adb shell cmd package enable io.acionyx.tunguska | Out-Null
    & $adb shell cmd package enable sgnv.anubis.app | Out-Null
    & $adb shell cmd package enable io.acionyx.tunguska.jointtesthost | Out-Null
    & $adb shell cmd package enable io.acionyx.tunguska.trafficprobe | Out-Null
    & $adb shell run-as io.acionyx.tunguska.jointtesthost rm -rf files/tunguska-smoke | Out-Null
    & $adb shell run-as io.acionyx.tunguska.jointtesthost mkdir -p files/tunguska-smoke | Out-Null
    Invoke-CheckedInstrumentation -Step "Tunguska Anubis joint UI instrumentation" -Arguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "io.acionyx.tunguska.trafficprobe.AnubisJointUiProofTest",
        "-e", "profile_share_link_hex", $shareLinkHex,
        "io.acionyx.tunguska.jointtesthost.test/androidx.test.runner.AndroidJUnitRunner"
    )

    & "tools\emulator\pull-diagnostics.ps1" -AppPackage "io.acionyx.tunguska.jointtesthost"
}
finally {
    Pop-Location
}
