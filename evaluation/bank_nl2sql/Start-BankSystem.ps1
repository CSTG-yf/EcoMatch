<#
.SYNOPSIS
    Build, start and bootstrap the local bank question system.

.DESCRIPTION
    This is the single Windows development entry point for the bank question
    Agent. It orchestrates the repository's existing build, H2 import, daemon
    and HTTP bootstrap entry points. It does not write runtime database files
    or call Java services directly.

    On a fresh checkout the sequence is:
      1. Build the standalone release when it is not present.
      2. Import the frozen official bank tables into the stopped H2 database.
      3. Start the existing standalone daemon on port 9080.
      4. Wait for HTTP readiness.
      5. Discover/create/update the bank resources through the HTTP API.
      6. Leave chat-model binding to the administrator after startup.

    When the service is already reachable, the stopped-state H2 import is
    skipped and the HTTP bootstrap is run again. This makes repeated runs a
    safe way to apply Agent prompt or pipeline changes without creating a
    second Agent.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:9080",
    [string]$MetadataDatabase,
    [string]$ReleaseVersion = "2.0.6",
    [int]$WaitSeconds = 180,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$BuildEntry = Join-Path $RepoRoot "assembly\bin\supersonic-build.bat"
$DaemonEntry = Join-Path $RepoRoot "assembly\bin\supersonic-daemon.bat"
$ImportEntry = Join-Path $RepoRoot "evaluation\bank_nl2sql\db\Import-OfficialBankData.ps1"
$BootstrapEntry = Join-Path $RepoRoot "evaluation\bank_nl2sql\bootstrap_bank_agent.py"
$PythonEntry = Join-Path $RepoRoot "evaluation\.venv\Scripts\python.exe"
$PythonRequirements = Join-Path $RepoRoot "evaluation\requirements.txt"
$DatasetDirectory = Join-Path $RepoRoot "evaluation\bank_nl2sql"
$ReleaseDirectory = Join-Path $RepoRoot "assembly\build\supersonic-standalone-1.0.0-SNAPSHOT"

if (-not $MetadataDatabase) {
    $MetadataDatabase = Join-Path $RepoRoot ".local-dev\state\semantic"
}
$MetadataDatabase = [System.IO.Path]::GetFullPath($MetadataDatabase)
$ReceiptDirectory = Join-Path $RepoRoot ".local-dev\bank-nl2sql\official-v3"
$ReceiptPath = Join-Path $ReceiptDirectory "bootstrap-receipt.json"

function Assert-File {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Description)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description was not found: $Path"
    }
}

function Invoke-BatchEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    Write-Host ("Running existing entry point: {0} {1}" -f $Path, ($Arguments -join " "))
    & $Path @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Entry point failed with exit code $($LASTEXITCODE): $Path"
    }
}

function Test-ServiceReady {
    try {
        $null = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd("/") + "/") -Method Get -UseBasicParsing -TimeoutSec 5
        return $true
    } catch {
        # A reachable Spring application may answer with 3xx/4xx while still
        # booting its protected API. Any HTTP response means the port is live;
        # connection failures are the only not-ready case here.
        if ($_.Exception.Response) {
            return $true
        }
        return $false
    }
}

