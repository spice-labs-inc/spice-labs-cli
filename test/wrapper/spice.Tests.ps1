#Requires -Module Pester
<#
  Pester 5 tests for the PowerShell wrapper script (spice.ps1).
  Uses a mock docker script to capture args — no real Docker required.

  Run (PS7):   Invoke-Pester ./test/wrapper/spice.Tests.ps1 -Output Detailed
  Run (PS5):   powershell.exe -Command "Import-Module Pester; Invoke-Pester ./test/wrapper/spice.Tests.ps1 -Output Detailed"
#>

BeforeAll {
  # $PSScriptRoot may be empty in PS5 when invoked via -Command; use $MyInvocation fallback
  $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
  $script:RepoRoot = (Resolve-Path (Join-Path (Join-Path $scriptDir '..') '..')).Path
  $script:WrapperScript = Join-Path $script:RepoRoot 'spice.ps1'
  $script:MockBinDir = Join-Path ([System.IO.Path]::GetTempPath()) "spice-mock-bin-$([guid]::NewGuid().ToString('N').Substring(0,8))"
  New-Item -ItemType Directory -Path $script:MockBinDir -Force | Out-Null

  # Create mock docker script that captures args and produces structured output.
  $script:DockerArgsFile = Join-Path $script:MockBinDir 'docker-args.txt'

  if ($IsWindows -or -not (Test-Path variable:IsWindows)) {
    # Windows: compile a tiny C# mock docker.exe.
    # Batch files can't preserve = in args. A real .exe receives args intact.
    $mockExe = Join-Path $script:MockBinDir 'docker.exe'
    $mockCs = @'
using System;
using System.IO;
using System.Collections.Generic;
class MockDocker {
  static int Main(string[] args) {
    if (args.Length > 0 && args[0] == "pull") return 0;
    // Detect runtime survey calls by --entrypoint
    string entrypoint = null, volHost = null;
    for (int j = 0; j < args.Length - 1; j++) {
      if (args[j] == "--entrypoint") entrypoint = args[j+1];
      if (args[j] == "-v" && args[j+1].Contains(":")) {
        // Handle Windows drive letters (e.g. C:\path:C:\path)
        var vol = args[j+1];
        int sep = vol.IndexOf(':', vol.Length > 2 && vol[1] == ':' ? 2 : 0);
        volHost = sep > 0 ? vol.Substring(0, sep) : vol;
      }
    }
    // Phase 1: extraction
    if (entrypoint == "sh" && volHost != null && Directory.Exists(volHost)) {
      File.WriteAllText(Path.Combine(volHost, "ancho.jar"), "mock");
      File.WriteAllText(Path.Combine(volHost, "spice-jfr.jfc"), "mock");
      Console.WriteLine("done");
      return 0;
    }
    // Phase 4: RuntimeCollect
    if (entrypoint == "java") return 0;
    // Write all args to capture file
    var af = Environment.GetEnvironmentVariable("DOCKER_ARGS_FILE");
    if (!string.IsNullOrEmpty(af)) File.WriteAllLines(af, args);
    // Find image arg, everything after is CLI args
    bool found = false;
    var cli = new List<string>();
    var env = new Dictionary<string,string>();
    string prev = "";
    int exitCode = 0;
    foreach (var a in args) {
      if (found) { cli.Add(a); }
      else {
        if (prev == "-e") {
          int eq = a.IndexOf('=');
          if (eq > 0) env[a.Substring(0,eq)] = a.Substring(eq+1);
          prev = ""; continue;
        }
        if (a == "-e") { prev = a; continue; }
        prev = "";
        if (a.StartsWith("spice-")) { found = true; continue; }
      }
    }
    Console.WriteLine("===SPICE_TEST_BEGIN===");
    foreach (var c in cli) Console.WriteLine("ARG:" + c);
    string sp; env.TryGetValue("SPICE_PASS", out sp);
    string jv; env.TryGetValue("SPICE_LABS_JVM_ARGS", out jv);
    Console.WriteLine("ENV:SPICE_PASS=" + (sp ?? ""));
    Console.WriteLine("ENV:SPICE_LABS_JVM_ARGS=" + (jv ?? ""));
    Console.WriteLine("COLORED:green-text");
    Console.WriteLine("COLORED:red-text");
    Console.Error.WriteLine("STDERR:test-error-output");
    Console.WriteLine("===SPICE_TEST_END===");
    // Check for TEST_EXIT_CODE
    string tc; if (env.TryGetValue("TEST_EXIT_CODE", out tc)) int.TryParse(tc, out exitCode);
    return exitCode;
  }
}
'@
    # Use csc.exe directly — Add-Type -OutputType ConsoleApplication doesn't work in PS7.
    # .NET Framework csc.exe is always available on Windows.
    $mockCsFile = Join-Path $script:MockBinDir 'MockDocker.cs'
    Set-Content -Path $mockCsFile -Value $mockCs
    $csc = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path $csc)) { $csc = Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
    & $csc /nologo /out:$mockExe /target:exe $mockCsFile 2>&1 | Out-Null
    if (-not (Test-Path $mockExe)) { throw "Failed to compile mock docker.exe" }

    # Compile mock java.exe for JVM mode tests
    $script:JavaArgsFile = Join-Path $script:MockBinDir 'java-args.txt'
    $mockJavaExe = Join-Path $script:MockBinDir 'java.exe'
    $mockJavaCs = @'
using System;
using System.IO;
class MockJava {
  static int Main(string[] args) {
    var af = Environment.GetEnvironmentVariable("JAVA_ARGS_FILE");
    if (!string.IsNullOrEmpty(af)) File.WriteAllLines(af, args);
    Console.WriteLine("===SPICE_TEST_BEGIN===");
    foreach (var a in args) Console.WriteLine("ARG:" + a);
    Console.WriteLine("===SPICE_TEST_END===");
    return 0;
  }
}
'@
    $mockJavaCsFile = Join-Path $script:MockBinDir 'MockJava.cs'
    Set-Content -Path $mockJavaCsFile -Value $mockJavaCs
    & $csc /nologo /out:$mockJavaExe /target:exe $mockJavaCsFile 2>&1 | Out-Null
    if (-not (Test-Path $mockJavaExe)) { throw "Failed to compile mock java.exe" }

    $mockDocker = Join-Path $script:MockBinDir 'docker-mock.ps1'  # unused on Windows, kept for compat
    Set-Content -Path $mockDocker -Value @'
# Mock docker - reads args from MOCK_DOCKER_ARGS env var (set by docker.cmd shim)
# to avoid powershell.exe interpreting -e and other flags.
$raw = $env:MOCK_DOCKER_ARGS
if (-not $raw) { exit 0 }

# Parse: split on spaces, respecting double-quoted segments
$allArgs = @()
$buf = ''
$inQ = $false
for ($i = 0; $i -lt $raw.Length; $i++) {
  $c = $raw[$i]
  if ($c -eq '"') { $inQ = -not $inQ; continue }
  if ($c -eq ' ' -and -not $inQ) {
    if ($buf.Length -gt 0) { $allArgs += $buf; $buf = '' }
    continue
  }
  $buf += $c
}
if ($buf.Length -gt 0) { $allArgs += $buf }

if ($allArgs.Count -gt 0 -and $allArgs[0] -eq 'pull') { exit 0 }

# Write all args to capture file
$argsFile = $env:DOCKER_ARGS_FILE
if ($argsFile) { ($allArgs | ForEach-Object { "$_" }) -join "`n" | Set-Content -Path $argsFile }

# Find the image arg - everything after it is CLI args
$cliArgs = @()
$foundImage = $false
$envVars = @{}
$prevFlag = ''
foreach ($a in $allArgs) {
  if ($foundImage) { $cliArgs += $a; continue }
  if ($prevFlag -eq '-e') {
    if ($a -match '^([^=]+)=(.*)$') { $envVars[$Matches[1]] = $Matches[2] }
    $prevFlag = ''
    continue
  }
  if ($a -eq '-e') { $prevFlag = '-e'; continue }
  $prevFlag = ''
  if ($a -match '^spice-') { $foundImage = $true; continue }
  if ($a -match '^[a-z]' -and $a -notmatch '^--' -and $a -ne 'run' -and $a -ne 'host' -and $a -ne 'never' -and ($a -match ':' -or $a -match '/')) {
    $foundImage = $true; continue
  }
}

Write-Output '===SPICE_TEST_BEGIN==='
foreach ($ca in $cliArgs) { Write-Output "ARG:$ca" }
Write-Output "ENV:SPICE_PASS=$($envVars['SPICE_PASS'])"
Write-Output "ENV:SPICE_LABS_JVM_ARGS=$($envVars['SPICE_LABS_JVM_ARGS'])"
Write-Output 'COLORED:green-text'
Write-Output 'COLORED:red-text'
[Console]::Error.WriteLine('STDERR:test-error-output')
Write-Output '===SPICE_TEST_END==='

# Check for TEST_EXIT_CODE in -e args
$exitCode = 0
$pf = ''
foreach ($a in $allArgs) {
  if ($pf -eq '-e' -and $a -match '^TEST_EXIT_CODE=(\d+)$') { $exitCode = [int]$Matches[1] }
  $pf = $a
}
exit $exitCode
'@
  } else {
    # Non-Windows: shell script mock
    $mockDockerPs1 = Join-Path $script:MockBinDir 'docker-mock.ps1'
    $mockDockerSh = Join-Path $script:MockBinDir 'docker'
    # The PS1 mock is the same as above (reused)
    Set-Content -Path $mockDockerPs1 -Value @'
$rawArgs = $env:MOCK_DOCKER_RAW_ARGS
if (-not $rawArgs) {
  $allArgs = $args
} else {
  $allArgs = $rawArgs -split ' '
}
if ($allArgs[0] -eq 'pull') { exit 0 }
if ($env:DOCKER_ARGS_FILE) { $allArgs -join "`n" | Set-Content -Path $env:DOCKER_ARGS_FILE }
$cliArgs = @(); $foundImage = $false; $envVars = @{}; $prevFlag = ''
foreach ($a in $allArgs) {
  if ($foundImage) { $cliArgs += $a; continue }
  if ($prevFlag -eq '-e') { if ($a -match '^([^=]+)=(.*)$') { $envVars[$Matches[1]] = $Matches[2] }; $prevFlag = ''; continue }
  if ($a -eq '-e') { $prevFlag = '-e'; continue }
  $prevFlag = ''
  if ($a -match '^[a-z]' -and $a -notmatch '^--' -and $a -notmatch '^host$' -and $a -match '(:|/)') { $foundImage = $true; continue }
  if ($a -match '^spice-') { $foundImage = $true; continue }
}
Write-Output '===SPICE_TEST_BEGIN==='
foreach ($ca in $cliArgs) { Write-Output "ARG:$ca" }
Write-Output "ENV:SPICE_PASS=$($envVars['SPICE_PASS'])"
Write-Output "ENV:SPICE_LABS_JVM_ARGS=$($envVars['SPICE_LABS_JVM_ARGS'])"
Write-Output "$([char]27)[32mCOLORED:green-text$([char]27)[0m"
Write-Output "$([char]27)[31mCOLORED:red-text$([char]27)[0m"
[Console]::Error.WriteLine('STDERR:test-error-output')
Write-Output '===SPICE_TEST_END==='
exit 0
'@
    # Shell mock handles runtime survey entrypoint calls directly (no pwsh needed)
    # and falls back to the pwsh mock for normal docker run calls.
    Set-Content -Path $mockDockerSh -Value @"
#!/bin/bash
if [ "`\`$1" = 'pull' ]; then exit 0; fi

# Detect runtime survey Docker calls by --entrypoint
_entrypoint=""
_vol_host=""
_prev=""
for _arg in "`\`$@"; do
  if [ "`\`$_prev" = "--entrypoint" ]; then _entrypoint="`\`$_arg"; fi
  if [ "`\`$_prev" = "-v" ]; then _vol_host="`\`${_arg%%:*}"; fi
  _prev="`\`$_arg"
done

# Phase 1: extraction (--entrypoint sh) — create mock files in workdir
if [ "`\`$_entrypoint" = "sh" ] && [ -n "`\`$_vol_host" ] && [ -d "`\`$_vol_host" ]; then
  echo "mock" > "`\`$_vol_host/ancho.jar"
  echo "mock" > "`\`$_vol_host/spice-jfr.jfc"
  echo done
  exit 0
fi

# Phase 4: RuntimeCollect (--entrypoint java) — just succeed
if [ "`\`$_entrypoint" = "java" ]; then
  exit 0
fi

pwsh -NoProfile -File "$mockDockerPs1" "`\`$@"
"@
    chmod +x $mockDockerSh 2>`$null
  }

  # ── Helper: run the wrapper with mock docker and parse output ────────────
  function Invoke-SpiceWrapper {
    [CmdletBinding()]
    param(
      [Parameter(Mandatory)]
      [string[]]$Arguments,
      [string]$SpicePass = 'test-pass-value',
      [string]$DockerFlags
    )

    # Put mock docker first on PATH
    $env:PATH = "$($script:MockBinDir)$([System.IO.Path]::PathSeparator)$($env:PATH)"

    $env:SPICE_LABS_CLI_SKIP_PULL = '1'
    $env:SPICE_IMAGE = 'spice-wrapper-test'
    $env:SPICE_IMAGE_TAG = 'latest'
    $env:SPICE_PASS = $SpicePass
    $env:DOCKER_ARGS_FILE = $script:DockerArgsFile
    Remove-Item env:__SPICE_LOGGING_ACTIVE -ErrorAction SilentlyContinue
    if ($DockerFlags) { $env:SPICE_DOCKER_FLAGS = $DockerFlags }
    else { Remove-Item env:SPICE_DOCKER_FLAGS -ErrorAction SilentlyContinue }

    $rawLines = @()
    $exitCode = 0

    # Use 'Continue' to prevent ErrorRecords from stderr (via 2>&1) from
    # throwing under Pester's $ErrorActionPreference = 'Stop'.
    $savedEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      # Reset LASTEXITCODE by running a trivial native command that exits 0
      if ($IsWindows -or -not (Test-Path variable:IsWindows)) { cmd /c "exit /b 0" } else { true }
      $rawLines = @(& $script:WrapperScript @Arguments 2>&1 | ForEach-Object { "$_" })
      $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }

    } catch {
      $rawLines = @("EXCEPTION:$($_.Exception.Message)")
      $exitCode = 1

    } finally {
      $ErrorActionPreference = $savedEAP
    }

    # Parse structured output between markers
    $containerArgs = @()
    $containerEnv = @{}
    $inBlock = $false

    foreach ($line in $rawLines) {
      if ($line -eq '===SPICE_TEST_BEGIN===') { $inBlock = $true; continue }
      if ($line -eq '===SPICE_TEST_END===') { $inBlock = $false; continue }
      if (-not $inBlock) { continue }
      if ($line -match '^ARG:(.*)$') { $containerArgs += $Matches[1] }
      elseif ($line -match '^ENV:([^=]+)=(.*)$') { $containerEnv[$Matches[1]] = $Matches[2] }
    }

    # Also parse the raw docker args file for volume/flag verification
    $dockerRunArgs = @()
    if (Test-Path $script:DockerArgsFile) {
      $dockerRunArgs = @(Get-Content $script:DockerArgsFile)
      Remove-Item $script:DockerArgsFile -ErrorAction SilentlyContinue
    }

    [PSCustomObject]@{
      RawOutput     = $rawLines
      ExitCode      = [int]$exitCode
      ContainerArgs = $containerArgs
      ContainerEnv  = $containerEnv
      DockerRunArgs = $dockerRunArgs
    }
  }
}

AfterAll {
  if (Test-Path $script:MockBinDir) {
    Remove-Item $script:MockBinDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}

# ═════════════════════════════════════════════════════════════════════════════

Describe 'spice.ps1 wrapper' {

  BeforeEach {
    $script:TestDir = Join-Path ([System.IO.Path]::GetTempPath()) "spice-test-$([guid]::NewGuid().ToString('N').Substring(0,8))"
    New-Item -ItemType Directory -Path $script:TestDir -Force | Out-Null
    # Create a default input dir with a file for tests that need it
    $script:InputDir = Join-Path $script:TestDir 'input'
    New-Item -ItemType Directory -Path $script:InputDir -Force | Out-Null
    Set-Content -Path (Join-Path $script:InputDir 'file.txt') -Value 'test-content'
  }

  AfterEach {
    if (Test-Path $script:TestDir) {
      Remove-Item $script:TestDir -Recurse -Force -ErrorAction SilentlyContinue
    }
  }

  # ── PS5 compatibility ────────────────────────────────────────────────────

  Context 'PowerShell 5 compatibility' {
    It 'parses without errors in PowerShell 5' {
      if (-not (Get-Command powershell.exe -ErrorAction SilentlyContinue)) {
        Set-ItResult -Skipped -Because 'powershell.exe (PS5) not available on this platform'
        return
      }
      $testDir = Join-Path (Join-Path $script:RepoRoot 'test') 'wrapper'
      & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $testDir 'ps5-check.ps1') $script:WrapperScript
      $LASTEXITCODE | Should -Be 0 -Because 'spice.ps1 must parse cleanly in PowerShell 5.1'
    }
  }

  # ── Arg parsing: basic commands ──────────────────────────────────────────

  Context 'Arg parsing — basic commands' {
    It 'survey inventory with directory input' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
      $r.ContainerArgs | Should -Contain '/mnt/input'
    }

    It 'survey inventory with single file input' {
      $file = Join-Path $script:InputDir 'file.txt'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $file)
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
      $r.ContainerArgs | Should -Contain '/mnt/input/file.txt'
    }

    It 'pass decode' {
      $r = Invoke-SpiceWrapper -Arguments @('pass', 'decode')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'pass'
      $r.ContainerArgs | Should -Contain 'decode'
    }

    It '--version flag reaches container' {
      $r = Invoke-SpiceWrapper -Arguments @('--version')
      $r.ContainerArgs | Should -Contain '--version'
    }

    It '--help flag reaches container' {
      $r = Invoke-SpiceWrapper -Arguments @('--help')
      $r.ContainerArgs | Should -Contain '--help'
    }

    It 'survey --help passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', '--help')
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain '--help'
    }
  }

  # ── Output directory ─────────────────────────────────────────────────────

  Context 'Output directory' {
    It '--output (space) creates dir and mounts volume' {
      $outDir = Join-Path $script:TestDir 'output-space'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--output', $outDir)
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain '--output'
      $r.ContainerArgs | Should -Contain '/mnt/output'
      $outDir | Should -Exist
    }

    It '--output= (equals) creates dir and mounts volume' {
      $outDir = Join-Path $script:TestDir 'output-eq'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, "--output=$outDir")
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain '--output'
      $r.ContainerArgs | Should -Contain '/mnt/output'
      $outDir | Should -Exist
    }

    It 'default output dir created when --output omitted (bug #530)' {
      $defaultDir = Join-Path (Join-Path $HOME '.spicelabs') 'surveyor'
      if (Test-Path $defaultDir) { Remove-Item $defaultDir -Recurse -Force }

      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $defaultDir | Should -Exist
      # Verify the volume mount is in the docker run args
      $volArg = $r.DockerRunArgs | Where-Object { $_ -match '\.spicelabs' -and $_ -match 'surveyor' }
      $volArg | Should -Not -BeNullOrEmpty
    }
  }

  # ── Value flags pass-through ─────────────────────────────────────────────

  Context 'Value flags pass-through' {
    It '--threads N passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--threads', '4')
      $r.ContainerArgs | Should -Contain '--threads'
      $r.ContainerArgs | Should -Contain '4'
    }

    It '--max-records N passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--max-records', '1000')
      $r.ContainerArgs | Should -Contain '--max-records'
      $r.ContainerArgs | Should -Contain '1000'
    }

    It '--chunk-size N passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--chunk-size', '128')
      $r.ContainerArgs | Should -Contain '--chunk-size'
      $r.ContainerArgs | Should -Contain '128'
    }

    It '--tag-json value passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--tag-json', '{"env":"ci"}')
      $r.ContainerArgs | Should -Contain '--tag-json'
      # The docker args file captures what the wrapper passed to docker.
      # Verify via docker args file since the .bat mock may mangle JSON braces.
      $jsonArg = $r.DockerRunArgs | Where-Object { $_ -match 'env' -and $_ -match 'ci' }
      $jsonArg | Should -Not -BeNullOrEmpty
    }

    It '--ginger-args value passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--ginger-args', '--timeout=30')
      $r.ContainerArgs | Should -Contain '--ginger-args'
      $r.ContainerArgs | Should -Contain '--timeout=30'
    }

    It '--goat-rodeo-args value passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--goat-rodeo-args', '--parallel')
      $r.ContainerArgs | Should -Contain '--goat-rodeo-args'
      $r.ContainerArgs | Should -Contain '--parallel'
    }

    It 'all value flags combined' {
      $r = Invoke-SpiceWrapper -Arguments @(
        'survey', 'inventory', 'myapp', $script:InputDir,
        '--threads', '4', '--max-records', '1000', '--chunk-size', '128',
        '--tag-json', '{"k":"v"}', '--ginger-args', '--g', '--goat-rodeo-args', '--r'
      )
      $r.ContainerArgs | Should -Contain '--threads'
      $r.ContainerArgs | Should -Contain '4'
      $r.ContainerArgs | Should -Contain '--max-records'
      $r.ContainerArgs | Should -Contain '1000'
      $r.ContainerArgs | Should -Contain '--chunk-size'
      $r.ContainerArgs | Should -Contain '128'
      $r.ContainerArgs | Should -Contain '--tag-json'
      # JSON values verified via docker args file (bat mock may mangle braces)
      ($r.DockerRunArgs | Where-Object { $_ -match 'k' -and $_ -match 'v' }) | Should -Not -BeNullOrEmpty
      $r.ContainerArgs | Should -Contain '--ginger-args'
      $r.ContainerArgs | Should -Contain '--g'
      $r.ContainerArgs | Should -Contain '--goat-rodeo-args'
      $r.ContainerArgs | Should -Contain '--r'
    }

    It 'flags before positional args' {
      $r = Invoke-SpiceWrapper -Arguments @(
        'survey', 'inventory', '--threads', '4', '--log-level', 'debug',
        'myapp', $script:InputDir
      )
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
      $r.ContainerArgs | Should -Contain '/mnt/input'
      $r.ContainerArgs | Should -Contain '--threads'
      $r.ContainerArgs | Should -Contain '4'
      $r.ContainerArgs | Should -Contain '--log-level'
      $r.ContainerArgs | Should -Contain 'debug'
    }
  }

  # ── Boolean flags ────────────────────────────────────────────────────────

  Context 'Boolean flags' {
    It '--no-upload passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--no-upload')
      $r.ContainerArgs | Should -Contain '--no-upload'
    }

    It '--upload-only passes through' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--upload-only')
      $r.ContainerArgs | Should -Contain '--upload-only'
    }
  }

  # ── Log file ─────────────────────────────────────────────────────────────

  Context 'Log file' {
    It '--log-file (space) creates log file with content' {
      $logFile = Join-Path $script:TestDir 'test.log'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--log-file', $logFile)
      $r.ExitCode | Should -Be 0
      $logFile | Should -Exist
      $content = Get-Content $logFile -Raw
      $content | Should -Not -BeNullOrEmpty
      $content | Should -Match 'SPICE_TEST_BEGIN'
    }

    It '--log-file= (equals) creates log file' {
      $logFile = Join-Path $script:TestDir 'test-eq.log'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, "--log-file=$logFile")
      $r.ExitCode | Should -Be 0
      $logFile | Should -Exist
      Get-Content $logFile -Raw | Should -Match 'SPICE_TEST_BEGIN'
    }

    It 'log file has ANSI codes stripped' {
      $logFile = Join-Path $script:TestDir 'ansi.log'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--log-file', $logFile)
      $r.ExitCode | Should -Be 0
      $content = Get-Content $logFile -Raw
      $content | Should -Match 'COLORED:green-text'
      $content | Should -Match 'COLORED:red-text'
      # On Windows with .bat mock, no real ANSI codes are emitted.
      # On Linux, the shell mock produces real ANSI codes that get stripped.
      # Either way, the log file should not contain escape sequences.
      $content | Should -Not -Match '\x1b\['
    }

    It 'log file written when --threads present (bug #529)' {
      $logFile = Join-Path $script:TestDir 'threads.log'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--threads', '2', '--log-file', $logFile)
      $r.ExitCode | Should -Be 0
      $logFile | Should -Exist
      Get-Content $logFile -Raw | Should -Match 'SPICE_TEST_BEGIN'
    }

    It 'log file written with all extra flags (bug #529)' {
      $logFile = Join-Path $script:TestDir 'allflags.log'
      $r = Invoke-SpiceWrapper -Arguments @(
        'survey', 'inventory', 'myapp', $script:InputDir,
        '--threads', '2', '--max-records', '100', '--chunk-size', '32',
        '--ginger-args', '--g', '--tag-json', '{"k":"v"}',
        '--log-file', $logFile
      )
      $r.ExitCode | Should -Be 0
      $logFile | Should -Exist
      Get-Content $logFile -Raw | Should -Match 'SPICE_TEST_BEGIN'
    }

    It '--log-file stripped from container args' {
      $logFile = Join-Path $script:TestDir 'stripped.log'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--log-file', $logFile)
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Not -Contain '--log-file'
      $r.ContainerArgs | Should -Not -Contain $logFile
    }
  }

  # ── SPICE_PASS ───────────────────────────────────────────────────────────

  Context 'SPICE_PASS' {
    It 'SPICE_PASS passed to container' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir) -SpicePass 'my-secret-token'
      $r.ExitCode | Should -Be 0
      $r.ContainerEnv['SPICE_PASS'] | Should -Be 'my-secret-token'
    }

    It 'SPICE_PASS trimmed of whitespace' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir) -SpicePass "  spaces-around  `r`n"
      $r.ExitCode | Should -Be 0
      $r.ContainerEnv['SPICE_PASS'] | Should -Be 'spaces-around'
    }
  }

  # ── Exit code ────────────────────────────────────────────────────────────

  Context 'Exit code' {
    It 'non-zero exit code propagated' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir) -DockerFlags '-e TEST_EXIT_CODE=42'
      $r.ExitCode | Should -Be 42
    }
  }

  # ── Docker command construction ──────────────────────────────────────────

  Context 'Docker command' {
    It 'includes --network host' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.DockerRunArgs | Should -Contain '--network'
      $r.DockerRunArgs | Should -Contain 'host'
    }

    It 'includes --pull=never when SKIP_PULL set' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.DockerRunArgs | Should -Contain '--pull=never'
    }

    It 'includes volume mount for input' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $volMount = $r.DockerRunArgs | Where-Object { $_ -match '/mnt/input' }
      $volMount | Should -Not -BeNullOrEmpty
    }
  }

  # ── JVM mode ─────────────────────────────────────────────────────────────────────

  Context 'JVM mode' {
    It 'args passed to java correctly' {
      $jar = Join-Path $script:TestDir 'fake.jar'
      Set-Content -Path $jar -Value 'fake'
      $env:SPICE_LABS_CLI_USE_JVM = '1'
      $env:SPICE_LABS_CLI_JAR = $jar
      $env:JAVA_ARGS_FILE = $script:JavaArgsFile
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', '/some/path', '--threads', '4')
        $javaArgs = @(Get-Content $script:JavaArgsFile -ErrorAction SilentlyContinue)
        $javaArgs | Should -Not -BeNullOrEmpty
        ($javaArgs -join ' ') | Should -Match '-jar'
        ($javaArgs -join ' ') | Should -Match 'fake\.jar'
        ($javaArgs -join ' ') | Should -Match 'survey'
        ($javaArgs -join ' ') | Should -Match '--threads'
      } finally {
        Remove-Item env:SPICE_LABS_CLI_USE_JVM -ErrorAction SilentlyContinue
        Remove-Item env:SPICE_LABS_CLI_JAR -ErrorAction SilentlyContinue
        Remove-Item env:JAVA_ARGS_FILE -ErrorAction SilentlyContinue
      }
    }

    It '--log-file stripped before passing to java' {
      $jar = Join-Path $script:TestDir 'fake.jar'
      Set-Content -Path $jar -Value 'fake'
      $logFile = Join-Path $script:TestDir 'test.log'
      $env:SPICE_LABS_CLI_USE_JVM = '1'
      $env:SPICE_LABS_CLI_JAR = $jar
      $env:JAVA_ARGS_FILE = $script:JavaArgsFile
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', '/some/path', '--log-file', $logFile)
        $javaArgs = @(Get-Content $script:JavaArgsFile -ErrorAction SilentlyContinue)
        $javaArgs | Should -Not -BeNullOrEmpty
        ($javaArgs -join ' ') | Should -Not -Match '--log-file'
        ($javaArgs -join ' ') | Should -Not -Match ([regex]::Escape($logFile))
      } finally {
        Remove-Item env:SPICE_LABS_CLI_USE_JVM -ErrorAction SilentlyContinue
        Remove-Item env:SPICE_LABS_CLI_JAR -ErrorAction SilentlyContinue
        Remove-Item env:JAVA_ARGS_FILE -ErrorAction SilentlyContinue
      }
    }
  }

  # ── Runtime survey orchestration ───────────────────────────────────────

  Context 'Runtime survey' {
    BeforeAll {
      # Helper: create a script that performs an action AND creates a fake .jfr
      # so the wrapper doesn't abort at the "no recordings" check.
      function New-TestScript {
        param([string]$Name, [string]$WinBody, [string]$UnixBody)
        if ($IsWindows -or -not (Test-Path variable:IsWindows)) {
          # On Windows, create a .ps1 script that does the action + creates a fake .jfr
          # Then wrap it in a .cmd that calls powershell
          $ps1Path = Join-Path $script:TestDir "$Name.ps1"
          $ps1Body = @"
$WinBody
`$jto = `$env:JAVA_TOOL_OPTIONS
if (`$jto -match 'settings=([^,]+)') {
  `$d = Split-Path `$matches[1] -Parent
  Set-Content -Path (Join-Path `$d 'recording-fake.jfr') -Value 'fake'
}
"@
          Set-Content -Path $ps1Path -Value $ps1Body
          $path = Join-Path $script:TestDir "$Name.cmd"
          Set-Content -Path $path -Value "@powershell -NoProfile -ExecutionPolicy Bypass -File `"$ps1Path`" %*"
        } else {
          $path = Join-Path $script:TestDir "$Name.sh"
          $jfrSnippet = "`n_dir=`$(echo `"`$JAVA_TOOL_OPTIONS`" | sed -n 's/.*settings=\([^ ,]*\).*/\1/p' | xargs dirname)`necho fake > `"`$_dir/recording-`$`$.jfr`""
          Set-Content -Path $path -Value "#!/bin/bash`n$UnixBody$jfrSnippet"
          chmod +x $path
        }
        return $path
      }
    }

    It 'missing command after -- fails' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr')
      $r.ExitCode | Should -Be 1
    }

    It 'missing subject fails' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', '--jfr', '--', 'echo', 'hello')
      $r.ExitCode | Should -Be 1
    }

    It 'target command runs on host' {
      $marker = Join-Path $script:TestDir 'host-ran.txt'
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-host-$PID"
      $cmd = New-TestScript -Name 'touch' -WinBody "Set-Content -Path '$marker' -Value 'test'" -UnixBody "touch `"$marker`""
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--output', $outdir, '--', $cmd)
        $marker | Should -Exist
      } finally {
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
    }

    It 'JAVA_TOOL_OPTIONS set for target' {
      $dump = Join-Path $script:TestDir 'jto-dump.txt'
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-jto-$PID"
      $cmd = New-TestScript -Name 'dump-jto' -WinBody "Set-Content -Path '$dump' -Value `$env:JAVA_TOOL_OPTIONS" -UnixBody "echo `\"`\`$JAVA_TOOL_OPTIONS`\" > `"$dump`""
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--output', $outdir, '--', $cmd)
        $dump | Should -Exist
        $jto = Get-Content $dump -Raw
        $jto | Should -Match 'StartFlightRecording'
        $jto | Should -Match 'dumponexit=true'
        $jto | Should -Match 'spice-jfr\.jfc'
      } finally {
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
    }

    It 'workdir created under output dir' {
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-workdir-$PID"
      $cmd = New-TestScript -Name 'noop-workdir' -WinBody '' -UnixBody 'true'
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--keep-recording', '--output', $outdir, '--', $cmd)
        $found = Get-ChildItem -Path $outdir -Directory -Filter 'survey-*' -ErrorAction SilentlyContinue | Select-Object -First 1
        $found | Should -Not -BeNullOrEmpty
      } finally {
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
    }

    It 'workdir cleaned up without --keep-recording' {
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-cleanup-$PID"
      $cmd = New-TestScript -Name 'noop-cleanup' -WinBody '' -UnixBody 'true'
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--output', $outdir, '--', $cmd)
        $found = Get-ChildItem -Path $outdir -Directory -Filter 'survey-*' -ErrorAction SilentlyContinue | Select-Object -First 1
        $found | Should -BeNullOrEmpty
      } finally {
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
    }

    It 'recordings kept with --keep-recording' {
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-keep-$PID"
      $cmd = New-TestScript -Name 'fake-jfr-keep' -WinBody '' -UnixBody 'true'
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--keep-recording', '--output', $outdir, '--', $cmd)
        # Verify workdir was kept (not cleaned up)
        $found = Get-ChildItem -Path $outdir -Directory -Filter 'survey-*' -ErrorAction SilentlyContinue | Select-Object -First 1
        $found | Should -Not -BeNullOrEmpty
      } finally {
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
    }

    It 'JFC extracted from container' {
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-jfc-$PID"
      $cmd = New-TestScript -Name 'noop-jfc' -WinBody '' -UnixBody 'true'
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--keep-recording', '--output', $outdir, '--', $cmd)
        $workdir = Get-ChildItem -Path $outdir -Directory -Filter 'survey-*' -ErrorAction SilentlyContinue | Select-Object -First 1
        $workdir | Should -Not -BeNullOrEmpty
        (Join-Path $workdir.FullName 'spice-jfr.jfc') | Should -Exist
      } finally {
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
    }
  }
}
