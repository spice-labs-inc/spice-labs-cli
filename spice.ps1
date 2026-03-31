#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

if (-not (Test-Path variable:IsWindows)) { $IsWindows = $true }

# ── Log file tee (must be first) ─────────────────────────────────────────────

$logFile = ""
for ($i = 0; $i -lt $args.Count; $i++) {
  if ($args[$i] -match "^--log-file=(.*)") { $logFile = $matches[1]; break }
  elseif ($args[$i] -eq "--log-file" -and $i + 1 -lt $args.Count) { $logFile = $args[$i + 1]; break }
}

if ($logFile -and -not $env:__SPICE_LOGGING_ACTIVE) {
  $env:__SPICE_LOGGING_ACTIVE = "1"
  $filteredArgs = @(); $prev = ""
  foreach ($arg in $args) {
    if ($arg -match "^--log-file=") { continue }
    elseif ($prev -eq "--log-file") { $prev = ""; continue }
    elseif ($arg -eq "--log-file") { $prev = $arg; continue }
    else { $filteredArgs += $arg; $prev = "" }
  }
  & $PSCommandPath @filteredArgs 2>&1 | ForEach-Object {
    $line = "$_"; Write-Output $line
    Add-Content -Path $logFile -Value ($line -replace '\x1b\[[0-9;]*[a-zA-Z]', '')
  }
  exit $LASTEXITCODE
}

# ── Script update check ─────────────────────────────────────────────────────

$ScriptPath = $MyInvocation.MyCommand.Path
$LocalHash = Get-FileHash -Path $ScriptPath -Algorithm SHA256 | Select-Object -ExpandProperty Hash

try {
  $ReleaseInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/spice-labs-inc/spice-labs-cli/releases/latest" -Headers @{ 'User-Agent' = 'spice-updater' }
  $Asset = $ReleaseInfo.assets | Where-Object { $_.name -eq "spice.ps1" }
  if ($Asset -and $Asset.digest) {
    $RemoteHash = $Asset.digest -replace "sha256:", ""
    if ($LocalHash -ne $RemoteHash) {
      Write-Host "⚠️  A newer version of this script is available. Run:"
      Write-Host "    irm -UseBasicParsing -Uri https://install.spicelabs.io | iex"
    }
  }
} catch {}

# ── Helpers ──────────────────────────────────────────────────────────────────

function Get-AbsolutePath($path) {
  if ($path -eq "~") { $path = $HOME }
  elseif ($path -like "~/*" -or $path -like "~\*") { $path = Join-Path $HOME ($path -replace "^~[/\\]") }
  return (Resolve-Path -LiteralPath $path).ProviderPath
}

function Convert-ToDockerPath($path) {
  if ($IsWindows) {
    if ($path -match '^([A-Za-z]):') { $path = $path -replace '^[A-Za-z]:', "/$($matches[1].ToLower())" }
    return $path -replace '\\', '/'
  }
  return $path
}

$subcommands = @('survey', 'inventory', 'static', 'runtime', 'pass', 'decode')

$valueFlags = @('--output', '--threads', '--max-records', '--chunk-size',
  '--log-level', '--log-file', '--tag-json', '--goat-rodeo-args', '--ginger-args')

$jar = if ($env:SPICE_LABS_CLI_JAR) { $env:SPICE_LABS_CLI_JAR } else { "/opt/spice-labs-cli/spice-labs-cli.jar" }
$img = if ($env:SPICE_IMAGE) { $env:SPICE_IMAGE } else { "spicelabs/spice-labs-cli" }
$tag = if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" }

# ── JVM mode (no Docker, no path rewriting) ──────────────────────────────────

if ($env:SPICE_LABS_CLI_USE_JVM -eq "1") {
  if (-not (Test-Path $jar)) { Write-Error "Missing: $jar"; exit 1 }
  $jvmArgs = if ($env:SPICE_LABS_JVM_ARGS) { $env:SPICE_LABS_JVM_ARGS } else { "--XX:MaxRAMPercentage=75" }
  $filtered = @(); $prev = ""
  foreach ($arg in $args) {
    if ($arg -match "^--log-file=") { continue }
    elseif ($prev -eq "--log-file") { $prev = ""; continue }
    elseif ($arg -eq "--log-file") { $prev = $arg; continue }
    else { $filtered += $arg; $prev = "" }
  }
  & java $jvmArgs -jar $jar @filtered
  exit $LASTEXITCODE
}

# ── Docker checks ────────────────────────────────────────────────────────────

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Error "❌ Docker is not installed or not in PATH"
  Write-Host "   Please install Docker: https://docs.docker.com/get-docker/"
  exit 1
}

