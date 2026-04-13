param(
    [string]$Config = "tools/ingest/upstreams.yaml",
    [string]$Date = "",
    [string]$Root = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
}

if ([string]::IsNullOrWhiteSpace($Date)) {
    $Date = Get-Date -Format "yyyy-MM-dd"
}

$configPath = Join-Path $Root $Config
$rawRoot = Join-Path $Root "data/raw/$Date"
$derivedRoot = Join-Path $Root "data/derived/$Date"
New-Item -ItemType Directory -Force $rawRoot, $derivedRoot | Out-Null

function Get-Upstreams {
    param([string]$Path)

    $owner = $null
    foreach ($line in Get-Content $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^-\s+owner:\s+(.+)$") {
            $owner = $Matches[1].Trim()
        } elseif ($trimmed -match "^owner:\s+(.+)$") {
            $owner = $Matches[1].Trim()
        } elseif ($trimmed -match "^repo:\s+(.+)$") {
            if (-not $owner) {
                throw "Encountered repo before owner in $Path"
            }
            [pscustomobject]@{
                owner = $owner
                repo = $Matches[1].Trim()
            }
            $owner = $null
        }
    }
}

function Invoke-GhArray {
    param([string]$Route)

    $pagesJson = gh api --paginate --slurp $Route
    if ($LASTEXITCODE -ne 0) {
        throw "gh api failed for route $Route"
    }
    $pages = $pagesJson | ConvertFrom-Json
    $items = New-Object System.Collections.Generic.List[object]

    foreach ($page in $pages) {
        if ($page -is [System.Array]) {
            foreach ($item in $page) {
                [void]$items.Add($item)
            }
        } elseif ($null -ne $page) {
            [void]$items.Add($page)
        }
    }

    return ,$items.ToArray()
}

function Write-JsonFile {
    param(
        [string]$Path,
        [object]$Value
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 100), $encoding)
}

$allIssues = New-Object System.Collections.Generic.List[object]

foreach ($upstream in Get-Upstreams -Path $configPath) {
    $repoDir = Join-Path $rawRoot ("{0}_{1}" -f $upstream.owner, $upstream.repo)
    New-Item -ItemType Directory -Force $repoDir | Out-Null

    Write-Host "Fetching $($upstream.owner)/$($upstream.repo)..."
    $issues = Invoke-GhArray -Route "/repos/$($upstream.owner)/$($upstream.repo)/issues?state=all&per_page=100"
    $prs = Invoke-GhArray -Route "/repos/$($upstream.owner)/$($upstream.repo)/pulls?state=all&per_page=100"

    $issuesPath = Join-Path $repoDir "issues.json"
    $prsPath = Join-Path $repoDir "prs.json"
    Write-JsonFile -Path $issuesPath -Value $issues
    Write-JsonFile -Path $prsPath -Value $prs

    foreach ($issue in $issues) {
        [void]$allIssues.Add($issue)
    }
}

$allIssuesPath = Join-Path $rawRoot "all-issues.json"
$csvPath = Join-Path $derivedRoot "security_backlog.csv"
$markdownPath = Join-Path $derivedRoot "inventory.md"

Write-JsonFile -Path $allIssuesPath -Value $allIssues.ToArray()

$gradle = if ($IsWindows) {
    Join-Path $Root "gradlew.bat"
} else {
    Join-Path $Root "gradlew"
}

& $gradle ":tools:ingest:run" "--args=--input `"$allIssuesPath`" --csv `"$csvPath`" --markdown `"$markdownPath`""
if ($LASTEXITCODE -ne 0) {
    throw "Gradle ingest classification failed."
}
