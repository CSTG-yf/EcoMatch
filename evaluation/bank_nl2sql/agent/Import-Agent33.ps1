<#
.SYNOPSIS
    Imports the versioned Agent 33 definition into a local H2 metadata database.

.DESCRIPTION
    This is a separate Agent import. It does not import benchmark tables and it
    never replaces the database file. Stop the SuperSonic runtime first so H2
    is not locked. The chat-model endpoint and key are intentionally excluded
    from the package; configure chat model id 1 in the target environment.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Import-Agent33.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Import-Agent33.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]$TargetDatabase,
    [string]$JavaPath,
    [string]$H2JarPath,
    [int]$ChatModelId = 1,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$ConfigPath = Join-Path $PSScriptRoot "agent-33.json"

if (-not $TargetDatabase) {
    $TargetDatabase = Join-Path $RepoRoot ".local-dev\state\semantic"
}
$TargetDatabase = [System.IO.Path]::GetFullPath($TargetDatabase)

function Resolve-Java {
    if ($JavaPath) { return (Resolve-Path -LiteralPath $JavaPath).Path }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $candidate = Get-ChildItem -LiteralPath (Join-Path $RepoRoot ".local-dev\jdk") -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "java.exe not found; pass -JavaPath"
}

function Resolve-H2Jar {
    if ($H2JarPath) { return (Resolve-Path -LiteralPath $H2JarPath).Path }
    $candidates = @(
        (Join-Path $RepoRoot ".local-dev\m2-repository\com\h2database\h2"),
        (Join-Path $RepoRoot ".local-dev\h2"),
        (Join-Path $env:USERPROFILE ".m2\repository\com\h2database\h2")
    )
    foreach ($root in $candidates) {
        if (Test-Path -LiteralPath $root) {
            $jar = Get-ChildItem -LiteralPath $root -Recurse -Filter "h2-*.jar" | Select-Object -First 1
            if ($jar) { return $jar.FullName }
        }
    }
    throw "H2 jar not found; pass -H2JarPath"
}

