param(
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"

if (-not $OutputPath) {
    $OutputPath = Get-UiHierarchyPath
}

$path = Export-UiHierarchy -OutputPath $OutputPath
[xml]$xml = Get-Content -Path $path
$xml.SelectNodes("//node") |
    Where-Object { $_.text -or $_.'content-desc' } |
    Select-Object `
        @{n='text';e={[string]$_.text}}, `
        @{n='content';e={[string]$_.'content-desc'}}, `
        @{n='resource';e={[string]$_.'resource-id'}}, `
        @{n='bounds';e={[string]$_.bounds}} |
    Format-Table -AutoSize
