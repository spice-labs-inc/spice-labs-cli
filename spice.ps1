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
  # Use 'Continue' so that ErrorRecords from docker stderr (via 2>&1)
  # do not terminate the pipeline under $ErrorActionPreference = 'Stop'.
  $ErrorActionPreference = 'Continue'
  & $PSCommandPath @filteredArgs 2>&1 | ForEach-Object {
    $line = "$_"
    Write-Output $line
    Add-Content -Path $logFile -Value ($line -replace '\x1b\[[0-9;]*[a-zA-Z]', '')
  }
  exit $LASTEXITCODE
}

# ── Script update check ─────────────────────────────────────────────────────

$ScriptPath = $MyInvocation.MyCommand.Path
$LocalHash = Get-FileHash -Path $ScriptPath -Algorithm SHA256 | Select-Object -ExpandProperty Hash

$ReleaseInfo = $null
if ($env:SPICE_LABS_CLI_SKIP_PULL -ne "1") {
  try {
    $ReleaseInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/spice-labs-inc/spice-labs-cli/releases/latest" -Headers @{ 'User-Agent' = 'spice-updater' }
  } catch {
    # Silently ignore update check failures (no network, rate limited, etc.)
  }
}
if ($ReleaseInfo) {
  $Asset = $ReleaseInfo.assets | Where-Object { $_.name -eq "spice.ps1" }
  if ($Asset -and $Asset.digest) {
    $RemoteHash = $Asset.digest -replace "sha256:", ""
    if ($LocalHash -ne $RemoteHash) {
      Write-Host "[!] A newer version of this script is available. Run:"
      Write-Host "    irm -UseBasicParsing -Uri https://install.spicelabs.io | iex"
    }
  }
}

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
  Write-Error "[X] Docker is not installed or not in PATH"
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
    Write-Host "[*] Checking for updates to Spice Labs Surveyor CLI..."
    if ($debugMode) { docker pull "${img}:${tag}" }
    else { docker pull --quiet "${img}:${tag}" | Out-Null }
  } catch { Write-Warning "[!] Failed to pull ${img}:${tag}, using local copy if available" }
}

foreach ($arg in $args) {
  if ($arg -eq "-V" -or $arg -eq "--version") {
    Write-Host "Powershell Script hash"
    Write-Host $LocalHash
    break
  }
}

# Trim SPICE_PASS to remove invisible characters (e.g. CRLF, BOM, trailing
# whitespace) that Windows may introduce via the Environment Variables GUI.
$spicePass = if ($env:SPICE_PASS) { $env:SPICE_PASS.Trim() } else { "" }

# --user: match bash wrapper behavior on Linux/macOS. Not needed on Windows
# where Docker Desktop handles file ownership transparently.
$userFlag = @()
if (-not $IsWindows) {
  try {
    $uid = & id -u
    $gid = & id -g
    $userFlag = @("--user", "${uid}:${gid}")
  } catch {}
}

# ── Runtime survey detection (must happen before general arg parsing) ───────
# survey runtime args contain "-- <command...>" which the general parser
# would misinterpret as positional path args. Detect and handle early.

$isRuntimeSurvey = $false
for ($i = 0; $i -lt $args.Count - 1; $i++) {
  if ($args[$i] -eq 'survey' -and $args[$i + 1] -eq 'runtime') {
    $isRuntimeSurvey = $true
    break
  }
}

