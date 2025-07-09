#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

$jar = if ($env:SPICE_LABS_CLI_JAR) { $env:SPICE_LABS_CLI_JAR } else { "/opt/spice-labs-cli/spice-labs-cli.jar" }

function Get-AbsolutePath($path) {
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

  $jvmArgs = if ($env:SPICE_LABS_JVM_ARGS) { $env:SPICE_LABS_JVM_ARGS } else { "-XX:MaximumHeapSizePercent=75" }
  & java $jvmArgs -jar $jar @args
  exit $LASTEXITCODE
} else {
  $img       = if ($env:SPICE_IMAGE)     { $env:SPICE_IMAGE }     else { "spicelabs/spice-labs-cli" }
  $tag       = if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" }
  $inputDir  = ""
  $outputDir = ""
  $modifiedArgs = @()

  for ($i = 0; $i -lt $args.Count; $i++) {
    switch ($args[$i]) {
      "--input" {
        $inputDir = $args[$i + 1]
        $modifiedArgs += "--input"
        $modifiedArgs += "/mnt/input"
        $i++
        continue
      }
      "--output" {
        $outputDir = $args[$i + 1]
        $modifiedArgs += "--output"
        $modifiedArgs += "/mnt/output"
        $i++
        continue
      }
      default {
        $modifiedArgs += $args[$i]
      }
    }
  }

  $volumes = @()
  if ($inputDir) {
    $absIn = Convert-ToDockerPath (Get-AbsolutePath $inputDir)
    $volumes += "-v"; $volumes += "${absIn}:/mnt/input"
  }
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
