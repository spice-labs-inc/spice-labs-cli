#!/usr/bin/env pwsh
#Requires -Version 5.1

$ErrorActionPreference = "Stop"

$DOCKER_IMAGE = "circlejtp/spice-labs-cli:latest"
$ci_mode = $false
$pull_latest = $true

# Defaults
$command = "run"
$inputPath = ""
$outputPath = ""
$extra_args = @()

function Show-Help {
@"
Usage: spice-labs-cli.ps1 --command <cmd> [--input <path>] [--output <path>] [--ci] [--quiet|--verbose]

Commands:
  run                     Scan artifacts and upload ADGs (default)
  scan-artifacts          Generate ADGs only
  upload-adgs             Upload existing ADGs
  upload-deployment-events Upload deployment events from stdin

Options:
  --command CMD           One of: run, scan-artifacts, upload-adgs, upload-deployment-events
  --input PATH            Path to input directory or file
  --output PATH           Path for output (only needed for scan-artifacts)
  --ci                    Run in CI/CD mode (non-interactive, implies --quiet unless overridden)
  --quiet                 Suppress output
  --verbose               Enable detailed logging
  --no-pull               Don't pull the latest Docker image
  --help                  Show this help
"@
}

# Argument parsing
$i = 0
while ($i -lt $args.Count) {
    switch ($args[$i]) {
        '--command' {
            $command = $args[$i + 1]
            $i += 2
            continue
        }
        '--input' {
            $inputPath = $args[$i + 1]
            $i += 2
            continue
        }
        '--output' {
            $outputPath = $args[$i + 1]
            $i += 2
            continue
        }
        '--ci' {
            $ci_mode = $true
            $i++
            continue
        }
        '--no-pull' {
            $pull_latest = $false
            $i++
            continue
        }
        '--quiet' { $extra_args += '--quiet'; $i++; continue }
        '--verbose' { $extra_args += '--verbose'; $i++; continue }
        '--help' {
            Show-Help
            exit 0
        }
        default {
            $extra_args += $args[$i]
            $i++
        }
    }
}

# Validate SPICE_PASS for commands that require it
switch ($command) {
    'scan-artifacts' { } # skip check
    default {
        if (-not $env:SPICE_PASS) {
            Write-Host "SPICE_PASS environment variable must be set for command '$command'" -ForegroundColor Red
            exit 1
        }
    }
}

# Pull image unless skipped
if ($pull_latest) {
    docker pull $DOCKER_IMAGE | Out-Null
}

# Prepare args
$docker_args = @('--command', $command)

# Default to current dir as input if not set
if (-not $inputPath) { $inputPath = (Get-Location).Path }
$docker_args += @('--input', '/mnt/input')

# Ensure output directory exists for commands that write to it
if ($command -eq "scan-artifacts" -or $command -eq "run") {
    if (-not $outputPath) {
        $outputPath = New-TemporaryFile | Split-Path
    } else {
        if (-not (Test-Path $outputPath)) {
            New-Item -ItemType Directory -Path $outputPath | Out-Null
        }
    }
   if (-not (Test-Path $outputPath -PathType Container)) 
   {
       $test_tmp_filename = "writetest-"+[guid]::NewGuid()
	$test_filename = (Join-Path $test_folder $test_tmp_filename)
	
	Try { 
		# Try to add a new file
		[io.file]::OpenWrite($test_filename).close()
		Write-Host -ForegroundColor Green "[+] Writable:" $test_folder
		
		# Remove test file
		Remove-Item -ErrorAction SilentlyContinue $test_filename
		
		if (Test-Path $test_filename and $verbose) { 
			Write-Host -ForegroundColor Yellow "[*] Failed to delete test file: " $test_filename
		}
	}
	Catch {
		# Report error?
		if ($verbose) { 
			Write-Host -ForegroundColor Red "[-] Not writable: " $test_folder
		}
        exit 1
	}
      
   }
    $outputPath = Convert-Path $outputPath
    $docker_args += @('--output', '/mnt/output')
}

# Default to --quiet in CI unless overridden
if ($ci_mode -and -not ($extra_args -contains '--verbose') -and -not ($extra_args -contains '--quiet')) {
    $docker_args += '--quiet'
}

# Prepare Docker flags
$volumes = @("-v", "$($inputPath):/mnt/input")
if ($outputPath) { $volumes += @("-v", "$($outputPath):/mnt/output") }

$flags = @("-e", "SPICE_PASS", "--rm")
if ($command -eq "upload-deployment-events") { $flags += "-i" }

Write-Host " Running spice-labs-cli with command: $command" 
Write-Host " Mounting input:  $inputPath" 
if ($outputPath) { Write-Host " Mounting output: ($outputPath)" }

# Run and filter output
try {
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = "docker"
    #Write-Host (@("run") + $flags + $volumes + @($DOCKER_IMAGE) + $docker_args + $extra_args)
    #$cmdline = @("run") + $flags + $volumes + @($DOCKER_IMAGE) + $docker_args + $extra_args
    $processInfo.Arguments = @(" run") + $flags + $volumes + @($DOCKER_IMAGE) + $docker_args + $extra_args
   # Start-Process -FilePath "docker" -ArgumentList $cmdline
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.UseShellExecute = $false
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo
    Write-Host("Starting CLI Process Please Stand By....")
    $process.Start() | Out-Null

    # Filter output: replace 'spicelabs.sh' with 'spice-labs-cli.sh' and remove help block
    $outputLines = @()
    $helpBlock = $false
    while (($line = $process.StandardOutput.ReadLine()) -ne $null) {
        if ($line -match "::spice-labs-cli-help-start::") { $helpBlock = $true; continue }
        if ($line -match "::spice-labs-cli-help-end::") { $helpBlock = $false; continue }
        if ($helpBlock) { continue }
        $outputLines += ($line -replace '\bspicelabs\.sh\b', 'spice-labs-cli.sh')
    }
    $outputLines | ForEach-Object { Write-Host $_ }

    $stderr = $process.StandardError.ReadToEnd()
    if ($stderr) { Write-Host $stderr -ForegroundColor Yellow }

    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Spice Labs CLI failed."
    }
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    Write-Host "   "
    Write-Host "The Spice Labs CLI failed" 
    Show-Help
    exit 1
}