if ($isRuntimeSurvey) {
  # Parse runtime survey args — split at "--" into CLI flags + user command.
  $rtCliArgs = @()
  $rtUserCmd = @()
  $rtSubject = ""
  $rtOutputPath = ""
  $rtNoUpload = $false
  $rtNativeOnly = $false
  $rtKeepRecording = $false
  $rtPastSep = $false
  $rtPrev = ""
  $rtPos = 0

  foreach ($arg in $args) {
    # Strip --log-file (handled at shell level)
    if ($arg -match '^--log-file=') { continue }
    elseif ($rtPrev -eq '--log-file') { $rtPrev = ""; continue }
    elseif ($arg -eq '--log-file') { $rtPrev = $arg; continue }

    if ($arg -eq '--' -and -not $rtPastSep) {
      $rtPastSep = $true
      continue
    }
    if ($rtPastSep) {
      $rtUserCmd += $arg
      continue
    }

    # Capture --output value
    if ($arg -match '^--output=(.*)$') { $rtOutputPath = $matches[1]; continue }
    elseif ($rtPrev -eq '--output') { $rtOutputPath = $arg; $rtPrev = ""; continue }
    elseif ($arg -eq '--output') { $rtPrev = $arg; continue }

    # Handle value-consuming flags
    if ($rtPrev) {
      $rtCliArgs += $rtPrev; $rtCliArgs += $arg; $rtPrev = ""; continue
    }

    if ($arg -like '-*') {
      if ($arg -eq '--no-upload') { $rtNoUpload = $true }
      if ($arg -eq '--native-only') { $rtNativeOnly = $true }
      if ($arg -eq '--keep-recording') { $rtKeepRecording = $true }
      if ($arg -in $valueFlags) { $rtPrev = $arg }
      else { $rtCliArgs += $arg }
      continue
    }

    # Positional: subcommands pass through, first non-subcommand is subject
    if ($arg -in $subcommands) {
      $rtCliArgs += $arg
    } else {
      $rtPos++
      if ($rtPos -eq 1) {
        $rtSubject = $arg
        $rtCliArgs += $arg
      } else {
        $rtCliArgs += $arg
      }
    }
  }
  if ($rtPrev) { $rtCliArgs += $rtPrev }

  if ($rtUserCmd.Count -eq 0) {
    Write-Host "[X] No command specified after --"
    Write-Host "Usage: spice survey runtime <subject> --jfr -- <command...>"
    exit 1
  }

  if (-not $rtSubject) {
    Write-Host "[X] No subject specified"
    Write-Host "Usage: spice survey runtime <subject> --jfr -- <command...>"
    exit 1
  }

  # Create workdir under the output dir (or default location)
  $rtBase = if ($rtOutputPath) { $rtOutputPath } else { Join-Path (Join-Path $HOME '.spicelabs') 'runtime-survey' }
  if (-not (Test-Path $rtBase)) { New-Item -ItemType Directory -Path $rtBase -Force | Out-Null }
  $rtRandom = -join ((0x30..0x39) + (0x41..0x5A) + (0x61..0x7A) | Get-Random -Count 8 | ForEach-Object { [char]$_ })
  $rtWorkdir = Join-Path $rtBase "survey-$rtRandom"
  New-Item -ItemType Directory -Path $rtWorkdir -Force | Out-Null

  # Convert workdir to Docker-compatible path
  $rtWorkdirDocker = Convert-ToDockerPath (Get-AbsolutePath $rtWorkdir)
  $rtWorkdirHost = (Get-AbsolutePath $rtWorkdir)

  # Phase 1: Extract agent + JFC from container
  Write-Host "Preparing runtime survey..."
  $spiceCliDir = Split-Path $jar -Parent
  $p1Args = @('run', '--rm', '--entrypoint', 'sh')
  $p1Args += @($userFlag)
  $p1Args += @($pullFlag)
  $p1Args += @('-v', "${rtWorkdirHost}:${rtWorkdirDocker}")
  $p1Args += @("${img}:${tag}")
  $p1Args += @('-c', "cp '${spiceCliDir}/ancho.jar' '${rtWorkdirDocker}/' 2>/dev/null; cp '${spiceCliDir}/spice-jfr.jfc' '${rtWorkdirDocker}/' 2>/dev/null")
  & docker @p1Args

  # Phase 2: Build JAVA_TOOL_OPTIONS
  $rtJfc = Join-Path $rtWorkdir 'spice-jfr.jfc'
  if (-not (Test-Path $rtJfc)) {
    Write-Host "[X] Failed to extract JFR settings from container"
    Remove-Item -Recurse -Force $rtWorkdir -ErrorAction SilentlyContinue
    exit 1
  }

  $spiceJto = "-XX:StartFlightRecording=settings=${rtWorkdirHost}/spice-jfr.jfc,dumponexit=true,filename=${rtWorkdirHost}/recording-%p.jfr"
  if ($IsWindows) {
    $spiceJto = "-XX:StartFlightRecording=settings=$rtJfc,dumponexit=true,filename=$(Join-Path $rtWorkdir 'recording-%p.jfr')"
  }

  if (-not $rtNativeOnly -and (Test-Path (Join-Path $rtWorkdir 'ancho.jar'))) {
    Write-Host "Downloading probe configuration..."
    $rtProbes = Join-Path $rtWorkdir 'probes.json'
    $dlArgs = @('run', '--rm', '--entrypoint', 'java')
    $dlArgs += @($userFlag)
    $dlArgs += @('--network', 'host')
    $dlArgs += @($pullFlag)
    $dlArgs += @('-e', "SPICE_PASS=$spicePass")
    $dlArgs += @("${img}:${tag}")
    $dlArgs += @('-cp', $jar, 'io.spicelabs.cli.RuntimeCollect', '--download-probes')
    & docker @dlArgs > $rtProbes 2>$null

    if ((Test-Path $rtProbes) -and (Get-Item $rtProbes).Length -gt 0) {
      $spiceJto = "-javaagent:${rtWorkdirHost}/ancho.jar=${rtProbes} $spiceJto"
    } else {
      Remove-Item $rtProbes -ErrorAction SilentlyContinue
      Write-Host "[!] Could not download probe config. Using native-only mode."
    }
  }

  # Phase 3: Execute target command on the HOST
  Write-Host "Executing: $($rtUserCmd -join ' ')"
  $existingJto = $env:JAVA_TOOL_OPTIONS
  if ($existingJto) {
    $env:JAVA_TOOL_OPTIONS = "$spiceJto $existingJto"
  } else {
    $env:JAVA_TOOL_OPTIONS = $spiceJto
  }

  $ErrorActionPreference = 'Continue'
  & $rtUserCmd[0] @($rtUserCmd | Select-Object -Skip 1)
  $rtTargetRc = $LASTEXITCODE
  $ErrorActionPreference = 'Stop'

  # Restore original JAVA_TOOL_OPTIONS
  if ($existingJto) { $env:JAVA_TOOL_OPTIONS = $existingJto }
  else { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }

  if ($rtTargetRc -ne 0) {
    Write-Host "[!] Target command exited with code $rtTargetRc. Still collecting recordings."
  }

  # Check for recordings
  $rtRecordings = Get-ChildItem -Path $rtWorkdir -Filter '*.jfr' -ErrorAction SilentlyContinue
  if (-not $rtRecordings -or $rtRecordings.Count -eq 0) {
    Write-Host "[X] No JFR recordings found in $rtWorkdir"
    if (-not $rtKeepRecording) { Remove-Item -Recurse -Force $rtWorkdir -ErrorAction SilentlyContinue }
    exit 1
  }

  # Phase 4: Parse + upload in container
  Write-Host "Analyzing recordings..."
  $rtCollectArgs = @($rtSubject, $rtWorkdirDocker)
  if ($rtNoUpload) { $rtCollectArgs += '--no-upload' }

  $p4Args = @('run', '--rm', '--entrypoint', 'java')
  $p4Args += @($userFlag)
  $p4Args += @('--network', 'host')
  $p4Args += @($pullFlag)
  $p4Args += @('-v', "${rtWorkdirHost}:${rtWorkdirDocker}")
  $p4Args += @('-e', "SPICE_PASS=$spicePass")
  $p4Args += @("${img}:${tag}")
  $p4Args += @('-cp', $jar, 'io.spicelabs.cli.RuntimeCollect')
  $p4Args += @($rtCollectArgs)
  & docker @p4Args
  $rtCollectRc = $LASTEXITCODE

  # Clean up
  if (-not $rtKeepRecording) {
    Remove-Item -Recurse -Force $rtWorkdir -ErrorAction SilentlyContinue
  } else {
    Write-Host "Recordings kept in: $rtWorkdir"
  }

  if ($rtTargetRc -ne 0) { exit $rtTargetRc } else { exit $rtCollectRc }
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
} else {
  # Mount default output directory so container output is preserved on the host
  $defaultOutput = Join-Path (Join-Path $HOME '.spicelabs') 'surveyor'
  if (-not (Test-Path $defaultOutput)) { New-Item -ItemType Directory -Path $defaultOutput -Force | Out-Null }
  $hostDir = Convert-ToDockerPath (Get-AbsolutePath $defaultOutput)
  $volumes += "-v"; $volumes += "${hostDir}:/root/.spicelabs/surveyor"
}

# ── Run ──────────────────────────────────────────────────────────────────────

$envArgs = @()
if ($env:SPICE_LABS_JVM_ARGS) { $envArgs += "-e"; $envArgs += "SPICE_LABS_JVM_ARGS" }

$dockerFlags = @()
if ($env:SPICE_DOCKER_FLAGS) { $dockerFlags = $env:SPICE_DOCKER_FLAGS -split '\s+' }

# 'Continue' prevents docker stderr from becoming a terminating error
$ErrorActionPreference = 'Continue'
docker run --rm `
  @userFlag `
  @pullFlag @dockerFlags `
  --network host `
  @volumes `
  -e "SPICE_PASS=$spicePass" `
  @envArgs `
  "${img}:${tag}" `
  @dockerArgs
exit $LASTEXITCODE
