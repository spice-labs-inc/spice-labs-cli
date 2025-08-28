#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

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
    return ($path -replace '^([A-Za-z]):', { "/$($args[0].ToLower())" }) -replace '\\', '/'
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
  $img       = if ($env:SPICE_IMAGE)     { $env:SPICE_IMAGE }     else { "spicelabs/spice-labs-cli" }
  $tag       = if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" }
  $inputDir  = ""
  $outputDir = ""
  $modifiedArgs = @()
  $prev = ""

  foreach ($arg in $args) {
    switch -Regex ($arg) {
      "^--input=(.+)" {
        $inputDir = $matches[1]
        $modifiedArgs += "--input"; $modifiedArgs += "/mnt/input"
        continue
      }
      "^--output=(.+)" {
        $outputDir = $matches[1]
        $modifiedArgs += "--output"; $modifiedArgs += "/mnt/output"
        continue
      }
    }

    if ($prev -eq "--input") {
      $inputDir = $arg
      $modifiedArgs += "/mnt/input"
      $prev = ""
      continue
    }

    if ($prev -eq "--output") {
      $outputDir = $arg
      $modifiedArgs += "/mnt/output"
      $prev = ""
      continue
    }

    if ($arg -eq "--input" -or $arg -eq "--output") {
      $modifiedArgs += $arg
      $prev = $arg
    } else {
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

  if ($env:SPICE_LABS_CLI_SKIP_PULL -ne "1") {
    try {
      docker pull "${img}:${tag}"
    } catch {
      Write-Warning "⚠️  Failed to pull ${img}:${tag}, using local copy if available"
    }
  }

  $envArgs = @()
  if ($env:SPICE_LABS_JVM_ARGS) {
    $envArgs += "-e"
    $envArgs += "SPICE_LABS_JVM_ARGS"
  }

  docker run `
    --rm `
    @volumes `
    -e SPICE_PASS `
    @envArgs `
    "${img}:${tag}" `
    @modifiedArgs
}
