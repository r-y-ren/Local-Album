[CmdletBinding()]
param(
    [string]$RuntimeReportDirectory = "build/reports/lite-release/runtime-classpath",
    [string]$OutputDirectory = "build/reports/lite-release/compliance",
    [string]$PurposePolicy = "scripts/lite-artifact-purpose-policy.json"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Resolve-WorkspacePath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $workspace $Path
}

function Relative-Path([string]$Path) {
    $absolutePath = [System.IO.Path]::GetFullPath($Path)
    $separator = [System.IO.Path]::DirectorySeparatorChar
    $workspaceRoot = $workspace.TrimEnd([char[]]"\/") + $separator
    $workspaceUri = New-Object System.Uri($workspaceRoot)
    $pathUri = New-Object System.Uri($absolutePath)
    return [System.Uri]::UnescapeDataString(
        $workspaceUri.MakeRelativeUri($pathUri).ToString()
    ).Replace("\", "/")
}

function File-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}

function Get-ObjectProperty($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) { return $Object[$Name] }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Escape-Purl([string]$Value) {
    return [System.Uri]::EscapeDataString($Value).Replace("%2F", "/")
}

function Parse-ResolvedComponents([string]$Path) {
    $components = @{}
    $unresolvedLines = New-Object System.Collections.ArrayList

    foreach ($line in Get-Content $Path) {
        $treeMatch = [regex]::Match($line, '(?:\+---|\\---)\s+(.+)$')
        if (-not $treeMatch.Success) { continue }
        $payload = $treeMatch.Groups[1].Value.Trim()
        $payload = $payload -replace '\s+\((?:\*|c|n)\)\s*$', ''
        if ($payload -match '\s+FAILED\s*$') {
            [void]$unresolvedLines.Add($payload)
            continue
        }
        if ($payload.StartsWith("project :")) {
            $projectName = $payload.Substring("project :".Length).Trim()
            if (-not [string]::IsNullOrWhiteSpace($projectName)) {
                $key = "local-project:$projectName:bundled"
                $components[$key] = [ordered]@{
                    group = "local-project"
                    name = $projectName
                    version = "bundled"
                    source = "gradle-project-dependency"
                }
            }
            continue
        }

        $left = $payload
        $resolved = $null
        if ($payload.Contains(" -> ")) {
            $parts = $payload.Split(@(" -> "), 2, [System.StringSplitOptions]::None)
            $left = $parts[0].Trim()
            $resolved = $parts[1].Trim()
        }
        $leftParts = $left.Split(':')
        if ($leftParts.Length -lt 2) { continue }

        $group = $leftParts[0]
        $name = $leftParts[1]
        $version = if ($leftParts.Length -ge 3) { $leftParts[2] } else { $null }
        if (-not [string]::IsNullOrWhiteSpace($resolved)) {
            $resolvedParts = $resolved.Split(':')
            if ($resolvedParts.Length -ge 3) {
                $group = $resolvedParts[0]
                $name = $resolvedParts[1]
                $version = $resolvedParts[2]
            } else {
                $version = $resolved
            }
        }
        $version = ([string]$version) -replace '\s+\(.*\)\s*$', ''
        if ([string]::IsNullOrWhiteSpace($group) -or
            [string]::IsNullOrWhiteSpace($name) -or
            [string]::IsNullOrWhiteSpace($version)) {
            continue
        }

        $key = "$group`:$name`:$version"
        $components[$key] = [ordered]@{
            group = $group
            name = $name
            version = $version
            source = "gradle-runtime-classpath"
        }
    }

    return [ordered]@{
        components = @($components.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Value })
        unresolvedLines = @($unresolvedLines)
    }
}

function Find-CachedPom([string]$Group, [string]$Name, [string]$Version) {
    $gradleHome = if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        $env:GRADLE_USER_HOME
    } else {
        Join-Path ([System.Environment]::GetFolderPath("UserProfile")) ".gradle"
    }
    $moduleRoot = Join-Path $gradleHome "caches/modules-2/files-2.1/$Group/$Name/$Version"
    if (-not (Test-Path $moduleRoot -PathType Container)) { return $null }
    return Get-ChildItem $moduleRoot -Recurse -File -Filter *.pom -ErrorAction SilentlyContinue |
        Sort-Object FullName |
        Select-Object -First 1
}

