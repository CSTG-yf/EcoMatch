<#
.SYNOPSIS
    Imports the frozen official Bank NL2SQL v2.0.0 benchmark tables/views into a
    local H2 database (companion import package, NOT a runtime semantic.mv.db).

.DESCRIPTION
    This importer is part of the immutable companion import package in
    evaluation/bank_nl2sql/db/releases/2.0.0/. It:
      - verifies database-manifest.json and the SHA-256 of every packaged
        artifact (and of the official source workbook) BEFORE any import;
      - defaults to the repository-local semantic H2 base path
        <repo>\.local-dev\state\semantic (set S2_METADATA_DB_PATH to the same
        base when starting the runtime);
      - refuses an active/locked target database instead of stopping any
        process or deleting anything;
      - applies ONLY the packaged bank-h2.sql script (bank_organization,
        bank_metric_definition, bank_metric_daily and the three
        bank_benchmark.* compatibility views) via org.h2.tools.RunScript with
        the project-standard root/semantic credentials;
      - verifies the three exact row counts (13 organizations, 21 metrics,
        132678 facts) after the import and is idempotent for bank_* objects.
    It never manages processes and never deletes or overwrites arbitrary
    database files, Agent configuration or conversations.

.PARAMETER TargetDatabase
    H2 database base path without extension. Defaults to
    <repo>\.local-dev\state\semantic.

.PARAMETER JavaPath
    Path to java.exe. Defaults to discovery: JAVA_HOME, then a project-local
    JDK under <repo>\.local-dev\jdk, then java on PATH.

.PARAMETER H2JarPath
    Path to h2-*.jar. Defaults to discovery: $env:ECOMATCH_H2_JAR, then
    <repo>\.local-dev\m2-repository\com\h2database\h2, then
    <repo>\.local-dev\h2, then the user Maven repository (~\.m2).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Import-OfficialBankData.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Import-OfficialBankData.ps1 `
        -TargetDatabase C:\tmp\semantic -JavaPath C:\jdk\bin\java.exe `
        -H2JarPath C:\h2\h2-2.2.224.jar
