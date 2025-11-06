#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

# Compatibility shim for Windows PowerShell 5.1
if (-not (Test-Path variable:IsWindows)) {
  $IsWindows = $true
}

$ScriptPath = $MyInvocation.MyCommand.Path
$LocalHash = Get-FileHash -Path $ScriptPath -Algorithm SHA256 | Select-Object -ExpandProperty Hash

$ReleaseInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/spice-labs-inc/spice-labs-cli/releases/latest" -Headers @{ 'User-Agent' = 'spice-updater' }

$Asset = $ReleaseInfo.assets | Where-Object { $_.name -eq "spice.ps1" }

if ($Asset -and $Asset.digest) {
    $RemoteHash = $Asset.digest -replace "sha256:", ""

    if ($LocalHash -ne $RemoteHash) {
        Write-Host "⚠️  A newer version of this script is available. Run:"
        Write-Host "    irm -UseBasicParsing -Uri https://install.spicelabs.io | iex"
    }
}

$jar = if ($env:SPICE_LABS_CLI_JAR) { $env:SPICE_LABS_CLI_JAR } else { "/opt/spice-labs-cli/spice-labs-cli.jar" }

function Get-AbsolutePath($path) {
  if ($path -eq "~") {
    $path = $HOME
  } elseif ($path -like "~/*") {
    $path = Join-Path $HOME ($path -replace "^~\/")
  }
  return (Resolve-Path -LiteralPath $path).ProviderPath
}

function Convert-ToDockerPath($path) {
  if ($IsWindows) {
    if ($path -match '^([A-Za-z]):') {
      $driveLetter = $matches[1].ToLower()
      $path = $path -replace '^[A-Za-z]:', "/$driveLetter"
    }
    return $path -replace '\\', '/'
  } else {
    return $path
  }
}

if ($env:SPICE_LABS_CLI_USE_JVM -eq "1") {
  if (-not (Test-Path $jar)) {
    Write-Error "Missing: $jar"
    exit 1
  }

  $jvmArgs = if ($env:SPICE_LABS_JVM_ARGS) { $env:SPICE_LABS_JVM_ARGS } else { "--XX:MaxRAMPercentage=75" }
  & java $jvmArgs -jar $jar @args
  exit $LASTEXITCODE
} else {
  # Check if docker is installed
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "❌ Docker is not installed or not in PATH"
    Write-Host "   Please install Docker: https://docs.docker.com/get-docker/"
    exit 1
  }

  $img       = if ($env:SPICE_IMAGE)     { $env:SPICE_IMAGE }     else { "spicelabs/spice-labs-cli" }
  $tag       = if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" }
  $inputDir  = ""
  $outputDir = ""
  $modifiedArgs = @()
  $prev = ""

  foreach ($arg in $args) {
    if ($arg -match "^--input=(.*)") {
      $inputDir = $matches[1]
      $modifiedArgs += "--input"
      $modifiedArgs += "/mnt/input"
    } elseif ($arg -match "^--output=(.*)") {
      $outputDir = $matches[1]
      $modifiedArgs += "--output"
      $modifiedArgs += "/mnt/output"
    } elseif ($prev -eq "--input") {
      $inputDir = $arg
      $modifiedArgs += "/mnt/input"
      $prev = ""
    } elseif ($prev -eq "--output") {
      $outputDir = $arg
      $modifiedArgs += "/mnt/output"
      $prev = ""
    } elseif ($arg -eq "--input" -or $arg -eq "--output") {
      $modifiedArgs += $arg
      $prev = $arg
    } else {
      if ($arg -eq "-V") {
        Write-Host "Powershell Script hash"
        Write-Host $LocalHash
      }
      $modifiedArgs += $arg
      $prev = ""
    }
  }

  if (-not $inputDir) {
    $inputDir = "."
    $modifiedArgs += "--input"; $modifiedArgs += "/mnt/input"
  }

  $volumes = @()
  $absIn = Convert-ToDockerPath (Get-AbsolutePath $inputDir)
  $volumes += "-v"; $volumes += "${absIn}:/mnt/input"

  if ($outputDir) {
    $absOut = Convert-ToDockerPath (Get-AbsolutePath $outputDir)
    $volumes += "-v"; $volumes += "${absOut}:/mnt/output"
  }

  # Check if we're in debug/trace mode
  $debugMode = $false
  foreach ($arg in $args) {
    if ($arg -match '^--log-level=(debug|trace|all)$' -or $arg -match '^--log-level=(DEBUG|TRACE|ALL)$') {
      $debugMode = $true
      break
    }
  }

  if ($env:SPICE_LABS_CLI_SKIP_PULL -ne "1") {
    try {
      if ($debugMode) {
        Write-Host "📦 Checking for updates to Spice Labs Surveyor CLI..."
        docker pull "${img}:${tag}"
      } else {
        Write-Host "📦 Checking for updates to Spice Labs Surveyor CLI..."
        docker pull --quiet "${img}:${tag}" | Out-Null
      }
    } catch {
      Write-Warning "⚠️  Failed to pull ${img}:${tag}, using local copy if available"
    }
  }

  $envArgs = @()
  if ($env:SPICE_LABS_JVM_ARGS) {
    $envArgs += "-e"
    $envArgs += "SPICE_LABS_JVM_ARGS"
  }

  # Use --pull=never if skip pull is set, otherwise use default pull behavior
  $pullFlag = @()
  if ($env:SPICE_LABS_CLI_SKIP_PULL -eq "1") {
    $pullFlag += "--pull=never"
  }

  $dockerFlags = @()
  if ($env:SPICE_DOCKER_FLAGS) {
    $dockerFlags = $env:SPICE_DOCKER_FLAGS -split '\s+'
  }

  docker run `
    --rm `
    @pullFlag `
    @dockerFlags `
    @volumes `
    -e SPICE_PASS `
    @envArgs `
    "${img}:${tag}" `
    @modifiedArgs
}
