[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [ValidateSet(1000, 10000)]
    [int]$MediaCount = 1000,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = [System.IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $root) {
    if (-not $Force) {
        throw "OutputRoot already exists. Use -Force to replace it."
    }
    Remove-Item -LiteralPath $root -Recurse -Force
}
New-Item -ItemType Directory -Path $root -Force | Out-Null

# A valid 1x1 JPEG keeps the generated fixture decodable without external tooling.
$jpegBase64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/AP/EABQQAQAAAAAAAAAAAAAAAAAAACD/2gAIAQEAAQUCf//EABQRAQAAAAAAAAAAAAAAAAAAACD/2gAIAQMBAT8Bf//EABQRAQAAAAAAAAAAAAAAAAAAACD/2gAIAQIBAT8Bf//EABQQAQAAAAAAAAAAAAAAAAAAACD/2gAIAQEABj8Cf//Z"
$jpegBytes = [Convert]::FromBase64String($jpegBase64)

$entries = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $MediaCount; $index++) {
    $directory = Join-Path $root ("album-{0:D3}" -f ($index % 20))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $fileName = "media-{0:D6}.jpg" -f $index
    $path = Join-Path $directory $fileName
    [System.IO.File]::WriteAllBytes($path, $jpegBytes)
    $entries.Add([pscustomobject]@{
        filePath = $path
        operation = "unchanged"
        stableKey = "fixture-$index"
    })
}

$manifest = [pscustomobject]@{
    format = "localalbum-lite-scan-fixture-v1"
    generatedAt = [DateTime]::UtcNow.ToString("o")
    mediaCount = $MediaCount
    root = $root
    notes = @(
        "Generated files are valid still images for deterministic scanner smoke tests.",
        "Add real videos, EXIF variants, corrupt files and permission cases for release measurements."
    )
    mutations = @(
        [pscustomobject]@{ operation = "add"; stableKey = "mutation-add-1"; relativePath = "album-000/new-photo.jpg" },
        [pscustomobject]@{ operation = "modify"; stableKey = "fixture-0"; relativePath = "album-000/media-000000.jpg" },
        [pscustomobject]@{ operation = "delete"; stableKey = "fixture-1"; relativePath = "album-001/media-000001.jpg" },
        [pscustomobject]@{ operation = "rename"; stableKey = "fixture-2"; relativePath = "album-002/media-000002.jpg"; targetRelativePath = "album-003/moved-000002.jpg" }
    )
    entries = $entries
}

$manifestPath = Join-Path $root "fixture-manifest.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Output "Created Lite scan fixture: $root ($MediaCount images)"
Write-Output "Manifest: $manifestPath"
