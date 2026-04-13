param(
    [string]$ShareLink = "",
    [string]$ExpectedPhase = "RUNNING",
    [string]$JavaHome = "C:\Program Files\Java\jdk-24"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path

Push-Location $root
try {
    & "tools\emulator\start-emulator.ps1"

    $env:JAVA_HOME = $JavaHome
    $gradleArgs = @(
        ":app:connectedDebugAndroidTest",
        "--no-daemon",
        "--no-build-cache",
        "--no-configuration-cache",
        "-Pandroid.testInstrumentationRunnerArguments.class=io.acionyx.tunguska.app.VpnMvpSmokeTest"
    )

    if ($ShareLink) {
        $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ShareLink) | ForEach-Object {
            $_.ToString("x2")
        }) -join ""
        $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.profile_share_link_hex=$shareLinkHex"
    }

    if ($ExpectedPhase) {
        $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.expected_phase=$ExpectedPhase"
    }

    & .\gradlew.bat @gradleArgs
}
finally {
    Pop-Location
}
