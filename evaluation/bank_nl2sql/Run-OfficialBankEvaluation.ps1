<#
.SYNOPSIS
Runs the only supported Bank NL2SQL evaluation protocol.

.DESCRIPTION
Uses the fixed v2.0.1 data release, an Agent bootstrap receipt, isolated
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
    [string]$ModelLabel,

    [Parameter(Mandatory = $true)]
    [string]$BootstrapReceipt,

    [string]$EvidenceRoot,

    [string]$RunRegistry,

    [switch]$AcknowledgeFinalTest,

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
if ($Mode -eq "test" -and (-not $AcknowledgeFinalTest -or [string]::IsNullOrWhiteSpace($RunRegistry))) {
    throw "Test mode requires -AcknowledgeFinalTest and -RunRegistry."
}
if ($Mode -ne "test" -and $AcknowledgeFinalTest) {
    throw "-AcknowledgeFinalTest is valid only for test mode."
}

$RunnerArgs = @(
    $Runner,
    $DatasetDir,
    "--mode", $Mode,
    "--run-id", $RunId,
    "--base-url", $BaseUrl,
    "--agent-id", "$AgentId",
    "--model-label", $ModelLabel,
    "--bootstrap-receipt", $BootstrapReceipt
)
if (-not [string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $RunnerArgs += @("--evidence-root", $EvidenceRoot)
}
if ($Mode -eq "test") {
    $RunnerArgs += @("--acknowledge-final-test", "--run-registry", $RunRegistry)
}
if ($NoResume) {
    $RunnerArgs += "--no-resume"
}

& $Python @RunnerArgs
exit $LASTEXITCODE
