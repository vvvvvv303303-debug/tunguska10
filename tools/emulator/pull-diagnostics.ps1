param(
    [string]$RemotePath = "files/tunguska-smoke",
    [string]$OutputRoot = "",
    [string]$AppPackage = "io.acionyx.tunguska",
    [string]$OutputPrefix = "tunguska-smoke"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"
$adb = Get-AdbPath

if (-not $OutputRoot) {
    $OutputRoot = Get-LogsRoot
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$destination = Join-Path $OutputRoot "$OutputPrefix-$timestamp"
New-Item -ItemType Directory -Force -Path $destination | Out-Null

function Try-PullDiagnosticsArchive {
    $tarCommand = Get-Command tar -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $tarCommand) {
        return $false
    }

    $archivePath = Join-Path $env:TEMP ("tunguska-diagnostics-{0}.tar" -f ([guid]::NewGuid().ToString("N")))
    $stderrPath = Join-Path $env:TEMP ("tunguska-diagnostics-{0}.stderr.txt" -f ([guid]::NewGuid().ToString("N")))
    try {
        $process = Start-Process `
            -FilePath $adb `
            -ArgumentList @("exec-out", "run-as", $AppPackage, "tar", "-C", $RemotePath, "-cf", "-", ".") `
            -NoNewWindow `
            -Wait `
            -PassThru `
            -RedirectStandardOutput $archivePath `
            -RedirectStandardError $stderrPath

        if ($process.ExitCode -ne 0 -or -not (Test-Path $archivePath) -or (Get-Item $archivePath).Length -eq 0) {
            return $false
        }

        & $tarCommand.Source -xf $archivePath -C $destination
        return $LASTEXITCODE -eq 0
    }
    finally {
        Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

if (Try-PullDiagnosticsArchive) {
    Write-Host "Diagnostics pulled to $destination"
    return
}

$files = Invoke-Adb -Arguments @("shell", "run-as", $AppPackage, "find", $RemotePath, "-type", "f")
if ($LASTEXITCODE -ne 0) {
    throw "Failed to enumerate diagnostics from ${AppPackage}:$RemotePath."
}
$relativeFiles = $files |
    Where-Object { $_ -and $_.Trim() } |
    ForEach-Object { $_.Trim() }

foreach ($remoteFile in $relativeFiles) {
    $relativePath = $remoteFile.Substring($RemotePath.Length).TrimStart('/', '\')
    if (-not $relativePath) {
        continue
    }
    $localPath = Join-Path $destination $relativePath
    $localDirectory = Split-Path -Parent $localPath
    if ($localDirectory) {
        New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
    }
    $process = Start-Process `
        -FilePath $adb `
        -ArgumentList @("exec-out", "run-as", $AppPackage, "cat", $remoteFile) `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $localPath
    if ($process.ExitCode -ne 0) {
        throw "Failed to pull Tunguska diagnostic file $remoteFile."
    }
}

Write-Host "Diagnostics pulled to $destination"
