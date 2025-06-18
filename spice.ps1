#!/usr/bin/env pwsh
#Requires -Version 5.1
# File encoding: UTF-8 with BOM

$ErrorActionPreference = 'Stop'

# Determine if we should bypass Docker and use the JVM-based entry script
$useJvm = ($env:SPICE_LABS_CLI_USE_JVM -eq '1')

# Location of this script
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Docker image details
$dockerImage = "spicelabs/spice-labs-cli:latest"

# Flags for CI mode and pulling
$ciMode    = $false
$pullLatest = $true

# Defaults
$command    = 'run'
$inputPath  = ''
$outputPath = ''
$extraArgs  = @()

function Show-Help {
    Write-Output @"
Usage: spice --command <cmd> [--input <path>] [--output <path>] [--ci] [--quiet|--verbose] [--no-pull]

Commands:
  run                      Scan artifacts and upload ADGs (default)
  scan-artifacts           Generate ADGs only
  upload-adgs              Upload existing ADGs
  upload-deployment-events Upload deployment events from stdin

Options:
  --command CMD            One of: run, scan-artifacts, upload-adgs, upload-deployment-events
  --input PATH             Path to input directory or file
  --output PATH            Path for output (only needed for scan-artifacts or run)
  --ci                     Run in CI/CD mode (non-interactive, implies --quiet unless overridden)
  --quiet                  Suppress output
  --verbose                Enable detailed logging
  --no-pull                Don't pull the latest Docker image
  --help                   Show this help
"@
}

# Parse arguments
for ($i = 0; $i -lt $args.Count; ) {
    switch ($args[$i]) {
        '--command' { $command   = $args[$i+1]; $i += 2; continue }
        '--input'   { $inputPath = $args[$i+1]; $i += 2; continue }
        '--output'  { $outputPath = $args[$i+1]; $i += 2; continue }
        '--ci'      { $ciMode    = $true;    $i++; continue }
        '--no-pull' { $pullLatest = $false;  $i++; continue }
        '--quiet'   { $i++; continue }
        '--verbose' { $extraArgs += '--verbose'; $i++; continue }
        '--help'    { Show-Help; exit 0 }
        default     { $extraArgs += $args[$i];    $i++; continue }
    }
}

# Validate SPICE_PASS except for scan-artifacts
if ($command -ne 'scan-artifacts' -and -not $env:SPICE_PASS) {
    Write-Error "❌ SPICE_PASS environment variable must be set for command '$command'"
    exit 1
}

# Default input to current directory if not set
if (-not $inputPath) { $inputPath = (Get-Location).Path }

# Determine if command writes output
$needsOutput = ($command -in 'scan-artifacts','run')

if ($needsOutput) {
    if (-not $outputPath) {
        $tempDir    = [IO.Path]::Combine([IO.Path]::GetTempPath(), ([guid]::NewGuid().ToString()))
        $outputPath = $tempDir
        New-Item -ItemType Directory -Path $outputPath | Out-Null
    }
    elseif (-not (Test-Path $outputPath)) {
        New-Item -ItemType Directory -Path $outputPath | Out-Null
    }
    # Check writability
    try {
        $testFile = Join-Path $outputPath ".writetest"
        New-Item -ItemType File -Path $testFile -Force | Out-Null
        Remove-Item $testFile -Force
    }
    catch {
        Write-Error "❌ Output directory '$outputPath' is not writable. Please fix permissions and try again."
        exit 1
    }
}

# CI mode -> default to --quiet if neither set
if ($ciMode -and -not ($extraArgs -contains '--quiet') -and -not ($extraArgs -contains '--verbose')) {
    $extraArgs += '--quiet'
}

# Approved verb function for filtering output
function Invoke-FilterOutput {
    param([string[]]$Lines)
    $inHelp = $false
    foreach ($line in $Lines) {
        if ($line -match '::spice-labs-cli-help-start::') { $inHelp = $true; continue }
        if ($line -match '::spice-labs-cli-help-end::')   { $inHelp = $false; continue }
        if ($inHelp) { continue }
        Write-Output ($line -replace '\bspice-labs\.sh\b','spice')
    }
}

# JVM mode
if ($useJvm) {
    if (-not $env:SPICE_LABS_GOAT_RODEO_PATH -or -not $env:SPICE_LABS_GINGER_PATH) {
        Write-Error "❌ When SPICE_LABS_CLI_USE_JVM=1, both SPICE_LABS_GOAT_RODEO_PATH and SPICE_LABS_GINGER_PATH must be set."
        exit 1
    }

    $jvmArgs = @('--command',$command,'--input',$inputPath)
    if ($needsOutput) { $jvmArgs += @('--output',$outputPath) }
    $jvmArgs += $extraArgs

    Write-Output "🚀 Running spice (JVM mode) with command: $command"
    Write-Output "📁 Input:  $inputPath"
    if ($needsOutput) { Write-Output "📁 Output: $outputPath" }

    $raw = & "$scriptDir/spice-labs.sh" @jvmArgs 2>&1
    $exitCode = $LASTEXITCODE
    Invoke-FilterOutput $raw
    if ($exitCode -ne 0) {
        Write-Error "❌ The Spice Labs CLI (JVM mode) failed."
        Show-Help
        exit $exitCode
    }
    exit 0
}

# Docker mode
if ($pullLatest) { & docker pull $dockerImage | Out-Null }

$containerArgs = @('--command',$command,'--input','/mnt/input')
if ($needsOutput) { $containerArgs += @('--output','/mnt/output') }

# Volumes & flags
$volumes = @('-v',"$inputPath`:/mnt/input")
if ($needsOutput) { $volumes += @('-v',"$outputPath`:/mnt/output") }

$flags = @('-e','SPICE_PASS','--rm')
if ($command -eq 'upload-deployment-events') { $flags += '-i' }

Write-Output "🚀 Running spice (Docker mode) with command: $command"
Write-Output "📁 Mounting input:  $inputPath"
if ($needsOutput) { Write-Output "📁 Mounting output: $outputPath" }

$raw = & docker run @flags @volumes $dockerImage @containerArgs @extraArgs 2>&1
$exitCode = $LASTEXITCODE
Invoke-FilterOutput $raw
if ($exitCode -ne 0) {
    Write-Error "❌ The Spice Labs CLI (Docker mode) failed."
    Show-Help
    exit $exitCode
}