function Read-PomLicenses([string]$Group, [string]$Name, [string]$Version) {
    $pom = Find-CachedPom $Group $Name $Version
    if ($null -eq $pom) {
        return [ordered]@{ source = "cached_pom_not_found"; licenses = @() }
    }
    try {
        [xml]$xml = Get-Content $pom.FullName -Raw
        $licenses = New-Object System.Collections.ArrayList
        $licenseNodes = $xml.SelectNodes(
            "/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license']"
        )
        foreach ($license in @($licenseNodes)) {
            if ($null -eq $license) { continue }
            $nameNode = $license.SelectSingleNode("*[local-name()='name']")
            $urlNode = $license.SelectSingleNode("*[local-name()='url']")
            $licenseName = if ($null -eq $nameNode) { "" } else { [string]$nameNode.InnerText }
            $licenseUrl = if ($null -eq $urlNode) { "" } else { [string]$urlNode.InnerText }
            if ([string]::IsNullOrWhiteSpace($licenseName) -and
                [string]::IsNullOrWhiteSpace($licenseUrl)) {
                continue
            }
            [void]$licenses.Add([ordered]@{
                name = if ([string]::IsNullOrWhiteSpace($licenseName)) { "unspecified" } else { $licenseName.Trim() }
                url = if ([string]::IsNullOrWhiteSpace($licenseUrl)) { $null } else { $licenseUrl.Trim() }
            })
        }
        return [ordered]@{
            source = Relative-Path $pom.FullName
            licenses = @($licenses)
        }
    } catch {
        return [ordered]@{
            source = "cached_pom_parse_failed"
            licenses = @()
        }
    }
}

function Convert-ToCycloneDxLibrary($Component, [string]$Variant) {
    $licenseEvidence = Read-PomLicenses $Component.group $Component.name $Component.version
    $licenses = New-Object System.Collections.ArrayList
    foreach ($license in @($licenseEvidence.licenses)) {
        $licenseObject = [ordered]@{ name = $license.name }
        if (-not [string]::IsNullOrWhiteSpace([string]$license.url)) {
            $licenseObject["url"] = $license.url
        }
        [void]$licenses.Add([ordered]@{ license = $licenseObject })
    }

    $purl = "pkg:maven/$(Escape-Purl $Component.group)/$(Escape-Purl $Component.name)@$(Escape-Purl $Component.version)"
    $result = [ordered]@{
        type = "library"
        group = $Component.group
        name = $Component.name
        version = $Component.version
        "bom-ref" = $purl
        purl = $purl
        properties = @(
            [ordered]@{ name = "localalbum:variant"; value = $Variant },
            [ordered]@{ name = "localalbum:coordinateSource"; value = $Component.source },
            [ordered]@{ name = "localalbum:licenseEvidenceSource"; value = [string]$licenseEvidence.source },
            [ordered]@{ name = "localalbum:licenseReviewStatus"; value = "pending_manual_review" }
        )
    }
    if (@($licenses).Count -gt 0) {
        $result["licenses"] = @($licenses)
    }
    return $result
}

function Test-RuleMatch($Rule, [string]$Path) {
    $matchType = [string](Get-ObjectProperty $Rule "matchType")
    $pattern = [string](Get-ObjectProperty $Rule "pattern")
    if ($matchType -eq "exact") { return $Path -ceq $pattern }
    if ($matchType -eq "prefix") { return $Path.StartsWith($pattern, [System.StringComparison]::Ordinal) }
    throw "Unsupported asset rule matchType '$matchType'."
}

function Get-AssetSourceFiles($Rule) {
    $sourceSet = [string](Get-ObjectProperty $Rule "sourceSet")
    $pattern = [string](Get-ObjectProperty $Rule "pattern")
    if (-not $pattern.StartsWith("assets/")) { return @() }
    $relative = $pattern.Substring("assets/".Length)
    $sourceRoot = Resolve-WorkspacePath "app/src/$sourceSet/assets"
    $matchType = [string](Get-ObjectProperty $Rule "matchType")
    if ($matchType -eq "exact") {
        $file = Join-Path $sourceRoot $relative
        if (Test-Path $file -PathType Leaf) { return @(Get-Item $file) }
        return @()
    }
    if ($matchType -eq "prefix") {
        $directory = Join-Path $sourceRoot $relative.TrimEnd('/')
        if (Test-Path $directory -PathType Container) {
            return @(Get-ChildItem $directory -Recurse -File | Sort-Object FullName)
        }
    }
    return @()
}

