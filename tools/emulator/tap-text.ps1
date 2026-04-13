param(
    [Parameter(Mandatory = $true)]
    [string]$Text,
    [switch]$Exact
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"

$hierarchyPath = Export-UiHierarchy
$result = Invoke-UiTapByText -Text $Text -Exact:$Exact -HierarchyPath $hierarchyPath
$result | Format-List