#>
[CmdletBinding()]
param(
    [string]$TargetDatabase,
    [string]$JavaPath,
    [string]$H2JarPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

$OfficialVersion = "2.0.0"
$SchemaVersion = "1.0"
$ExpectedCounts = @{ organizations = 13; metrics = 21; facts = 132678 }
$ExpectedSourceRelativePath = "evaluation/bank_nl2sql/official/2.0.0/bank-nl2sql-ground-truth-v2.0.0.xlsx"

# SAFETY 1: This importer never stops processes (no Stop-Process, no taskkill).
# SAFETY 2: This importer never deletes database files (no Remove-Item, no
#           deletion of *.mv.db / *.lock.db / Agent/model/chat data).
# SAFETY 3: This importer applies ONLY the packaged bank-h2.sql benchmark
#           script (bank_* tables plus bank_benchmark.* views), never any
#           other SQL.
# SAFETY 4: An active/locked target H2 database is refused (exit code 2) and
#           left completely untouched; the user closes the runtime and retries.

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$ReleaseDir = Join-Path $RepoRoot "evaluation\bank_nl2sql\db\releases\2.0.0"
$ManifestPath = Join-Path $ReleaseDir "database-manifest.json"
$SqlitePath = Join-Path $ReleaseDir "bank.sqlite"
$H2ScriptPath = Join-Path $ReleaseDir "bank-h2.sql"
$SourceWorkbookPath = Join-Path $RepoRoot $ExpectedSourceRelativePath

if (-not $TargetDatabase) {
    $TargetDatabase = Join-Path $RepoRoot ".local-dev\state\semantic"
}
$TargetDatabase = [System.IO.Path]::GetFullPath($TargetDatabase)

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Assert-ManifestAndArtifacts {
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        throw "Release manifest not found: $ManifestPath"
    }
    $manifest = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
    if ($manifest.schemaVersion -ne $SchemaVersion) {
        throw "Unsupported manifest schemaVersion '$($manifest.schemaVersion)'; expected '$SchemaVersion'"
    }
    if ($manifest.officialVersion -ne $OfficialVersion) {
        throw "Unexpected officialVersion '$($manifest.officialVersion)'; expected '$OfficialVersion'"
    }
    if ($manifest.source.officialVersion -ne $OfficialVersion) {
        throw "Unexpected source officialVersion '$($manifest.source.officialVersion)'; expected '$OfficialVersion'"
    }
    if ($manifest.source.path -ne $ExpectedSourceRelativePath) {
        throw "Unexpected source path '$($manifest.source.path)'; expected '$ExpectedSourceRelativePath'"
    }
    if (-not (Test-Path -LiteralPath $SourceWorkbookPath)) {
        throw "Official source workbook not found: $SourceWorkbookPath"
    }
    $actualSourceHash = Get-Sha256 $SourceWorkbookPath
    if ($actualSourceHash -ne $manifest.source.sha256.ToLowerInvariant()) {
        throw "Source workbook SHA-256 mismatch: got $actualSourceHash, manifest declares $($manifest.source.sha256)"
    }
    foreach ($artifact in @(
        @{ name = "bank.sqlite"; path = $SqlitePath },
        @{ name = "bank-h2.sql"; path = $H2ScriptPath }
    )) {
        if (-not (Test-Path -LiteralPath $artifact.path)) {
            throw "Release artifact missing: $($artifact.path)"
        }
        $declared = $manifest.artifacts.($artifact.name)
        if (-not $declared) {
            throw "Manifest does not declare artifact '$($artifact.name)'"
        }
        $actualHash = Get-Sha256 $artifact.path
        if ($actualHash -ne $declared.sha256.ToLowerInvariant()) {
            throw "Artifact '$($artifact.name)' SHA-256 mismatch: got $actualHash, manifest declares $($declared.sha256)"
        }
        $actualBytes = (Get-Item -LiteralPath $artifact.path).Length
        if ($actualBytes -ne [int64]$declared.bytes) {
            throw "Artifact '$($artifact.name)' size mismatch: got $actualBytes bytes, manifest declares $($declared.bytes)"
        }
    }
    $counts = $manifest.counts
    if (
        [int]$counts.organizations -ne $ExpectedCounts.organizations -or
        [int]$counts.metrics -ne $ExpectedCounts.metrics -or
        [int]$counts.facts -ne $ExpectedCounts.facts
    ) {
        throw "Manifest counts mismatch: got $($counts.organizations)/$($counts.metrics)/$($counts.facts); " +
            "expected $($ExpectedCounts.organizations)/$($ExpectedCounts.metrics)/$($ExpectedCounts.facts)"
    }
    Write-Host "Manifest verified: $ManifestPath"
    Write-Host "  source: $($manifest.source.path) sha256=$($manifest.source.sha256)"
    Write-Host "  counts: organizations=$($counts.organizations) metrics=$($counts.metrics) facts=$($counts.facts)"
}

function Assert-TargetNotLocked {
    $lockFile = "$TargetDatabase.lock.db"
    if (Test-Path -LiteralPath $lockFile) {
        Write-Host "Refusing to import: target H2 database is active/locked ($lockFile)."
        Write-Host "Close the running SuperSonic service (or use another -TargetDatabase) and retry."
        Write-Host "No process was stopped and no file was modified."
        exit 2
    }
}

