[CmdletBinding()]
param(
    [string]$OutputDirectory = "build/reports/lite-release",
    [string]$ApkRoot = "app/build/outputs/apk",
    [string]$BundleRoot = "app/build/outputs/bundle",
    [string]$R8MappingRoot = "app/build/outputs/mapping",
    [string]$SchemaDirectory = "app/schemas/com.renyxin.localalbum.data.db.AppDatabase",
    [string]$PurposePolicy = "scripts/lite-artifact-purpose-policy.json",
    [string]$RuntimeReportDirectory = "build/reports/lite-release/runtime-classpath",
    [string]$ComplianceReportDirectory = "build/reports/lite-release/compliance",
    [string]$HistoricalFullApk = "",
    [switch]$RequireArtifacts,
    [switch]$ReleaseOnly,
    [bool]$FailOnGuardViolation = $true
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

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

function File-Evidence([System.IO.FileInfo]$File) {
    return [ordered]@{
        path = Relative-Path $File.FullName
        bytes = $File.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -Path $File.FullName).Hash.ToLowerInvariant()
    }
}

function Find-Tool([string[]]$Candidates) {
    foreach ($candidate in @($Candidates)) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($null -ne $command) { return $command.Source }
        if (Test-Path $candidate -PathType Leaf) { return (Resolve-Path $candidate).Path }
    }
    return $null
}

function Read-LocalSdkPath {
    $properties = Join-Path $workspace "local.properties"
    if (Test-Path $properties -PathType Leaf) {
        $line = Get-Content $properties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($null -ne $line) {
            $configuredPath = ($line -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
            if (Test-Path $configuredPath -PathType Container) { return $configuredPath }
        }
    }
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$candidate) -and
            (Test-Path $candidate -PathType Container)) {
            return [string]$candidate
        }
    }
    return $null
}

function Find-NewestBuildTool([string]$Name) {
    $sdkPath = Read-LocalSdkPath
    if ([string]::IsNullOrWhiteSpace($sdkPath)) { return $null }
    $root = Join-Path $sdkPath "build-tools"
    if (-not (Test-Path $root -PathType Container)) { return $null }
    return Get-ChildItem $root -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9\.]', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName $Name } |
        Where-Object { Test-Path $_ -PathType Leaf } |
        Select-Object -First 1
}

