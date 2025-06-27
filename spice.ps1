#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

$jar = $env:SPICE_LABS_CLI_JAR ?: "/opt/spice-labs-cli/spice-labs-cli.jar"

function Pwd-Docker {
  $p = (Resolve-Path .).Path
  if ($IsWindows) { $p -replace '^([A-Za-z]):', { "/$($args[0].ToLower())" } -replace '\\', '/' } else { $p }
}

if ($env:SPICE_LABS_CLI_USE_JVM -eq "1") {
  if (-not (Test-Path $jar)) { Write-Error "Missing: $jar"; exit 1 }
  java -jar $jar @args
} else {
  $img = $env:SPICE_IMAGE ?: "spicelabs/spice-labs-cli"
  $tag = $env:SPICE_IMAGE_TAG ?: "latest"
  $pwd = Pwd-Docker
  docker run --rm `
    -v "$pwd:/mnt/input" `
    -v "$pwd:/mnt/output" `
    -e SPICE_PASS `
    "$img`:$tag" `
    @args
}