function Resolve-Toolchain {
    if (-not $JavaPath) {
        if ($env:JAVA_HOME) {
            $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
            if (Test-Path -LiteralPath $candidate) { $JavaPath = $candidate }
        }
        if (-not $JavaPath) {
            $projectJdks = @(Get-ChildItem -Path (Join-Path $RepoRoot ".local-dev\jdk") -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue | Sort-Object FullName)
            if ($projectJdks.Count -gt 0) { $JavaPath = $projectJdks[-1].FullName }
        }
        if (-not $JavaPath) {
            $onPath = Get-Command java -ErrorAction SilentlyContinue
            if ($onPath) { $JavaPath = $onPath.Source }
        }
    }
    if (-not $H2JarPath) {
        if ($env:ECOMATCH_H2_JAR -and (Test-Path -LiteralPath $env:ECOMATCH_H2_JAR)) {
            $H2JarPath = $env:ECOMATCH_H2_JAR
        }
    }
    if (-not $H2JarPath) {
        $jarCandidates = @()
        $jarCandidates += @(Get-ChildItem -Path (Join-Path $RepoRoot ".local-dev\m2-repository\com\h2database\h2") -Recurse -Filter "h2-*.jar" -ErrorAction SilentlyContinue)
        $jarCandidates += @(Get-ChildItem -Path (Join-Path $RepoRoot ".local-dev\h2") -Filter "h2-*.jar" -ErrorAction SilentlyContinue)
        $userProfile = [Environment]::GetFolderPath("UserProfile")
        $jarCandidates += @(Get-ChildItem -Path (Join-Path $userProfile ".m2\repository\com\h2database\h2") -Recurse -Filter "h2-*.jar" -ErrorAction SilentlyContinue)
        if ($jarCandidates.Count -gt 0) {
            $H2JarPath = ($jarCandidates | Sort-Object FullName)[-1].FullName
        }
    }
    if (-not $JavaPath -or -not (Test-Path -LiteralPath $JavaPath)) {
        throw "Java executable not found. Pass -JavaPath, set JAVA_HOME, or provide a JDK under <repo>\.local-dev\jdk."
    }
    if (-not $H2JarPath -or -not (Test-Path -LiteralPath $H2JarPath)) {
        throw "H2 jar not found. Pass -H2JarPath, set ECOMATCH_H2_JAR, or provide h2-*.jar under <repo>\.local-dev (Maven repository layout is supported)."
    }
    Write-Host "Using java: $JavaPath"
    Write-Host "Using H2 jar: $H2JarPath"
}

function Invoke-RunScript {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Script,
        [switch]$ShowResults
    )
    $arguments = @("-cp", $H2JarPath, "org.h2.tools.RunScript", "-url", $Url, "-user", "root", "-password", "semantic", "-script", $Script)
    if ($ShowResults) { $arguments += "-showResults" }
    & $JavaPath @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "org.h2.tools.RunScript failed with exit code $LASTEXITCODE for script $Script"
    }
}

function Assert-RowCounts {
    $verifyScript = Join-Path ([System.IO.Path]::GetTempPath()) ("bank-import-verify-" + [guid]::NewGuid().ToString("N") + ".sql")
    $verifySql = @(
        "SELECT CASE WHEN (SELECT COUNT(*) FROM bank_organization) = $($ExpectedCounts.organizations) THEN 1 ELSE CAST('bank_organization count != $($ExpectedCounts.organizations)' AS INT) END;",
        "SELECT CASE WHEN (SELECT COUNT(*) FROM bank_metric_definition) = $($ExpectedCounts.metrics) THEN 1 ELSE CAST('bank_metric_definition count != $($ExpectedCounts.metrics)' AS INT) END;",
        "SELECT CASE WHEN (SELECT COUNT(*) FROM bank_metric_daily) = $($ExpectedCounts.facts) THEN 1 ELSE CAST('bank_metric_daily count != $($ExpectedCounts.facts)' AS INT) END;"
    ) -join "`n"
    [System.IO.File]::WriteAllText($verifyScript, $verifySql, (New-Object System.Text.UTF8Encoding($false)))
    try {
        Invoke-RunScript -Url $JdbcUrl -Script $verifyScript
    } finally {
        if ([System.IO.File]::Exists($verifyScript)) {
            [System.IO.File]::Delete($verifyScript)
        }
    }
}

Write-Host "Bank NL2SQL official database import package (companion import package, v$OfficialVersion)"
Write-Host "Target H2 database base: $TargetDatabase"

Assert-ManifestAndArtifacts
Assert-TargetNotLocked
Resolve-Toolchain

$TargetDir = Split-Path -Parent $TargetDatabase
if (-not (Test-Path -LiteralPath $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}
$JdbcUrl = "jdbc:h2:file:" + $TargetDatabase.Replace("\", "/") + ";DATABASE_TO_UPPER=false"

Write-Host "Importing benchmark tables/views from $H2ScriptPath ..."
Invoke-RunScript -Url $JdbcUrl -Script $H2ScriptPath
Write-Host "Import complete; verifying row counts ..."
Assert-RowCounts
Write-Host "Verification PASS: organizations=$($ExpectedCounts.organizations) metrics=$($ExpectedCounts.metrics) facts=$($ExpectedCounts.facts)"
Write-Host "Done. The benchmark tables/views are ready at $JdbcUrl"
