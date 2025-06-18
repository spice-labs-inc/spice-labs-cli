#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

function Convert-PathForDocker {
    param ([string]$path)

    if ($IsWindows) {
        # Convert C:\Path\To\Dir to /c/Path/To/Dir
        $fullPath = Resolve-Path -Path $path | ForEach-Object { $_.Path }
        return $fullPath -replace '^([A-Za-z]):', { "/$($args[0].ToLower())" } -replace '\\', '/'
    } else {
        return (Resolve-Path -Path $path).Path
    }
}

if ($env:SPICE_LABS_CLI_USE_JVM -eq "1") {
    java -jar "/opt/spice-labs-cli/spice-labs-cli.jar" @Args
} else {
    $dockerPath = Convert-PathForDocker (Get-Location)

    docker run --rm `
        -v "$dockerPath:/mnt/input" `
        -v "$dockerPath:/mnt/output" `
        -e SPICE_PASS `
        cli:latest `
        @Args
}
