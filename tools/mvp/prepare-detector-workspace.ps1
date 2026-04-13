param(
    [string]$Root = (Join-Path (Join-Path $PSScriptRoot "..\..") "build\mvp-detectors")
)

$ErrorActionPreference = "Stop"

$repos = @(
    @{
        Name = "RKNHardering"
        Url = "https://github.com/xtclovver/RKNHardering.git"
    },
    @{
        Name = "VPN-Detector"
        Url = "https://github.com/cherepavel/VPN-Detector.git"
    },
    @{
        Name = "per-app-split-bypass-poc"
        Url = "https://github.com/runetfreedom/per-app-split-bypass-poc.git"
    }
)

New-Item -ItemType Directory -Force -Path $Root | Out-Null

foreach ($repo in $repos) {
    $target = Join-Path $Root $repo.Name
    if (Test-Path $target) {
        Write-Host "Refreshing $($repo.Name) in $target"
        git -C $target fetch --all --tags --prune
        git -C $target pull --ff-only
    } else {
        Write-Host "Cloning $($repo.Name) into $target"
        git clone $repo.Url $target
    }
}

Write-Host ""
Write-Host "Detector workspace prepared in $Root"
Write-Host "Next: run tools\\mvp\\new-validation-report.ps1 to generate a report template."
