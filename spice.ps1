#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

$jar = if ($env:SPICE_LABS_CLI_JAR) { $env:SPICE_LABS_CLI_JAR } else { "/opt/spice-labs-cli/spice-labs-cli.jar" }

function Get-DockerPath {
  $p = (Resolve-Path .).Path
  if ($IsWindows) {
    $p -replace '^([A-Za-z]):', { "/$($args[0].ToLower())" } -replace '\\', '/'
  } else {
    $p
  }
}

if ($env:SPICE_LABS_CLI_USE_JVM -eq "1") {
  if (-not (Test-Path $jar)) {
    Write-Error "Missing: $jar"
    exit 1
  }
  java -jar $jar @args
} else {
  $img = if ($env:SPICE_IMAGE) { $env:SPICE_IMAGE } else { "spicelabs/spice-labs-cli" }
  $tag = if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" }
  $dockerPath = Get-DockerPath

  if ($env:SPICE_LABS_CLI_SKIP_PULL -ne "1") {
    try {
      docker pull "${img}:${tag}"
    } catch {
      Write-Warning "⚠️  Failed to pull ${img}:${tag}, using local copy if available"
    }
  }

  docker run --rm `
    -v "${dockerPath}:/mnt/input" `
    -v "${dockerPath}:/mnt/output" `
    -e SPICE_PASS `
    "${img}:${tag}" `
    @args
}
