param(
    [string]$AvdName = "tunguska-api34",
    [switch]$Headless,
    [switch]$ColdBoot,
    [switch]$WipeData,
    [switch]$HardReset,
    [string]$CameraBack = "emulated",
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12,
    [int]$VmHeapMb = 512,
    [decimal]$AnimatorDurationScale = 1
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\common\PathTools.ps1"

$sdkRoot = Get-AndroidSdkRoot
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$adb = Get-AdbPath
$avdConfig = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd\config.ini"

if (-not (Test-Path $emulator)) {
    throw "Android Emulator is not installed at $emulator"
}

if (-not (Test-Path $avdConfig)) {
    throw "AVD config not found: $avdConfig"
}

function Set-IniValue {
    param(
        [string]$Path,
        [string]$Key,
        [string]$Value
    )

    $content = Get-Content $Path
    $updated = $false
    for ($i = 0; $i -lt $content.Length; $i++) {
        if ($content[$i] -like "$Key=*") {
            $content[$i] = "$Key=$Value"
            $updated = $true
            break
        }
    }
    if (-not $updated) {
        $content += "$Key=$Value"
    }
    Set-Content -Path $Path -Value $content -Encoding ascii
}

function Stop-EmulatorHard {
    param([string]$AdbPath)

    try {
        & $AdbPath emu kill 2>$null | Out-Null
    } catch {
    }

    Get-Process |
        Where-Object { $_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*" } |
        Stop-Process -Force -ErrorAction SilentlyContinue

    Start-Sleep -Seconds 3
}

function Wait-ForEmulatorWindow {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        try {
            $null = $Process.Refresh()
        } catch {
            break
        }
        if ($Process.HasExited) {
            throw "The emulator process exited before creating a visible window."
        }
        $visibleWindow = Get-Process |
            Where-Object {
                ($_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*") -and
                    $_.MainWindowHandle -ne 0
            } |
            Select-Object -First 1
        if ($visibleWindow) {
            return
        }
    } while ((Get-Date) -lt $deadline)

    $visibleWindow = Get-Process |
        Where-Object {
            ($_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*") -and
                $_.MainWindowHandle -ne 0
        } |
        Select-Object -First 1
    if (-not $visibleWindow) {
        throw "The headed emulator did not create a visible window within ${TimeoutSeconds}s."
    }
}

Set-IniValue -Path $avdConfig -Key "hw.cpu.ncore" -Value $CpuCores
Set-IniValue -Path $avdConfig -Key "hw.ramSize" -Value "${MemoryMb}M"
Set-IniValue -Path $avdConfig -Key "vm.heapSize" -Value $VmHeapMb
Set-IniValue -Path $avdConfig -Key "hw.gpu.enabled" -Value "yes"
Set-IniValue -Path $avdConfig -Key "hw.gpu.mode" -Value "host"
Set-IniValue -Path $avdConfig -Key "fastboot.forceFastBoot" -Value "yes"
Set-IniValue -Path $avdConfig -Key "showDeviceFrame" -Value "yes"

$effectiveColdBoot = $ColdBoot -or $HardReset
$effectiveWipeData = $WipeData -or $HardReset

if ($HardReset) {
    Stop-EmulatorHard -AdbPath $adb
}

if (-not (Get-Process | Where-Object { $_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*" })) {
    $arguments = @(
        "-avd", $AvdName,
        "-no-boot-anim",
        "-no-audio",
        "-gpu", "host",
        "-memory", $MemoryMb,
        "-cores", $CpuCores,
        "-camera-back", $CameraBack
    )
    if ($Headless) {
        $arguments += "-no-window"
    }
    if ($effectiveColdBoot) {
        $arguments += "-no-snapshot-load"
    }
    if ($effectiveWipeData) {
        $arguments += "-wipe-data"
        $arguments += "-no-snapshot-save"
    }
    $startedProcess = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru
    if (-not $Headless) {
        Wait-ForEmulatorWindow -Process $startedProcess
    }
}

& $adb wait-for-device | Out-Null
$deadline = (Get-Date).AddMinutes(6)
do {
    Start-Sleep -Seconds 3
    $boot = (& $adb shell getprop sys.boot_completed 2>$null).Trim()
} while ((Get-Date) -lt $deadline -and $boot -ne "1")

if ($boot -ne "1") {
    throw "Emulator did not finish booting in time."
}

& $adb shell settings put global window_animation_scale 0 | Out-Null
& $adb shell settings put global transition_animation_scale 0 | Out-Null
& $adb shell settings put global animator_duration_scale $AnimatorDurationScale | Out-Null

Write-Host "Emulator ready (headed=$(!$Headless), hardReset=$HardReset, memoryMb=$MemoryMb, cpuCores=$CpuCores, animatorDurationScale=$AnimatorDurationScale)."
