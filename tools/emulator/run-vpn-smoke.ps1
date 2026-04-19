param(
    [string]$ShareLink = "",
    [string]$ExpectedPhase = "RUNNING",
    [string]$JavaHome = "C:\Program Files\Java\jdk-24",
    [string]$AvdName = "tunguska-api34",
    [switch]$Headless,
    [switch]$NoHardReset,
    [switch]$SkipInstall,
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12,
    [string[]]$TestClasses = @(
        "io.acionyx.tunguska.app.VpnImportAndConnectTest",
        "io.acionyx.tunguska.app.ChromeIpProofTest",
        "io.acionyx.tunguska.app.AutomationRelayProofTest",
        "io.acionyx.tunguska.app.FullTunnelProofTest",
        "io.acionyx.tunguska.app.DenylistRoutingProofTest",
        "io.acionyx.tunguska.app.AllowlistRoutingProofTest"
    )
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$diagnosticsRemotePath = "/sdcard/Download/tunguska-smoke"
$androidHome = "C:\Users\vladi\AppData\Local\Android\Sdk"
$adb = "$androidHome\platform-tools\adb.exe"
. "$PSScriptRoot\UiAutomatorTools.ps1"

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
    Invoke-Adb -Arguments @("shell", "rm", "-rf", $diagnosticsRemotePath) | Out-Null
    Invoke-Adb -Arguments @("shell", "mkdir", "-p", $diagnosticsRemotePath) | Out-Null

    if (-not $SkipInstall) {
        Assert-EmulatorOnline
        Write-Host "Phase: install Tunguska + tests + trafficprobe"
        $env:JAVA_HOME = $JavaHome
        & .\gradlew.bat `
            :app:installDebug `
            :app:installDebugAndroidTest `
            :trafficprobe:installDebug `
            --no-daemon `
            --no-build-cache `
            --no-configuration-cache `
            --no-parallel `
            "-Dkotlin.incremental=false"
        Assert-LastExitCode "Tunguska smoke install"
    }

    if (-not $ShareLink -and $env:TUNGUSKA_REAL_SHARE_LINK) {
        $ShareLink = $env:TUNGUSKA_REAL_SHARE_LINK
    }

    if ($ShareLink) {
        $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ShareLink) | ForEach-Object {
            $_.ToString("x2")
        }) -join ""
    }

    foreach ($testClass in $TestClasses) {
        Assert-EmulatorOnline
        Write-Host "Phase: run $testClass"
        $instrumentArgs = @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", $testClass
        )
        if ($ShareLink) {
            $instrumentArgs += @("-e", "profile_share_link_hex", $shareLinkHex)
        }
        if ($ExpectedPhase) {
            $instrumentArgs += @("-e", "expected_phase", $ExpectedPhase)
        }
        $instrumentArgs += @("io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner")
        & $adb @instrumentArgs
        Assert-LastExitCode "Instrumentation $testClass"
        Assert-EmulatorOnline
    }
    & "tools\emulator\pull-diagnostics.ps1"
}
finally {
    Pop-Location
}