function Convert-ToCycloneDxAssets($Policy, [string]$Edition, [string]$Variant) {
    $assetsByPath = @{}
    foreach ($rule in @(Get-ObjectProperty $Policy "assetRules")) {
        if (@((Get-ObjectProperty $rule "allowedEditions")) -notcontains $Edition) { continue }
        foreach ($file in @(Get-AssetSourceFiles $rule)) {
            $sourceSet = [string](Get-ObjectProperty $rule "sourceSet")
            $sourceRoot = (Resolve-Path (Resolve-WorkspacePath "app/src/$sourceSet/assets")).Path.TrimEnd('\')
            $relativeAssetPath = $file.FullName.Substring($sourceRoot.Length).TrimStart([char[]]"\/").Replace("\", "/")
            $packagePath = "assets/$relativeAssetPath"
            if (-not (Test-RuleMatch $rule $packagePath)) { continue }
            $sha256 = File-Sha256 $file.FullName
            $assetsByPath[$packagePath] = [ordered]@{
                type = "file"
                name = $packagePath
                "bom-ref" = "asset:$Edition`:$packagePath`:$sha256"
                hashes = @(
                    [ordered]@{ alg = "SHA-256"; content = $sha256 }
                )
                properties = @(
                    [ordered]@{ name = "localalbum:variant"; value = $Variant },
                    [ordered]@{ name = "localalbum:purpose"; value = [string](Get-ObjectProperty $rule "purpose") },
                    [ordered]@{ name = "localalbum:sourceSet"; value = $sourceSet },
                    [ordered]@{ name = "localalbum:sourcePath"; value = Relative-Path $file.FullName },
                    [ordered]@{ name = "localalbum:licenseReviewStatus"; value = "pending_manual_model_terms_review" }
                )
            }
        }
    }
    return @($assetsByPath.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Value })
}

$runtimePath = Resolve-WorkspacePath $RuntimeReportDirectory
$outputPath = Resolve-WorkspacePath $OutputDirectory
$policyPath = Resolve-WorkspacePath $PurposePolicy
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

if (-not (Test-Path $policyPath -PathType Leaf)) {
    throw "Purpose policy not found: $PurposePolicy"
}
$policy = Get-Content $policyPath -Raw | ConvertFrom-Json
$variants = @(
    [pscustomobject]@{ edition = "full"; variant = "fullRelease"; report = "fullRelease-runtimeClasspath.txt"; applicationId = "com.renyxin.localalbum" },
    [pscustomobject]@{ edition = "lite"; variant = "liteRelease"; report = "liteRelease-runtimeClasspath.txt"; applicationId = "com.renyxin.localalbum.lite" }
)

$statusVariants = New-Object System.Collections.ArrayList
$noticeComponents = @{}
$totalUnknownLicenseCount = 0
$totalModelAssetCount = 0

foreach ($definition in $variants) {
    $reportPath = Join-Path $runtimePath $definition.report
    if (-not (Test-Path $reportPath -PathType Leaf)) {
        throw "Runtime classpath report not found: $($definition.report)"
    }
    $parsed = Parse-ResolvedComponents $reportPath
    if (@($parsed.unresolvedLines).Count -gt 0) {
        throw "Runtime classpath contains unresolved dependencies: $($parsed.unresolvedLines -join '; ')"
    }

    $libraries = New-Object System.Collections.ArrayList
    $unknownLicenseCount = 0
    foreach ($component in @($parsed.components)) {
        $converted = Convert-ToCycloneDxLibrary $component $definition.variant
        if ($null -eq (Get-ObjectProperty $converted "licenses")) {
            $unknownLicenseCount++
        }
        [void]$libraries.Add($converted)
        $noticeKey = "$($component.group):$($component.name):$($component.version)"
        if (-not $noticeComponents.ContainsKey($noticeKey)) {
            $noticeComponents[$noticeKey] = [ordered]@{
                coordinate = $noticeKey
                licenses = @((Get-ObjectProperty $converted "licenses"))
                variants = New-Object System.Collections.ArrayList
            }
        }
        if (-not (@($noticeComponents[$noticeKey].variants) -contains $definition.variant)) {
            [void]$noticeComponents[$noticeKey].variants.Add($definition.variant)
        }
    }

    $assetComponents = @(Convert-ToCycloneDxAssets $policy $definition.edition $definition.variant)
    $totalModelAssetCount += @($assetComponents).Count
    $allComponents = @($libraries) + @($assetComponents)
    $timestamp = [DateTime]::UtcNow.ToString("o")
    $bom = [ordered]@{
        bomFormat = "CycloneDX"
        specVersion = "1.5"
        version = 1
        metadata = [ordered]@{
            timestamp = $timestamp
            tools = @(
                [ordered]@{
                    vendor = "LocalAlbum"
                    name = "Generate-DependencyComplianceEvidence.ps1"
                    version = "1"
                }
            )
            component = [ordered]@{
                type = "application"
                group = "com.renyxin"
                name = $definition.applicationId
                version = "0.1.0"
                "bom-ref" = "pkg:generic/$($definition.applicationId)@0.1.0?variant=$($definition.variant)"
                licenses = @(
                    [ordered]@{ license = [ordered]@{ id = "Apache-2.0" } }
                )
                properties = @(
                    [ordered]@{ name = "localalbum:edition"; value = $definition.edition },
                    [ordered]@{ name = "localalbum:variant"; value = $definition.variant },
                    [ordered]@{ name = "localalbum:policyVersion"; value = [string]$policy.policyVersion },
                    [ordered]@{ name = "localalbum:releaseApproval"; value = "not_granted_by_automated_inventory" }
                )
            }
        }
        components = @($allComponents)
    }

    $bomPath = Join-Path $outputPath "$($definition.variant).cdx.json"
    $bom | ConvertTo-Json -Depth 20 | Set-Content -Path $bomPath -Encoding utf8
    $totalUnknownLicenseCount += $unknownLicenseCount
    [void]$statusVariants.Add([ordered]@{
        edition = $definition.edition
        variant = $definition.variant
        runtimeReport = [ordered]@{
            path = Relative-Path $reportPath
            sha256 = File-Sha256 $reportPath
        }
        sbom = [ordered]@{
            path = Relative-Path $bomPath
            sha256 = File-Sha256 $bomPath
        }
        libraryComponentCount = @($libraries).Count
        modelAndAssetComponentCount = @($assetComponents).Count
        componentsWithoutCachedPomLicenseDeclaration = $unknownLicenseCount
    })
}

$noticeLines = New-Object System.Collections.ArrayList
foreach ($line in @(
    "LOCALALBUM GENERATED THIRD-PARTY NOTICE INVENTORY",
    "",
    "STATUS: PENDING MANUAL LICENSE AND MODEL TERMS REVIEW",
    "This file is an automated component inventory, not legal approval and not a substitute for upstream license texts.",
    "The release remains blocked until every dependency and bundled model/data asset has an approved license/notice disposition.",
    "",
    "Project license: Apache-2.0 (see repository LICENSE).",
    "",
    "Maven/runtime components:"
)) { [void]$noticeLines.Add($line) }

foreach ($entry in @($noticeComponents.GetEnumerator() | Sort-Object Name)) {
    $licenseNames = New-Object System.Collections.ArrayList
    foreach ($wrapper in @($entry.Value.licenses)) {
        if ($null -eq $wrapper) { continue }
        $license = Get-ObjectProperty $wrapper "license"
        $name = Get-ObjectProperty $license "name"
        if (-not [string]::IsNullOrWhiteSpace([string]$name)) { [void]$licenseNames.Add([string]$name) }
    }
    $licenseText = if (@($licenseNames).Count -gt 0) {
        (@($licenseNames | Select-Object -Unique) -join "; ") + " (cached POM declaration; manual verification required)"
    } else {
        "UNKNOWN (cached POM missing or has no license declaration)"
    }
    [void]$noticeLines.Add("- $($entry.Value.coordinate)")
    [void]$noticeLines.Add("  Variants: $(@($entry.Value.variants) -join ', ')")
    [void]$noticeLines.Add("  License evidence: $licenseText")
}

[void]$noticeLines.Add("")
[void]$noticeLines.Add("Bundled model/data assets:")
foreach ($rule in @($policy.assetRules | Sort-Object pattern)) {
    [void]$noticeLines.Add("- $($rule.pattern)")
    [void]$noticeLines.Add("  Purpose: $($rule.purpose)")
    [void]$noticeLines.Add("  Allowed editions: $(@($rule.allowedEditions) -join ', ')")
    [void]$noticeLines.Add("  License/model terms: PENDING MANUAL REVIEW")
}

$noticePath = Join-Path $outputPath "THIRD-PARTY-NOTICE.generated.txt"
$noticeLines | Set-Content -Path $noticePath -Encoding utf8

$status = [ordered]@{
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    generator = "scripts/Generate-DependencyComplianceEvidence.ps1"
    inventoryStatus = "generated"
    licenseReviewStatus = "pending_manual_license_and_model_terms_review"
    releaseApproved = $false
    legalApprovalClaimed = $false
    variants = @($statusVariants)
    aggregate = [ordered]@{
        componentsWithoutCachedPomLicenseDeclaration = $totalUnknownLicenseCount
        bundledModelAndAssetComponents = $totalModelAssetCount
    }
    notice = [ordered]@{
        path = Relative-Path $noticePath
        sha256 = File-Sha256 $noticePath
    }
}
$statusPath = Join-Path $outputPath "compliance-status.json"
$status | ConvertTo-Json -Depth 20 | Set-Content -Path $statusPath -Encoding utf8

Write-Host "Compliance inventory written to $(Relative-Path $outputPath)"
Write-Host "License review status: $($status.licenseReviewStatus)"