$debugMode = $false
foreach ($arg in $args) {
  if ($arg -match '(?i)^--log-level=(debug|trace|all)$') { $debugMode = $true; break }
}

$pullFlag = @()
if ($env:SPICE_LABS_CLI_SKIP_PULL -eq "1") {
  $pullFlag += "--pull=never"
} else {
  try {
    Write-Host "📦 Checking for updates to Spice Labs Surveyor CLI..."
    if ($debugMode) { docker pull "${img}:${tag}" }
    else { docker pull --quiet "${img}:${tag}" | Out-Null }
  } catch { Write-Warning "⚠️  Failed to pull ${img}:${tag}, using local copy if available" }
}

foreach ($arg in $args) {
  if ($arg -eq "-V" -or $arg -eq "--version") {
    Write-Host "Powershell Script hash"
    Write-Host $LocalHash
    break
  }
}

# ── Find paths in args and build docker command ──────────────────────────────

$inputPath = ""
$outputPath = ""
$positionalIndex = 0
$dockerArgs = @()
$volumes = @()

$prev = ""
foreach ($arg in $args) {
  # Strip --log-file
  if ($arg -match "^--log-file=") { continue }
  elseif ($prev -eq "--log-file") { $prev = ""; continue }
  elseif ($arg -eq "--log-file") { $prev = $arg; continue }

  # Capture --output value
  if ($arg -match "^--output=(.*)$") { $outputPath = $matches[1]; $prev = ""; continue }
  elseif ($prev -eq "--output") { $outputPath = $arg; $prev = ""; continue }
  elseif ($arg -eq "--output") { $prev = $arg; continue }

  # Handle other value-consuming flags
  if ($prev) {
    $dockerArgs += $prev; $dockerArgs += $arg; $prev = ""; continue
  }

  if ($arg -like "-*") {
    if ($arg -in $valueFlags) { $prev = $arg }
    else { $dockerArgs += $arg }
    continue
  }

  # It's a positional arg
  if ($arg -in $subcommands) {
    $dockerArgs += $arg
  } else {
    $positionalIndex++
    if ($positionalIndex -eq 1) {
      # Subject — pass through
      $dockerArgs += $arg
    } elseif ($positionalIndex -eq 2) {
      # Input path — capture for volume mount
      $inputPath = $arg
    } else {
      $dockerArgs += $arg
    }
  }
}
if ($prev) { $dockerArgs += $prev }

# ── Resolve input path ───────────────────────────────────────────────────────

if ($inputPath) {
  if (Test-Path -LiteralPath $inputPath -PathType Leaf) {
    $hostDir = Convert-ToDockerPath (Get-AbsolutePath (Split-Path -Parent $inputPath))
    $fileName = Split-Path -Leaf $inputPath
    $volumes += "-v"; $volumes += "${hostDir}:/mnt/input"
    $dockerArgs += "/mnt/input/${fileName}"
  } else {
    $hostDir = Convert-ToDockerPath (Get-AbsolutePath $inputPath)
    $volumes += "-v"; $volumes += "${hostDir}:/mnt/input"
    $dockerArgs += "/mnt/input"
  }
}

# ── Resolve output path ─────────────────────────────────────────────────────

if ($outputPath) {
  if (-not (Test-Path $outputPath)) { New-Item -ItemType Directory -Path $outputPath -Force | Out-Null }
  $hostDir = Convert-ToDockerPath (Get-AbsolutePath $outputPath)
  $volumes += "-v"; $volumes += "${hostDir}:/mnt/output"
  $dockerArgs += "--output"; $dockerArgs += "/mnt/output"
}

# ── Run ──────────────────────────────────────────────────────────────────────

# Trim SPICE_PASS to remove invisible characters (e.g. CRLF, BOM, trailing
# whitespace) that Windows may introduce via the Environment Variables GUI.
$spicePass = if ($env:SPICE_PASS) { $env:SPICE_PASS.Trim() } else { "" }

$envArgs = @()
if ($env:SPICE_LABS_JVM_ARGS) { $envArgs += "-e"; $envArgs += "SPICE_LABS_JVM_ARGS" }

$dockerFlags = @()
if ($env:SPICE_DOCKER_FLAGS) { $dockerFlags = $env:SPICE_DOCKER_FLAGS -split '\s+' }

docker run --rm `
  @pullFlag @dockerFlags `
  --network host `
  @volumes `
  -e "SPICE_PASS=$spicePass" `
  @envArgs `
  "${img}:${tag}" `
  @dockerArgs