function Quote-Sql([AllowNull()][string]$Value) {
    if ($null -eq $Value) { return "NULL" }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Json-Compact($Value) {
    if ($null -eq $Value) { return "[]" }
    return ($Value | ConvertTo-Json -Compress -Depth 30)
}

if (-not (Test-Path -LiteralPath $ConfigPath)) { throw "Agent config not found: $ConfigPath" }
$config = Get-Content -Raw -Encoding UTF8 -LiteralPath $ConfigPath | ConvertFrom-Json
$agent = $config.agent
if ([int]$agent.id -ne 33) { throw "Unexpected Agent 33 config identity" }
if (@($agent.toolConfig.tools).Count -ne 1 -or @($agent.toolConfig.tools[0].dataSetIds).Count -ne 2) {
    throw "Agent 33 must bind exactly the two packaged dataset ids"
}
$datasetIds = @($agent.toolConfig.tools[0].dataSetIds | ForEach-Object { [int]$_ })
if (($datasetIds -join ",") -ne "33,65") { throw "Expected dataset ids 33,65" }

$dbFile = "$TargetDatabase.mv.db"
$lockFile = "$TargetDatabase.lock.db"
if (-not (Test-Path -LiteralPath $dbFile)) { throw "Target H2 database not found: $dbFile" }
if (Test-Path -LiteralPath $lockFile) {
    throw "Target H2 database is locked; stop SuperSonic before importing Agent 33: $lockFile"
}

$java = Resolve-Java
$h2 = Resolve-H2Jar
$toolConfig = Json-Compact $agent.toolConfig
$chatConfig = $agent.chatAppConfig
foreach ($property in $chatConfig.PSObject.Properties) {
    if ($property.Value.chatModelId -ne $null -and $property.Value.enable -eq $true) {
        $property.Value.chatModelId = $ChatModelId
    }
}
$chatModelConfig = Json-Compact $chatConfig
$examplesJson = Json-Compact $agent.examples
$adminsJson = Json-Compact $agent.admins
$viewersJson = Json-Compact $agent.viewers
$adminOrgsJson = Json-Compact $agent.adminOrgs
$viewOrgsJson = Json-Compact $agent.viewOrgs

Write-Output "Agent config: $ConfigPath"
Write-Output "Target database: $TargetDatabase"
Write-Output "Dataset ids: $($datasetIds -join ',')"
Write-Output "Chat model id: $ChatModelId"

$jdbcUrl = "jdbc:h2:file:$($TargetDatabase.Replace('\','/'));DATABASE_TO_UPPER=false;IFEXISTS=TRUE"
$existsSql = "SELECT EXISTS(SELECT 1 FROM s2_agent WHERE id=33) AS agent_exists;"
$existsOutput = & $java -cp $h2 org.h2.tools.Shell -url $jdbcUrl -user root -password semantic -sql $existsSql 2>&1
if ($LASTEXITCODE -ne 0) { throw ($existsOutput -join [Environment]::NewLine) }
$hasAgent = ($existsOutput -join "`n") -match "(?im)^\s*TRUE\s*$"

$commonAssignments = @"
name=$(Quote-Sql $agent.name), description=$(Quote-Sql $agent.description),
status=$([int]$agent.status), examples=$(Quote-Sql $examplesJson),
enable_search=$([int]$agent.enableSearch), enable_feedback=$([int]$agent.enableFeedback),
tool_config=$(Quote-Sql $toolConfig), chat_model_config=$(Quote-Sql $chatModelConfig),
visual_config=NULL, admin=$(Quote-Sql $adminsJson),
viewer=$(Quote-Sql $viewersJson), admin_org=$(Quote-Sql $adminOrgsJson),
view_org=$(Quote-Sql $viewOrgsJson), is_open=$([int]$agent.isOpen),
updated_by='agent33-import', updated_at=CURRENT_TIMESTAMP
"@
if ($hasAgent) {
    $sql = "UPDATE s2_agent SET $commonAssignments WHERE id=33;"
} else {
    $sql = @"
INSERT INTO s2_agent (
    id, name, description, status, examples, created_by, created_at,
    updated_by, updated_at, enable_search, enable_feedback, tool_config,
    chat_model_config, visual_config, admin, viewer, admin_org, view_org, is_open
) VALUES (
    33, $(Quote-Sql $agent.name), $(Quote-Sql $agent.description), $([int]$agent.status),
    $(Quote-Sql $examplesJson), 'agent33-import', CURRENT_TIMESTAMP,
    'agent33-import', CURRENT_TIMESTAMP, $([int]$agent.enableSearch),
    $([int]$agent.enableFeedback), $(Quote-Sql $toolConfig),
    $(Quote-Sql $chatModelConfig), NULL, $(Quote-Sql $adminsJson),
    $(Quote-Sql $viewersJson), $(Quote-Sql $adminOrgsJson),
    $(Quote-Sql $viewOrgsJson), $([int]$agent.isOpen)
);
"@
}
$sql += " SELECT id, name, status, is_open FROM s2_agent WHERE id=33;"
if ($DryRun) {
    Write-Output "target agent exists: $hasAgent"
    Write-Output "dry-run: no database write"
    return
}

$sqlFile = [System.IO.Path]::GetTempFileName()
try {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($sqlFile, $sql, $utf8NoBom)
    $output = & $java -cp $h2 org.h2.tools.RunScript -url $jdbcUrl -user root -password semantic -script $sqlFile 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join [Environment]::NewLine) }
} finally {
    Remove-Item -LiteralPath $sqlFile -Force -ErrorAction SilentlyContinue
}
$verifySql = "SELECT id, name, status, is_open FROM s2_agent WHERE id=33;"
$output = & $java -cp $h2 org.h2.tools.Shell -url $jdbcUrl -user root -password semantic -sql $verifySql 2>&1
if ($LASTEXITCODE -ne 0) { throw ($output -join [Environment]::NewLine) }
Write-Output ($output -join [Environment]::NewLine)
if (($output -join "`n") -notmatch "(?m)^\s*33\s+\|") {
    throw "Agent 33 verification row was not returned"
}
Write-Output "Agent 33 import succeeded."