function Wait-ForService {
    param([Parameter(Mandatory = $true)][int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-ServiceReady) {
            Write-Host "Service is reachable at $BaseUrl"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Service did not become reachable within $TimeoutSeconds seconds: $BaseUrl"
}

function Ensure-PythonEnvironment {
    if (Test-Path -LiteralPath $PythonEntry -PathType Leaf) {
        & $PythonEntry -c "import openpyxl"
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Write-Host "Project Python environment is missing dependencies; installing evaluation requirements..."
    } else {
        $systemPython = Get-Command python -ErrorAction SilentlyContinue
        if (-not $systemPython) {
            $systemPython = Get-Command py -ErrorAction SilentlyContinue
        }
        if (-not $systemPython) {
            throw "Python 3 was not found. Install Python 3.10+ and rerun this entry point."
        }
        Write-Host "Creating project Python environment: $PythonEntry"
        if ($systemPython.Name -eq "py.exe") {
            & $systemPython.Source -3 -m venv (Split-Path -Parent (Split-Path -Parent $PythonEntry))
        } else {
            & $systemPython.Source -m venv (Split-Path -Parent (Split-Path -Parent $PythonEntry))
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to create the project Python environment."
        }
    }

    Assert-File $PythonRequirements "evaluation Python requirements"
    & $PythonEntry -m pip install -r $PythonRequirements
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to install evaluation Python requirements."
    }
}

function Test-MetadataDatabasePresent {
    return (
        (Test-Path -LiteralPath $MetadataDatabase -PathType Leaf) -or
        (Test-Path -LiteralPath "$MetadataDatabase.mv.db" -PathType Leaf) -or
        (Test-Path -LiteralPath "$MetadataDatabase.lock.db" -PathType Leaf)
    )
}

Assert-File $ImportEntry "official H2 import entry point"
Assert-File $BootstrapEntry "HTTP Agent bootstrap script"
Ensure-PythonEnvironment

$env:S2_METADATA_DB_PATH = $MetadataDatabase
Write-Host "Bank question system metadata database: $MetadataDatabase"

$serviceReady = Test-ServiceReady
if (-not $serviceReady) {
    if (-not $SkipBuild -and -not (Test-Path -LiteralPath $ReleaseDirectory -PathType Container)) {
        Assert-File $BuildEntry "standalone build entry point"
        Invoke-BatchEntry -Path $BuildEntry -Arguments @("standalone")
    } elseif (-not (Test-Path -LiteralPath $ReleaseDirectory -PathType Container)) {
        throw "Standalone release is missing and -SkipBuild was specified: $ReleaseDirectory"
    }

    Write-Host "Importing official bank data through the existing H2 import entry point..."
    & $ImportEntry -TargetDatabase $MetadataDatabase -ReleaseVersion $ReleaseVersion
    if ($LASTEXITCODE -ne 0) {
        throw "Official bank data import failed with exit code $LASTEXITCODE"
    }

    Assert-File $DaemonEntry "standalone daemon entry point"
    Invoke-BatchEntry -Path $DaemonEntry -Arguments @("start")
    Wait-ForService -TimeoutSeconds $WaitSeconds
} else {
    if (-not (Test-MetadataDatabasePresent)) {
        throw "Port 9080 is already occupied by a service that does not use the requested metadata database '$MetadataDatabase'. Close that service or choose another BaseUrl, then rerun."
    }
    Write-Host "An existing service is already reachable; skipping stopped-state H2 import."
}

New-Item -ItemType Directory -Path $ReceiptDirectory -Force | Out-Null
Write-Host "Applying the bank Agent through the HTTP bootstrap API..."
& $PythonEntry $BootstrapEntry $DatasetDirectory `
    --base-url $BaseUrl `
    --expected-h2-database $MetadataDatabase `
    --output $ReceiptPath
if ($LASTEXITCODE -ne 0) {
    throw "HTTP bank Agent bootstrap failed with exit code $LASTEXITCODE"
}

try {
    $receipt = Get-Content -Raw -LiteralPath $ReceiptPath | ConvertFrom-Json
} catch {
    throw "Bootstrap completed but its receipt is not valid JSON: $ReceiptPath"
}
if (-not $receipt.agentId -or [int]$receipt.agentId -le 0) {
    throw "Bootstrap receipt does not contain a valid agentId: $ReceiptPath"
}
if ([string]$receipt.officialVersion -ne $ReleaseVersion) {
    throw "Bootstrap receipt officialVersion '$($receipt.officialVersion)' does not match requested release '$ReleaseVersion'"
}

Write-Host "Bank question system is ready."
Write-Host ("  Web:     {0}" -f $BaseUrl)
Write-Host ("  Agent:   {0} (id={1})" -f $receipt.agentName, $receipt.agentId)
Write-Host ("  Dataset: {0} (official={1})" -f $receipt.dataSetId, $receipt.officialVersion)
Write-Host ("  Receipt: {0}" -f $ReceiptPath)
