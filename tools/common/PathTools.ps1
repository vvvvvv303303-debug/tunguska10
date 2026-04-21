function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\.." )).Path
}

function Get-AndroidSdkRoot {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME
    )

    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
        $candidates += Join-Path $localAppData "Android\Sdk"
    }

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
}

function Get-AdbPath {
    $adb = Join-Path (Get-AndroidSdkRoot) "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        throw "adb.exe not found at $adb"
    }
    return $adb
}

function Get-LogsRoot {
    return Join-Path (Get-RepoRoot) "logs"
}

function Get-DistDirectory {
    return Join-Path (Get-RepoRoot) "dist"
}

function Get-ChromeApkPath {
    return Join-Path (Get-RepoRoot) "tools\browser\chrome.apk"
}

function Get-ShizukuApkPath {
    return Join-Path (Get-RepoRoot) "tools\third_party\shizuku.apk"
}

function Get-DefaultJavaHome {
    $candidates = @(
        $env:JAVA_HOME,
        (Join-Path ${env:ProgramFiles} "Java\jdk-24")
    )

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    return $null
}

function Get-LatestAndroidBuildToolPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ToolName
    )

    $buildToolsRoot = Join-Path (Get-AndroidSdkRoot) "build-tools"
    if (-not (Test-Path $buildToolsRoot)) {
        return $null
    }

    $tool = Get-ChildItem -Path $buildToolsRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $ToolName } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1

    return $tool
}