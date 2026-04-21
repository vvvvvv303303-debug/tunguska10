param(
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

if ($JavaHome) {
    & "$PSScriptRoot\build-internal-alpha.ps1" -JavaHome $JavaHome
} else {
    & "$PSScriptRoot\build-internal-alpha.ps1"
}
