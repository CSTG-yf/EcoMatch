<#
.SYNOPSIS
Runs the only supported Bank NL2SQL evaluation protocol.

.DESCRIPTION
Uses the fixed v2.0.6 data release, an Agent bootstrap receipt, isolated
frontend-style conversations, and Fact v3 caseAccuracy.  It does not expose
legacy score switches or a caller-controlled concurrency setting.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("smoke", "train", "dev", "test")]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$RunId,

    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$AgentId,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$ChatModelId,

    [string]$ModelLabel,

    [Parameter(Mandatory = $true)]
    [string]$BootstrapReceipt,

    [string]$EvidenceRoot,

    [Nullable[int]]$MaxFailures,

    [switch]$NoResume
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

$DatasetDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $DatasetDir "..\..")).Path
$Python = Join-Path $RepoRoot "evaluation\.venv\Scripts\python.exe"
$Runner = Join-Path $DatasetDir "run_official_runtime_eval.py"

if (-not (Test-Path -LiteralPath $Python)) {
    throw "Project evaluation virtual environment is missing: $Python"
}
if (-not (Test-Path -LiteralPath $Runner)) {
    throw "Official evaluation runner is missing: $Runner"
}
if (-not (Test-Path -LiteralPath $BootstrapReceipt)) {
    throw "Bootstrap receipt is missing: $BootstrapReceipt"
}
if ($null -ne $MaxFailures -and $MaxFailures -lt 0) {
    throw "-MaxFailures must be zero or greater."
}
if ($null -ne $MaxFailures -and $Mode -notin @("train", "dev")) {
    throw "-MaxFailures is valid only for train or dev mode."
}

$RunnerArgs = @(
    $Runner,
    $DatasetDir,
    "--mode", $Mode,
    "--run-id", $RunId,
    "--base-url", $BaseUrl,
    "--agent-id", "$AgentId",
    "--chat-model-id", "$ChatModelId",
    "--bootstrap-receipt", $BootstrapReceipt
)
if (-not [string]::IsNullOrWhiteSpace($ModelLabel)) {
    $RunnerArgs += @("--model-label", $ModelLabel)
}
if (-not [string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $RunnerArgs += @("--evidence-root", $EvidenceRoot)
}
if ($null -ne $MaxFailures) {
    $RunnerArgs += @("--max-failures", "$MaxFailures")
}
if ($NoResume) {
    $RunnerArgs += "--no-resume"
}

& $Python @RunnerArgs
exit $LASTEXITCODE