function Get-ObjectProperty($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary] -and $Object.Contains($Name)) {
        return $Object[$Name]
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-EditionPolicy($Policy, [string]$Edition) {
    $editions = Get-ObjectProperty $Policy "editions"
    return Get-ObjectProperty $editions $Edition
}

function Test-RuleMatch($Rule, [string]$Path) {
    $matchType = [string](Get-ObjectProperty $Rule "matchType")
    $pattern = [string](Get-ObjectProperty $Rule "pattern")
    if ($matchType -eq "exact") {
        return $Path.Equals($pattern, [System.StringComparison]::Ordinal)
    }
    if ($matchType -eq "prefix") {
        return $Path.StartsWith($pattern, [System.StringComparison]::Ordinal)
    }
    if ($matchType -eq "wildcard") {
        return $Path -clike $pattern
    }
    throw "Unsupported artifact rule matchType '$matchType' for '$pattern'."
}

function Find-AssetRule($Policy, [string]$Path) {
    foreach ($rule in @(Get-ObjectProperty $Policy "assetRules")) {
        if (Test-RuleMatch $rule $Path) { return $rule }
    }
    return $null
}

function Test-GuardedAssetPath($Policy, [string]$Path) {
    foreach ($prefix in @(Get-ObjectProperty $Policy "guardedAssetPrefixes")) {
        if ($Path.StartsWith([string]$prefix, [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

function Get-CanonicalArchivePath([string]$ArchivePath, [string]$Kind) {
    $normalized = $ArchivePath.Replace("\", "/")
    if ($Kind -eq "aab" -and $normalized -match '^[^/]+/(assets|lib)/') {
        return $normalized.Substring($normalized.IndexOf('/') + 1)
    }
    return $normalized
}

function Get-ArchiveEntries([string]$Path, [string]$Kind) {
    $entries = New-Object System.Collections.ArrayList
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($entry in $archive.Entries) {
            if ([string]::IsNullOrWhiteSpace($entry.Name)) { continue }
            $canonical = Get-CanonicalArchivePath $entry.FullName $Kind
            if (-not ($canonical.StartsWith("assets/") -or $canonical.StartsWith("lib/"))) {
                continue
            }
            [void]$entries.Add([ordered]@{
                archivePath = $entry.FullName.Replace("\", "/")
                canonicalPath = $canonical
                bytes = $entry.Length
                compressedBytes = $entry.CompressedLength
            })
        }
    } finally {
        $archive.Dispose()
    }
    return @($entries | Sort-Object canonicalPath, archivePath)
}

function Test-ClassWithinRoot([string]$ClassName, [string]$Root) {
    return $ClassName.Equals($Root, [System.StringComparison]::Ordinal) -or
        $ClassName.StartsWith($Root + '$', [System.StringComparison]::Ordinal)
}

function Convert-ClassNameToDexDescriptor([string]$ClassName) {
    return "L$($ClassName.Replace('.', '/'));"
}

function Convert-DexDescriptorToClassName([string]$Descriptor) {
    if ($Descriptor.Length -lt 3 -or
        -not $Descriptor.StartsWith("L", [System.StringComparison]::Ordinal) -or
        -not $Descriptor.EndsWith(";", [System.StringComparison]::Ordinal)) {
        return $null
    }
    return $Descriptor.Substring(1, $Descriptor.Length - 2).Replace('/', '.')
}

function Read-R8ForbiddenClassMappings(
    [string]$Path,
    [string[]]$ForbiddenRoots
) {
    $records = New-Object System.Collections.ArrayList
    $reader = [System.IO.File]::OpenText($Path)
    try {
        while (($line = $reader.ReadLine()) -ne $null) {
            if ($line.Length -eq 0 -or
                [char]::IsWhiteSpace($line[0]) -or
                $line[0] -eq '#') {
                continue
            }
            $separator = " -> "
            $arrow = $line.IndexOf($separator, [System.StringComparison]::Ordinal)
            if ($arrow -le 0 -or -not $line.EndsWith(":", [System.StringComparison]::Ordinal)) {
                continue
            }
            $residualStart = $arrow + $separator.Length
            $residualLength = $line.Length - $residualStart - 1
            if ($residualLength -le 0) { continue }
            $originalClass = $line.Substring(0, $arrow)
            $residualClass = $line.Substring($residualStart, $residualLength)
            foreach ($root in @($ForbiddenRoots)) {
                if (Test-ClassWithinRoot $originalClass $root) {
                    [void]$records.Add([ordered]@{
                        root = $root
                        originalClass = $originalClass
                        residualClass = $residualClass
                    })
                    break
                }
            }
        }
    } finally {
        $reader.Dispose()
    }
    return @($records)
}

function Read-DexDescriptors([string]$DexDump, [string]$DexPath) {
    $descriptors = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $descriptorLineCount = 0
    $parseFailureCount = 0
    $exitCode = -1
    $failure = $null
    try {
        & $DexDump $DexPath 2>&1 | ForEach-Object {
            $line = [string]$_
            if ($line.IndexOf("Class descriptor", [System.StringComparison]::Ordinal) -lt 0) {
                return
            }
            $descriptorLineCount++
            $firstQuote = $line.IndexOf([char]39)
            $lastQuote = $line.LastIndexOf([char]39)
            if ($firstQuote -lt 0 -or $lastQuote -le $firstQuote) {
                $parseFailureCount++
                return
            }
            $descriptor = $line.Substring($firstQuote + 1, $lastQuote - $firstQuote - 1)
            if ($null -eq (Convert-DexDescriptorToClassName $descriptor)) {
                $parseFailureCount++
                return
            }
            [void]$descriptors.Add($descriptor)
        }
        $exitCode = $LASTEXITCODE
    } catch {
        $failure = $_.Exception.Message
        if ($LASTEXITCODE -is [int]) { $exitCode = $LASTEXITCODE }
    }
    return [pscustomobject]@{
        descriptors = @($descriptors)
        descriptorCount = $descriptors.Count
        descriptorLineCount = $descriptorLineCount
        parseFailureCount = $parseFailureCount
        exitCode = $exitCode
        error = $failure
    }
}

function Inspect-ApkSignature([string]$Path, [string]$ApkSigner) {
    if ([string]::IsNullOrWhiteSpace($ApkSigner)) {
        return [ordered]@{
            inspected = $false
            verified = $false
            certificateSha256 = $null
            report = "apksigner_not_found"
        }
    }
    $raw = (& $ApkSigner verify --verbose --print-certs $Path 2>&1 | Out-String).Trim()
    $exitCode = $LASTEXITCODE
    $match = [regex]::Match(
        $raw,
        'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    $digest = $null
    if ($match.Success) {
        $digest = $match.Groups[1].Value.Replace(":", "").ToLowerInvariant()
    }
    return [ordered]@{
        inspected = $true
        verified = ($exitCode -eq 0)
        certificateSha256 = $digest
        report = $raw
    }
}

function Inspect-AabSignature([string]$Path, [string]$KeyTool, [string]$JarSigner) {
    $certificateReport = "keytool_not_found"
    $digest = $null
    if (-not [string]::IsNullOrWhiteSpace($KeyTool)) {
        $certificateReport = (& $KeyTool -printcert -jarfile $Path 2>&1 | Out-String).Trim()
        $match = [regex]::Match(
            $certificateReport,
            'SHA256:\s*([0-9a-fA-F:]+)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if ($match.Success) {
            $digest = $match.Groups[1].Value.Replace(":", "").ToLowerInvariant()
        }
    }

    $verificationReport = "jarsigner_not_found"
    $verified = $false
    if (-not [string]::IsNullOrWhiteSpace($JarSigner)) {
        $verificationReport = (& $JarSigner -verify -certs $Path 2>&1 | Out-String).Trim()
        $verified = ($LASTEXITCODE -eq 0)
    }
    return [ordered]@{
        inspected = (-not [string]::IsNullOrWhiteSpace($KeyTool))
        verified = $verified
        certificateSha256 = $digest
        certificateReport = $certificateReport
        verificationReport = $verificationReport
    }
}

$outputPath = Resolve-WorkspacePath $OutputDirectory
$apkPath = Resolve-WorkspacePath $ApkRoot
$bundlePath = Resolve-WorkspacePath $BundleRoot
$mappingRootPath = Resolve-WorkspacePath $R8MappingRoot
$schemaPath = Resolve-WorkspacePath $SchemaDirectory
$policyPath = Resolve-WorkspacePath $PurposePolicy
$runtimePath = Resolve-WorkspacePath $RuntimeReportDirectory
$compliancePath = Resolve-WorkspacePath $ComplianceReportDirectory
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

if (-not (Test-Path $policyPath -PathType Leaf)) {
    throw "Artifact purpose policy not found: $PurposePolicy"
}
$policy = Get-Content $policyPath -Raw | ConvertFrom-Json
$guardViolations = New-Object System.Collections.ArrayList
$releaseBlockers = New-Object System.Collections.ArrayList
$artifactContexts = New-Object System.Collections.ArrayList
$candidateMatrix = New-Object System.Collections.ArrayList
$existingCandidatePaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$releaseMetadataByEdition = @{}

# Only these declared Full/Lite variants are release evidence inputs. Generic debug/release outputs are stale/non-candidates.
$releaseApkDefinitions = @(
    [pscustomobject]@{ edition = "full"; variant = "fullRelease"; buildType = "release"; metadata = "full/release/output-metadata.json"; expectedApplicationId = "com.renyxin.localalbum" },
    [pscustomobject]@{ edition = "lite"; variant = "liteRelease"; buildType = "release"; metadata = "lite/release/output-metadata.json"; expectedApplicationId = "com.renyxin.localalbum.lite" }
)
$apkDefinitions = if ($ReleaseOnly) {
    $releaseApkDefinitions
} else {
    @(
        [pscustomobject]@{ edition = "full"; variant = "fullDebug"; buildType = "debug"; metadata = "full/debug/output-metadata.json"; expectedApplicationId = "com.renyxin.localalbum.debug" },
        [pscustomobject]@{ edition = "lite"; variant = "liteDebug"; buildType = "debug"; metadata = "lite/debug/output-metadata.json"; expectedApplicationId = "com.renyxin.localalbum.lite.debug" }
    ) + $releaseApkDefinitions
}

foreach ($definition in $apkDefinitions) {
    $metadataFile = Join-Path $apkPath $definition.metadata
    if (-not (Test-Path $metadataFile -PathType Leaf)) {
        [void]$candidateMatrix.Add([ordered]@{
            kind = "apk"
            edition = $definition.edition
            variant = $definition.variant
            status = "not_built"
            metadataPath = Relative-Path $metadataFile
        })
        if ($RequireArtifacts) {
            [void]$guardViolations.Add([ordered]@{
                type = "missing_declared_artifact"
                edition = $definition.edition
                variant = $definition.variant
                detail = "APK output metadata is missing."
            })
        }
        continue
    }

    $metadataJson = Get-Content $metadataFile -Raw | ConvertFrom-Json
    $actualVariant = [string](Get-ObjectProperty $metadataJson "variantName")
    $actualApplicationId = [string](Get-ObjectProperty $metadataJson "applicationId")
    if ($actualVariant -ne $definition.variant) {
        [void]$guardViolations.Add([ordered]@{
            type = "variant_metadata_mismatch"
            edition = $definition.edition
            variant = $definition.variant
            detail = "Metadata variant '$actualVariant' does not match declared variant."
        })
    }
    if ($actualApplicationId -ne $definition.expectedApplicationId) {
        [void]$guardViolations.Add([ordered]@{
            type = "application_id_mismatch"
            edition = $definition.edition
            variant = $definition.variant
            expected = $definition.expectedApplicationId
            actual = $actualApplicationId
        })
    }

    $builtCount = 0
    foreach ($element in @(Get-ObjectProperty $metadataJson "elements")) {
        $outputFileName = [string](Get-ObjectProperty $element "outputFile")
        $artifactPath = Join-Path (Split-Path $metadataFile -Parent) $outputFileName
        if (-not (Test-Path $artifactPath -PathType Leaf)) {
            [void]$guardViolations.Add([ordered]@{
                type = "metadata_output_missing"
                edition = $definition.edition
                variant = $definition.variant
                detail = $outputFileName
            })
            continue
        }
        $artifactFile = Get-Item $artifactPath
        [void]$existingCandidatePaths.Add($artifactFile.FullName)
        $metadataEvidence = [ordered]@{
            source = Relative-Path $metadataFile
            applicationId = $actualApplicationId
            versionCode = Get-ObjectProperty $element "versionCode"
            versionName = Get-ObjectProperty $element "versionName"
        }
        $record = [ordered]@{
            kind = "apk"
            edition = $definition.edition
            variant = $definition.variant
            buildType = $definition.buildType
            releaseCandidate = ($definition.buildType -eq "release")
            file = File-Evidence $artifactFile
            metadata = $metadataEvidence
        }
        [void]$artifactContexts.Add([pscustomobject]@{
            AbsolutePath = $artifactFile.FullName
            Kind = "apk"
            Edition = $definition.edition
            Variant = $definition.variant
            BuildType = $definition.buildType
            Record = $record
        })
        $builtCount++
        if ($definition.buildType -eq "release") {
            $releaseMetadataByEdition[$definition.edition] = $metadataEvidence
        }
    }
    [void]$candidateMatrix.Add([ordered]@{
        kind = "apk"
        edition = $definition.edition
        variant = $definition.variant
        status = if ($builtCount -gt 0) { "built" } else { "metadata_without_output" }
        metadataPath = Relative-Path $metadataFile
        outputCount = $builtCount
    })
}

$bundleDefinitions = @(
    [pscustomobject]@{ edition = "full"; variant = "fullRelease"; directory = "fullRelease" },
    [pscustomobject]@{ edition = "lite"; variant = "liteRelease"; directory = "liteRelease" }
)
foreach ($definition in $bundleDefinitions) {
    $directory = Join-Path $bundlePath $definition.directory
    $bundleFiles = @()
    if (Test-Path $directory -PathType Container) {
        $bundleFiles = @(Get-ChildItem $directory -File -Filter *.aab | Sort-Object FullName)
    }
    if (@($bundleFiles).Count -eq 0) {
        [void]$candidateMatrix.Add([ordered]@{
            kind = "aab"
            edition = $definition.edition
            variant = $definition.variant
            status = "not_built"
            outputDirectory = Relative-Path $directory
        })
        if ($RequireArtifacts) {
            [void]$guardViolations.Add([ordered]@{
                type = "missing_declared_artifact"
                edition = $definition.edition
                variant = $definition.variant
                detail = "AAB output is missing."
            })
        }
        continue
    }
    foreach ($bundleFile in $bundleFiles) {
        [void]$existingCandidatePaths.Add($bundleFile.FullName)
        $pairedMetadata = $null
        if ($releaseMetadataByEdition.ContainsKey($definition.edition)) {
            $pairedMetadata = $releaseMetadataByEdition[$definition.edition]
        }
        $record = [ordered]@{
            kind = "aab"
            edition = $definition.edition
            variant = $definition.variant
            buildType = "release"
            releaseCandidate = $true
            file = File-Evidence $bundleFile
            metadata = [ordered]@{
                source = "paired_release_apk_metadata"
                applicationId = if ($null -ne $pairedMetadata) { $pairedMetadata.applicationId } else { (Get-EditionPolicy $policy $definition.edition).applicationId }
                versionCode = if ($null -ne $pairedMetadata) { $pairedMetadata.versionCode } else { $null }
                versionName = if ($null -ne $pairedMetadata) { $pairedMetadata.versionName } else { $null }
            }
        }
        [void]$artifactContexts.Add([pscustomobject]@{
            AbsolutePath = $bundleFile.FullName
            Kind = "aab"
            Edition = $definition.edition
            Variant = $definition.variant
            BuildType = "release"
            Record = $record
        })
    }
    [void]$candidateMatrix.Add([ordered]@{
        kind = "aab"
        edition = $definition.edition
        variant = $definition.variant
        status = "built"
        outputDirectory = Relative-Path $directory
        outputCount = @($bundleFiles).Count
    })
}

$excludedArtifacts = New-Object System.Collections.ArrayList
foreach ($root in @($apkPath, $bundlePath)) {
    if (-not (Test-Path $root -PathType Container)) { continue }
    foreach ($file in @(Get-ChildItem $root -Recurse -File | Where-Object { $_.Extension -in @('.apk', '.aab') })) {
        if (-not $existingCandidatePaths.Contains($file.FullName)) {
            [void]$excludedArtifacts.Add([ordered]@{
                file = File-Evidence $file
                reason = "not_declared_full_or_lite_candidate"
            })
        }
    }
}

# Verify policy/stage-plan invariants before using the policy as release evidence.
$litePolicy = Get-EditionPolicy $policy "lite"
$forbiddenLiteStages = @("core:face", "core:semantic", "core:ocr")
foreach ($stage in @((Get-ObjectProperty $litePolicy "corePlan") + (Get-ObjectProperty $litePolicy "automaticEnhancementPlan"))) {
    if ($forbiddenLiteStages -contains [string]$stage) {
        [void]$guardViolations.Add([ordered]@{
            type = "lite_forbidden_automatic_stage"
            edition = "lite"
            detail = [string]$stage
        })
    }
}
$allowedLiteManualStages = @("core:scene", "core:quality")
foreach ($stage in @(Get-ObjectProperty $litePolicy "manualEnhancementPlan")) {
    if ($allowedLiteManualStages -notcontains [string]$stage) {
        [void]$guardViolations.Add([ordered]@{
            type = "lite_unapproved_manual_stage"
            edition = "lite"
            detail = [string]$stage
        })
    }
}

# Inspect actual APK/AAB contents. Archive contents, not source filenames, are the enforcement boundary.
foreach ($context in @($artifactContexts)) {
    $archiveEntries = @(Get-ArchiveEntries $context.AbsolutePath $context.Kind)
    $canonicalPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $purposeEntries = New-Object System.Collections.ArrayList
    foreach ($entry in $archiveEntries) {
        [void]$canonicalPaths.Add([string]$entry.canonicalPath)
        $rule = Find-AssetRule $policy ([string]$entry.canonicalPath)
        $classification = "unclassified_runtime_entry"
        $purpose = $null
        $allowed = $null
        $sourceSet = $null

        if ($null -ne $rule) {
            $classification = if ([string]$entry.canonicalPath -like "lib/*") {
                "governed_native_runtime"
            } else {
                "governed_asset"
            }
            $purpose = Get-ObjectProperty $rule "purpose"
            $sourceSet = Get-ObjectProperty $rule "sourceSet"
            $allowed = @((Get-ObjectProperty $rule "allowedEditions")) -contains $context.Edition
            if (-not $allowed) {
                [void]$guardViolations.Add([ordered]@{
                    type = "asset_not_allowed_for_edition"
                    edition = $context.Edition
                    variant = $context.Variant
                    artifact = $context.Record.file.path
                    path = $entry.canonicalPath
                    purpose = $purpose
                })
            }
        } elseif (Test-GuardedAssetPath $policy ([string]$entry.canonicalPath)) {
            $classification = "unclassified_guarded_asset"
            $allowed = $false
            [void]$guardViolations.Add([ordered]@{
                type = "unclassified_guarded_asset"
                edition = $context.Edition
                variant = $context.Variant
                artifact = $context.Record.file.path
                path = $entry.canonicalPath
            })
        } elseif ([string]$entry.canonicalPath -like "lib/*") {
            foreach ($nativeRule in @(Get-ObjectProperty $policy "sharedNativeRuntimeRules")) {
                if ([string]$entry.canonicalPath -like [string](Get-ObjectProperty $nativeRule "pattern")) {
                    $classification = "shared_native_runtime"
                    $purpose = Get-ObjectProperty $nativeRule "purpose"
                    $allowed = $true
                    break
                }
            }
        }

        [void]$purposeEntries.Add([ordered]@{
            archivePath = $entry.archivePath
            canonicalPath = $entry.canonicalPath
            bytes = $entry.bytes
            compressedBytes = $entry.compressedBytes
            classification = $classification
            purpose = $purpose
            allowed = $allowed
            sourceSet = $sourceSet
        })
    }

    $editionPolicy = Get-EditionPolicy $policy $context.Edition
    $missingRequiredAssets = New-Object System.Collections.ArrayList
    foreach ($requiredPath in @(Get-ObjectProperty $editionPolicy "requiredAssetPaths")) {
        if (-not $canonicalPaths.Contains([string]$requiredPath)) {
            [void]$missingRequiredAssets.Add([string]$requiredPath)
            [void]$guardViolations.Add([ordered]@{
                type = "required_capability_asset_missing"
                edition = $context.Edition
                variant = $context.Variant
                artifact = $context.Record.file.path
                path = [string]$requiredPath
            })
        }
    }
    $context.Record["archivePurposeInventory"] = @($purposeEntries)
    $context.Record["missingRequiredAssets"] = @($missingRequiredAssets)
}

# Inspect final Lite Release DEX descriptors. R8 text reports alone are not an enforcement boundary.
$dexGuardAudits = New-Object System.Collections.ArrayList
$dexGuardFailureCount = 0
$dexGuardRoot = Get-ObjectProperty $policy "dexGuards"
$dexGuardPolicy = Get-ObjectProperty $dexGuardRoot "lite"
$forbiddenDexClassRoots = @()
if ($null -ne $dexGuardPolicy) {
    $forbiddenDexClassRoots = @(
        @(Get-ObjectProperty $dexGuardPolicy "forbiddenClassRoots") |
            ForEach-Object { [string]$_ } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Unique
    )
}
$declaredDexArtifactKind = [string](Get-ObjectProperty $dexGuardPolicy "artifactKind")
$declaredDexVariant = [string](Get-ObjectProperty $dexGuardPolicy "variant")
if ($null -eq $dexGuardPolicy -or
    $declaredDexArtifactKind -ne "apk" -or
    $declaredDexVariant -ne "liteRelease" -or
    @($forbiddenDexClassRoots).Count -eq 0) {
    $dexGuardFailureCount++
    [void]$guardViolations.Add([ordered]@{
        type = "invalid_lite_dex_guard_policy"
        edition = "lite"
        variant = "liteRelease"
        detail = "Policy must declare a non-empty Lite Release APK forbiddenClassRoots list."
    })
}

$dexdump = Find-Tool @(
    (Find-NewestBuildTool "dexdump.exe"),
    (Find-NewestBuildTool "dexdump"),
    "dexdump.exe",
    "dexdump"
)
$liteReleaseApks = @(
    $artifactContexts | Where-Object {
        $_.Kind -eq "apk" -and
        $_.Edition -eq "lite" -and
        $_.Variant -eq "liteRelease" -and
        $_.BuildType -eq "release"
    }
)
$mappingFilePath = Join-Path $mappingRootPath "liteRelease/mapping.txt"
$mappingEvidence = $null
$forbiddenMappingRecords = @()
if (@($liteReleaseApks).Count -gt 0) {
    if ([string]::IsNullOrWhiteSpace($dexdump)) {
        $dexGuardFailureCount++
        [void]$guardViolations.Add([ordered]@{
            type = "lite_dexdump_tool_missing"
            edition = "lite"
            variant = "liteRelease"
            detail = "Android build-tools dexdump is required to inspect final DEX descriptors."
        })
    }
    if (-not (Test-Path $mappingFilePath -PathType Leaf)) {
        $dexGuardFailureCount++
        [void]$guardViolations.Add([ordered]@{
            type = "lite_r8_mapping_missing"
            edition = "lite"
            variant = "liteRelease"
            detail = Relative-Path $mappingFilePath
        })
    } else {
        $mappingFile = Get-Item $mappingFilePath
        $mappingEvidence = File-Evidence $mappingFile
        if (@($forbiddenDexClassRoots).Count -gt 0) {
            try {
                $forbiddenMappingRecords = @(
                    Read-R8ForbiddenClassMappings $mappingFile.FullName $forbiddenDexClassRoots
                )
            } catch {
                $dexGuardFailureCount++
                [void]$guardViolations.Add([ordered]@{
                    type = "lite_r8_mapping_parse_failed"
                    edition = "lite"
                    variant = "liteRelease"
                    detail = $_.Exception.Message
                })
            }
        }
    }
}

foreach ($context in $liteReleaseApks) {
    $artifactFailureCount = 0
    $dexFiles = New-Object System.Collections.ArrayList
    $hits = New-Object System.Collections.ArrayList
    $hitKeys = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $allDescriptors = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $descriptorLocations = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    $temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("localalbum-lite-dex-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $temporaryDirectory | Out-Null
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($context.AbsolutePath)
        $dexEntries = @(
            $archive.Entries | Where-Object {
                $_.FullName.Replace("\", "/") -match '^classes(?:[0-9]+)?\.dex$'
            } | Sort-Object FullName
        )
        if (@($dexEntries).Count -eq 0) {
            $artifactFailureCount++
            $dexGuardFailureCount++
            [void]$guardViolations.Add([ordered]@{
                type = "lite_apk_contains_no_dex"
                edition = "lite"
                variant = $context.Variant
                artifact = $context.Record.file.path
            })
        }
        foreach ($entry in $dexEntries) {
            $dexName = [System.IO.Path]::GetFileName($entry.FullName)
            $extractedPath = Join-Path $temporaryDirectory $dexName
            $inputStream = $entry.Open()
            $outputStream = [System.IO.File]::Create($extractedPath)
            try {
                $inputStream.CopyTo($outputStream)
            } finally {
                $outputStream.Dispose()
                $inputStream.Dispose()
            }

            if ([string]::IsNullOrWhiteSpace($dexdump)) {
                [void]$dexFiles.Add([ordered]@{
                    archivePath = $entry.FullName.Replace("\", "/")
                    bytes = $entry.Length
                    compressedBytes = $entry.CompressedLength
                    descriptorCount = 0
                    status = "not_inspected_dexdump_missing"
                })
                continue
            }

            $dexResult = Read-DexDescriptors $dexdump $extractedPath
            foreach ($descriptor in @($dexResult.descriptors)) {
                [void]$allDescriptors.Add([string]$descriptor)
                if (-not $descriptorLocations.ContainsKey([string]$descriptor)) {
                    $descriptorLocations.Add([string]$descriptor, $entry.FullName.Replace("\", "/"))
                }
            }
            $dexStatus = "passed"
            if ($dexResult.exitCode -ne 0) {
                $dexStatus = "failed_dexdump"
                $artifactFailureCount++
                $dexGuardFailureCount++
                [void]$guardViolations.Add([ordered]@{
                    type = "lite_dexdump_failed"
                    edition = "lite"
                    variant = $context.Variant
                    artifact = $context.Record.file.path
                    dex = $entry.FullName.Replace("\", "/")
                    exitCode = $dexResult.exitCode
                    detail = $dexResult.error
                })
            }
            if ($dexResult.parseFailureCount -gt 0) {
                $dexStatus = "failed_descriptor_parse"
                $artifactFailureCount++
                $dexGuardFailureCount++
                [void]$guardViolations.Add([ordered]@{
                    type = "lite_dex_descriptor_parse_failed"
                    edition = "lite"
                    variant = $context.Variant
                    artifact = $context.Record.file.path
                    dex = $entry.FullName.Replace("\", "/")
                    parseFailureCount = $dexResult.parseFailureCount
                })
            }
            if ($dexResult.descriptorCount -eq 0) {
                $dexStatus = "failed_zero_descriptors"
                $artifactFailureCount++
                $dexGuardFailureCount++
                [void]$guardViolations.Add([ordered]@{
                    type = "lite_dex_zero_descriptors"
                    edition = "lite"
                    variant = $context.Variant
                    artifact = $context.Record.file.path
                    dex = $entry.FullName.Replace("\", "/")
                })
            }
            [void]$dexFiles.Add([ordered]@{
                archivePath = $entry.FullName.Replace("\", "/")
                bytes = $entry.Length
                compressedBytes = $entry.CompressedLength
                descriptorCount = $dexResult.descriptorCount
                descriptorLineCount = $dexResult.descriptorLineCount
                parseFailureCount = $dexResult.parseFailureCount
                dexdumpExitCode = $dexResult.exitCode
                status = $dexStatus
            })
        }
    } catch {
        $artifactFailureCount++
        $dexGuardFailureCount++
        [void]$guardViolations.Add([ordered]@{
            type = "lite_dex_archive_inspection_failed"
            edition = "lite"
            variant = $context.Variant
            artifact = $context.Record.file.path
            detail = $_.Exception.Message
        })
    } finally {
        if ($null -ne $archive) { $archive.Dispose() }
        Remove-Item $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }

    foreach ($descriptor in @($allDescriptors)) {
        $className = Convert-DexDescriptorToClassName ([string]$descriptor)
        if ($null -eq $className) { continue }
        foreach ($root in @($forbiddenDexClassRoots)) {
            if (Test-ClassWithinRoot $className $root) {
                $key = "$root|$descriptor"
                if ($hitKeys.Add($key)) {
                    [void]$hits.Add([ordered]@{
                        root = $root
                        originalClass = $className
                        residualClass = $className
                        descriptor = [string]$descriptor
                        dex = $descriptorLocations[[string]$descriptor]
                        detectedBy = "original_descriptor"
                    })
                }
            }
        }
    }
    foreach ($mapping in @($forbiddenMappingRecords)) {
        $descriptor = Convert-ClassNameToDexDescriptor ([string]$mapping.residualClass)
        if ($allDescriptors.Contains($descriptor)) {
            $key = "$($mapping.root)|$descriptor"
            if ($hitKeys.Add($key)) {
                [void]$hits.Add([ordered]@{
                    root = $mapping.root
                    originalClass = $mapping.originalClass
                    residualClass = $mapping.residualClass
                    descriptor = $descriptor
                    dex = $descriptorLocations[$descriptor]
                    detectedBy = "r8_mapping"
                })
            }
        }
    }
    $hitRootSet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $hitRoots = New-Object System.Collections.ArrayList
    foreach ($hit in @($hits)) {
        $hitRootValue = Get-ObjectProperty $hit "root"
        $hitRoot = [string]$hitRootValue
        if ([string]::IsNullOrWhiteSpace($hitRoot)) { continue }
        if ($hitRootSet.Add($hitRoot)) {
            [void]$hitRoots.Add($hitRoot)
        }
    }
    foreach ($hitRoot in @($hitRoots)) {
        $rootMatches = New-Object System.Collections.ArrayList
        foreach ($hit in @($hits)) {
            $candidateRootValue = Get-ObjectProperty $hit "root"
            $candidateRoot = [string]$candidateRootValue
            if ($candidateRoot -eq $hitRoot) {
                [void]$rootMatches.Add($hit)
            }
        }
        $artifactFailureCount++
        $dexGuardFailureCount++
        [void]$guardViolations.Add([ordered]@{
            type = "lite_forbidden_dex_class_present"
            edition = "lite"
            variant = $context.Variant
            artifact = $context.Record.file.path
            classRoot = $hitRoot
            matches = @($rootMatches)
        })
    }

    [void]$dexGuardAudits.Add([ordered]@{
        artifact = $context.Record.file.path
        artifactSha256 = $context.Record.file.sha256
        variant = $context.Variant
        status = if ($artifactFailureCount -eq 0) { "passed" } else { "failed" }
        descriptorCount = $allDescriptors.Count
        dexFiles = @($dexFiles)
        forbiddenHits = @($hits)
    })
}
$dexGuardStatus = if (@($liteReleaseApks).Count -eq 0) {
    "not_evaluated_no_lite_release_apk"
} elseif ($dexGuardFailureCount -eq 0) {
    "passed"
} else {
    "failed"
}

# Audit source-set placement as a second line of defense against future model-download regressions.
$sourceInputs = New-Object System.Collections.ArrayList
$sourceRoots = @(
    [pscustomobject]@{ path = "app/src/main/assets"; sourceSet = "main"; packagePrefix = "assets" },
    [pscustomobject]@{ path = "app/src/full/assets"; sourceSet = "full"; packagePrefix = "assets" },
    [pscustomobject]@{ path = "app/src/main/jniLibs"; sourceSet = "main"; packagePrefix = "lib" },
    [pscustomobject]@{ path = "app/src/main/cpp"; sourceSet = "main"; packagePrefix = "native-build-input" }
)
foreach ($sourceRoot in $sourceRoots) {
    $absoluteRoot = Resolve-WorkspacePath $sourceRoot.path
    if (-not (Test-Path $absoluteRoot -PathType Container)) { continue }
    $resolvedRoot = (Resolve-Path $absoluteRoot).Path.TrimEnd('\')
    foreach ($file in @(Get-ChildItem $resolvedRoot -Recurse -File | Sort-Object FullName)) {
        $relativeWithinRoot = $file.FullName.Substring($resolvedRoot.Length).TrimStart([char[]]"\/").Replace("\", "/")
        $packagePath = "$($sourceRoot.packagePrefix)/$relativeWithinRoot"
        $rule = Find-AssetRule $policy $packagePath
        $classification = "build_input"
        $purpose = $null
        $expectedSourceSet = $null
        if ($null -ne $rule) {
            $classification = "governed_asset_source"
            $purpose = Get-ObjectProperty $rule "purpose"
            $expectedSourceSet = [string](Get-ObjectProperty $rule "sourceSet")
            if ($expectedSourceSet -ne $sourceRoot.sourceSet) {
                [void]$guardViolations.Add([ordered]@{
                    type = "asset_in_wrong_source_set"
                    path = $packagePath
                    actualSourceSet = $sourceRoot.sourceSet
                    expectedSourceSet = $expectedSourceSet
                    purpose = $purpose
                })
            }
        } elseif (Test-GuardedAssetPath $policy $packagePath) {
            $classification = "unclassified_guarded_asset_source"
            [void]$guardViolations.Add([ordered]@{
                type = "unclassified_guarded_asset_source"
                path = $packagePath
                actualSourceSet = $sourceRoot.sourceSet
            })
        }
        [void]$sourceInputs.Add([ordered]@{
            sourceSet = $sourceRoot.sourceSet
            packagePath = $packagePath
            classification = $classification
            purpose = $purpose
            expectedSourceSet = $expectedSourceSet
            file = File-Evidence $file
        })
    }
}

$schemas = New-Object System.Collections.ArrayList
if (Test-Path $schemaPath -PathType Container) {
    foreach ($file in @(Get-ChildItem $schemaPath -File -Filter *.json | Sort-Object Name)) {
        $schemaJson = Get-Content $file.FullName -Raw | ConvertFrom-Json
        [void]$schemas.Add([ordered]@{
            version = $schemaJson.database.version
            identityHash = $schemaJson.database.identityHash
            artifact = File-Evidence $file
        })
    }
}

$apksigner = Find-Tool @((Find-NewestBuildTool "apksigner.bat"), (Find-NewestBuildTool "apksigner"), "apksigner")
$keytoolCandidates = @("keytool.exe", "keytool")
$jarsignerCandidates = @("jarsigner.exe", "jarsigner")
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $keytoolCandidates = @((Join-Path $env:JAVA_HOME "bin/keytool.exe"), (Join-Path $env:JAVA_HOME "bin/keytool")) + $keytoolCandidates
    $jarsignerCandidates = @((Join-Path $env:JAVA_HOME "bin/jarsigner.exe"), (Join-Path $env:JAVA_HOME "bin/jarsigner")) + $jarsignerCandidates
}
$keytool = Find-Tool $keytoolCandidates
$jarsigner = Find-Tool $jarsignerCandidates
$signatures = New-Object System.Collections.ArrayList
$currentReleaseDigests = @{}
foreach ($context in @($artifactContexts)) {
    $inspection = if ($context.Kind -eq "apk") {
        Inspect-ApkSignature $context.AbsolutePath $apksigner
    } else {
        Inspect-AabSignature $context.AbsolutePath $keytool $jarsigner
    }
    $signature = [ordered]@{
        path = $context.Record.file.path
        kind = $context.Kind
        edition = $context.Edition
        variant = $context.Variant
        inspection = $inspection
        candidateStatus = if ($inspection.verified -and -not [string]::IsNullOrWhiteSpace([string]$inspection.certificateSha256)) { "signed_build_artifact" } else { "unsigned_or_unverified_build_smoke_only" }
    }
    [void]$signatures.Add($signature)
    if ($context.BuildType -eq "release" -and $context.Kind -eq "apk" -and $inspection.verified) {
        $currentReleaseDigests[$context.Edition] = $inspection.certificateSha256
    }
}

$historicalFullEvidence = $null
$fullUpgradeChainStatus = "blocked_missing_historical_release_baseline"
if (-not [string]::IsNullOrWhiteSpace($HistoricalFullApk)) {
    $historicalPath = Resolve-WorkspacePath $HistoricalFullApk
    if (-not (Test-Path $historicalPath -PathType Leaf)) {
        $fullUpgradeChainStatus = "blocked_historical_release_baseline_not_found"
    } else {
        $historicalFile = Get-Item $historicalPath
        $historicalInspection = Inspect-ApkSignature $historicalFile.FullName $apksigner
        $historicalFullEvidence = [ordered]@{
            file = File-Evidence $historicalFile
            signature = $historicalInspection
        }
        if (-not $currentReleaseDigests.ContainsKey("full")) {
            $fullUpgradeChainStatus = "blocked_current_full_release_signature_unavailable"
        } elseif ([string]$historicalInspection.certificateSha256 -eq [string]$currentReleaseDigests["full"]) {
            $fullUpgradeChainStatus = "verified_certificate_digest_match"
        } else {
            $fullUpgradeChainStatus = "failed_certificate_digest_mismatch"
        }
    }
}
if ($fullUpgradeChainStatus -ne "verified_certificate_digest_match") {
    [void]$releaseBlockers.Add($fullUpgradeChainStatus)
}

$sdk = Read-LocalSdkPath
$adbCandidates = @("adb.exe", "adb")
if (-not [string]::IsNullOrWhiteSpace($sdk)) {
    $adbCandidates = @((Join-Path $sdk "platform-tools/adb.exe"), (Join-Path $sdk "platform-tools/adb")) + $adbCandidates
}
$adb = Find-Tool $adbCandidates
$devices = @()
if ($null -ne $adb) {
    $deviceLines = & $adb devices 2>$null | Select-Object -Skip 1
    $devices = @($deviceLines | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
}
$deviceCount = @($devices).Count
if ($deviceCount -eq 0) {
    [void]$releaseBlockers.Add("blocked_no_connected_device_instrumentation_performance_stability_evidence")
}

$runtimeReports = New-Object System.Collections.ArrayList
$expectedRuntimeReports = @(
    "fullDebug-runtimeClasspath.txt",
    "liteDebug-runtimeClasspath.txt",
    "fullRelease-runtimeClasspath.txt",
    "liteRelease-runtimeClasspath.txt"
)
foreach ($name in $expectedRuntimeReports) {
    $file = Join-Path $runtimePath $name
    if (Test-Path $file -PathType Leaf) {
        [void]$runtimeReports.Add([ordered]@{ name = $name; status = "present"; file = File-Evidence (Get-Item $file) })
    } else {
        [void]$runtimeReports.Add([ordered]@{ name = $name; status = "missing"; file = $null })
        [void]$releaseBlockers.Add("missing_runtime_classpath_report:$name")
    }
}

$complianceReports = New-Object System.Collections.ArrayList
$complianceStatus = $null
$complianceStatusPath = Join-Path $compliancePath "compliance-status.json"
if (Test-Path $compliancePath -PathType Container) {
    foreach ($file in @(Get-ChildItem $compliancePath -Recurse -File | Sort-Object FullName)) {
        [void]$complianceReports.Add((File-Evidence $file))
    }
}
if (@($complianceReports).Count -eq 0 -or
    -not (Test-Path $complianceStatusPath -PathType Leaf)) {
    [void]$releaseBlockers.Add("missing_sbom_notice_evidence")
} else {
    $complianceStatus = Get-Content $complianceStatusPath -Raw | ConvertFrom-Json
    if (-not [bool]$complianceStatus.releaseApproved) {
        [void]$releaseBlockers.Add(
            "compliance_manual_review_pending:$($complianceStatus.licenseReviewStatus)"
        )
    }
}

$gitCommit = (& git -C $workspace rev-parse HEAD 2>$null | Out-String).Trim()
$gitDirty = -not [string]::IsNullOrWhiteSpace((& git -C $workspace status --porcelain 2>$null | Out-String))
if ($gitDirty) { [void]$releaseBlockers.Add("dirty_workspace_not_frozen_release_candidate") }
[void]$releaseBlockers.Add("lite_signing_security_operations_decision_pending")

$guardViolationCount = @($guardViolations).Count
$guardStatus = if (@($artifactContexts).Count -eq 0) {
    "not_evaluated_no_candidate_artifacts"
} elseif ($guardViolationCount -eq 0) {
    "passed"
} else {
    "failed"
}

$artifactRecords = @($artifactContexts | ForEach-Object { $_.Record })
$report = [ordered]@{
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    gitCommit = $gitCommit
    gitDirty = $gitDirty
    environment = [ordered]@{
        os = [System.Environment]::OSVersion.VersionString
        powershell = $PSVersionTable.PSVersion.ToString()
        adb = $adb
        connectedDevices = @($devices)
        tools = [ordered]@{
            apksigner = $apksigner
            keytool = $keytool
            jarsigner = $jarsigner
            dexdump = $dexdump
        }
    }
    releaseDecisions = [ordered]@{
        policyVersion = $policy.policyVersion
        sceneAutomaticEnhancement = "disabled_fail_closed_policy_v2"
        qualityAutomaticEnhancement = "disabled_fail_closed_policy_v2"
        liteManualOcr = "disabled_v1"
        scanVsFaceSwap = "core_scan_priority_face_swap_queued"
        coexistence = "separate_application_id_configured"
        fullApplicationId = "com.renyxin.localalbum"
        liteApplicationId = "com.renyxin.localalbum.lite"
        liteSigning = "pending_security_operations_decision"
    }
    policyAndStagePlan = [ordered]@{
        policyFile = File-Evidence (Get-Item $policyPath)
        declaration = $policy
    }
    candidateMatrix = @($candidateMatrix)
    artifacts = $artifactRecords
    excludedArtifacts = @($excludedArtifacts)
    signatures = @($signatures)
    signingChain = [ordered]@{
        fullUpgradeChainStatus = $fullUpgradeChainStatus
        historicalFullRelease = $historicalFullEvidence
        currentFullCertificateSha256 = if ($currentReleaseDigests.ContainsKey("full")) { $currentReleaseDigests["full"] } else { $null }
        currentLiteCertificateSha256 = if ($currentReleaseDigests.ContainsKey("lite")) { $currentReleaseDigests["lite"] } else { $null }
    }
    schemas = @($schemas)
    sourceInputs = @($sourceInputs)
    runtimeClasspathReports = @($runtimeReports)
    complianceEvidence = [ordered]@{
        reports = @($complianceReports)
        status = $complianceStatus
    }
    artifactPurposeGuard = [ordered]@{
        status = $guardStatus
        violationCount = $guardViolationCount
        violations = @($guardViolations)
    }
    dexDescriptorGuard = [ordered]@{
        status = $dexGuardStatus
        tool = $dexdump
        mapping = $mappingEvidence
        mappingMatchesForForbiddenRoots = @($forbiddenMappingRecords).Count
        forbiddenClassRoots = @($forbiddenDexClassRoots)
        audits = @($dexGuardAudits)
    }
    deviceEvidenceStatus = if ($deviceCount -gt 0) { "device_available_not_run_by_this_script" } else { "blocked_no_connected_device" }
    releaseReadiness = [ordered]@{
        status = if (@($releaseBlockers).Count -eq 0 -and $guardStatus -eq "passed") { "ready" } else { "blocked" }
        blockers = @($releaseBlockers | Select-Object -Unique)
    }
}

$jsonPath = Join-Path $outputPath "release-evidence.json"
$report | ConvertTo-Json -Depth 20 | Set-Content -Path $jsonPath -Encoding utf8

$summary = @(
    "# LocalAlbum Lite Release Evidence",
    "",
    "- Generated (UTC): $($report.generatedAtUtc)",
    "- Git commit: $($report.gitCommit)",
    "- Dirty workspace: $($report.gitDirty)",
    "- Connected Android devices: $deviceCount",
    "- Declared artifacts recorded: $(@($artifactRecords).Count)",
    "- Excluded stale/non-candidate artifacts: $(@($excludedArtifacts).Count)",
    "- Room schemas recorded: $(@($schemas).Count)",
    "- Source asset/build inputs recorded: $(@($sourceInputs).Count)",
    "- Artifact-purpose guard: $guardStatus ($guardViolationCount violation(s))",
    "- Lite final DEX descriptor guard: $dexGuardStatus",
    "- Compliance review: $(if ($null -eq $complianceStatus) { 'missing' } else { $complianceStatus.licenseReviewStatus })",
    "- Full upgrade signing chain: $fullUpgradeChainStatus",
    "- Device evidence status: $($report.deviceEvidenceStatus)",
    "- Release readiness: $($report.releaseReadiness.status)",
    "",
    "Machine-readable evidence: release-evidence.json"
)
$summary | Set-Content -Path (Join-Path $outputPath "release-evidence.md") -Encoding utf8

Write-Host "Release evidence written to $(Relative-Path $jsonPath)"
Write-Host "Artifact-purpose guard: $guardStatus ($guardViolationCount violation(s))"
Write-Host "Lite final DEX descriptor guard: $dexGuardStatus"
if ($FailOnGuardViolation -and $guardViolationCount -gt 0) {
    throw "Artifact-purpose guard failed with $guardViolationCount violation(s). See $(Relative-Path $jsonPath)."
}
