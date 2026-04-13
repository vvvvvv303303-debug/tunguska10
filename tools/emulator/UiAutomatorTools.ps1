function Get-AdbPath {
    $sdkRoot = "C:\Users\vladi\AppData\Local\Android\Sdk"
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        throw "adb.exe not found at $adb"
    }
    return $adb
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $adb = Get-AdbPath
    & $adb @Arguments
}

function Export-UiHierarchy {
    param(
        [string]$OutputPath = "C:\src\tunguska\logs\ui-latest.xml"
    )

    $directory = Split-Path -Parent $OutputPath
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", "/sdcard/tunguska-ui.xml") | Out-Null
    Invoke-Adb -Arguments @("pull", "/sdcard/tunguska-ui.xml", $OutputPath) | Out-Null
    return $OutputPath
}

function Get-UiNodes {
    param(
        [string]$HierarchyPath = "C:\src\tunguska\logs\ui-latest.xml"
    )

    if (-not (Test-Path $HierarchyPath)) {
        $HierarchyPath = Export-UiHierarchy -OutputPath $HierarchyPath
    }

    [xml]$xml = Get-Content -Path $HierarchyPath
    return $xml.SelectNodes("//node")
}

function Convert-BoundsToPoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Bounds
    )

    if ($Bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Unsupported bounds format: $Bounds"
    }

    $x1 = [int]$Matches[1]
    $y1 = [int]$Matches[2]
    $x2 = [int]$Matches[3]
    $y2 = [int]$Matches[4]

    [pscustomobject]@{
        X = [int](($x1 + $x2) / 2)
        Y = [int](($y1 + $y2) / 2)
    }
}

function Find-UiNodeByText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,
        [switch]$Exact,
        [string]$HierarchyPath = "C:\src\tunguska\logs\ui-latest.xml"
    )

    $nodes = Get-UiNodes -HierarchyPath $HierarchyPath
    $match = $nodes | Where-Object {
        $nodeText = [string]$_.text
        $content = [string]$_.'content-desc'
        if ($Exact) {
            $nodeText -eq $Text -or $content -eq $Text
        } else {
            $nodeText -like "*$Text*" -or $content -like "*$Text*"
        }
    } | Select-Object -First 1

    if (-not $match) {
        throw "UI node not found for text '$Text'"
    }

    return [pscustomobject]@{
        Text = [string]$match.text
        ContentDescription = [string]$match.'content-desc'
        ResourceId = [string]$match.'resource-id'
        ClassName = [string]$match.class
        Bounds = [string]$match.bounds
        Clickable = [string]$match.clickable
    }
}

function Invoke-UiTapByText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,
        [switch]$Exact,
        [string]$HierarchyPath = "C:\src\tunguska\logs\ui-latest.xml"
    )

    $node = Find-UiNodeByText -Text $Text -Exact:$Exact -HierarchyPath $HierarchyPath
    $point = Convert-BoundsToPoint -Bounds $node.Bounds
    Invoke-Adb -Arguments @("shell", "input", "tap", "$($point.X)", "$($point.Y)") | Out-Null
    return [pscustomobject]@{
        Text = $node.Text
        Bounds = $node.Bounds
        X = $point.X
        Y = $point.Y
    }
}

function Invoke-UiTextInput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $escaped = $Text.Replace("%", "\%").Replace(" ", "%s").Replace("&", "\&").Replace("(", "\(").Replace(")", "\)")
    Invoke-Adb -Arguments @("shell", "input", "text", $escaped) | Out-Null
}
