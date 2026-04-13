param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-24"
)

$ErrorActionPreference = "Stop"

& "$PSScriptRoot\build-internal-alpha.ps1" -JavaHome $JavaHome
