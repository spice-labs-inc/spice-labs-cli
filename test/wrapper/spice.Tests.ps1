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
using System.Linq;
class MockDocker {
  static string HostPath(string vol) {
    int sep = vol.IndexOf(':', vol.Length > 2 && vol[1] == ':' ? 2 : 0);
    return sep > 0 ? vol.Substring(0, sep) : vol;
  }
  static string ContainerPath(string vol) {
    int sep = vol.IndexOf(':', vol.Length > 2 && vol[1] == ':' ? 2 : 0);
    return sep > 0 ? vol.Substring(sep + 1) : vol;
  }
  static int Main(string[] args) {
    if (args.Length > 0 && args[0] == "pull") return 0;
    // Detect runtime survey calls by --entrypoint
    string entrypoint = null;
    string workDir = null;
    var volumes = new Dictionary<string,string>();
    for (int j = 0; j < args.Length - 1; j++) {
      if (args[j] == "--entrypoint") entrypoint = args[j+1];
      if (args[j] == "-w") workDir = args[j+1];
      if (args[j] == "-v" && args[j+1].Contains(":")) {
        var vol = args[j+1];
        volumes[ContainerPath(vol)] = HostPath(vol);
      }
    }
    // Registry-login tests: keep the docker config the wrapper mounted, which may be a
    // temporary one removed when the run ends.
    var copyTo = Environment.GetEnvironmentVariable("MOCK_DOCKER_CONFIG_COPY");
    if (!string.IsNullOrEmpty(copyTo) && volumes.ContainsKey("/mnt/spice/docker-config:ro")) {
      var src = Path.Combine(volumes["/mnt/spice/docker-config:ro"], "config.json");
      if (File.Exists(src)) File.Copy(src, copyTo, true);
    }
    // Identity mounts mean the working directory the wrapper passes is also a real
    // host directory, so the mock can write there directly.
    if (workDir != null && volumes.ContainsKey(workDir)) workDir = volumes[workDir];
    // Phase 1: extraction. MOCK_NO_RUNTIME_FILES stands in for an image without runtime
    // surveys: nothing to copy, and the copy fails.
    if (entrypoint == "sh" && Environment.GetEnvironmentVariable("MOCK_NO_RUNTIME_FILES") == "1") return 1;
    if (entrypoint == "sh" && volumes.Count > 0) {
      foreach (var kv in volumes) {
        if (Directory.Exists(kv.Value)) {
          File.WriteAllText(Path.Combine(kv.Value, "ancho.jar"), "mock");
          File.WriteAllText(Path.Combine(kv.Value, "spice-jfr.jfc"), "mock");
        }
      }
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
      if (found) { cli.Add(a); continue; }
      if (prev == "-e") {
        int eq = a.IndexOf('=');
        if (eq > 0) env[a.Substring(0,eq)] = a.Substring(eq+1);
        prev = ""; continue;
      }
      if (a == "-e") { prev = a; continue; }
      prev = "";
      // The actual wrapper always places the image ref immediately before the CLI args,
      // so once we see a known command name the following args belong to the CLI.
      if (a == "survey" || a == "pass" || a == "registry") { found = true; cli.Add(a); continue; }
      if (a.StartsWith("spice-")) { found = true; continue; }
      // Detect image refs like ghcr.io/...:tag or spicelabs/spice-labs-cli:latest
      if (a.Length > 0 && char.IsLower(a[0]) && !a.StartsWith("--") && a != "run" && a != "host" && a != "never"
          && (a.Contains(":") || a.Contains("/"))) { found = true; continue; }
    }
    // If no --output was given, write a marker to the container's working directory.
    // The wrapper mounts the user's current directory at its own path and passes it as
    // -w, so a relative write inside the container lands there on the host.
    bool hasOutput = false;
    foreach (var a in cli) {
      if (a == "--output" || a.StartsWith("--output=")) { hasOutput = true; break; }
    }
    if (!hasOutput && workDir != null) {
      try {
        Directory.CreateDirectory(workDir);
        File.WriteAllText(Path.Combine(workDir, "default-marker.txt"), "DEFAULT");
        Console.WriteLine("WROTE:" + workDir + "/default-marker.txt");
      } catch {}
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

    # A credential helper, as Docker Desktop installs one: registry on stdin, JSON out.
    $mockHelperExe = Join-Path $script:MockBinDir 'docker-credential-fake.exe'
    $mockHelperCs = @'
using System;
using System.IO;
class FakeHelper {
  static int Main(string[] args) {
    if (args.Length < 1 || args[0] != "get") return 1;
    string registry = Console.In.ReadToEnd().Trim();
    var log = Environment.GetEnvironmentVariable("FAKE_HELPER_LOG");
    if (!string.IsNullOrEmpty(log)) File.AppendAllText(log, registry + "\n");
    if (registry == "https://index.docker.io/v1/") {
      Console.WriteLine("{\"ServerURL\":\"https://index.docker.io/v1/\",\"Username\":\"hubuser\",\"Secret\":\"hub-s3cret\"}");
      return 0;
    }
    if (registry == "ghcr.io") {
      Console.WriteLine("{\"ServerURL\":\"ghcr.io\",\"Username\":\"ghuser\",\"Secret\":\"gh-s3cret\"}");
      return 0;
    }
    if (registry == "tok.example.com") {
      Console.WriteLine("{\"ServerURL\":\"tok.example.com\",\"Username\":\"<token>\",\"Secret\":\"tok-s3cret\"}");
      return 0;
    }
    Console.WriteLine("credentials not found in native keychain");
    return 1;
  }
}
'@
    $mockHelperCsFile = Join-Path $script:MockBinDir 'FakeHelper.cs'
    Set-Content -Path $mockHelperCsFile -Value $mockHelperCs
    & $csc /nologo /out:$mockHelperExe /target:exe $mockHelperCsFile 2>&1 | Out-Null
    if (-not (Test-Path $mockHelperExe)) { throw "Failed to compile mock docker-credential-fake.exe" }

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
$cliArgs = @(); $foundImage = $false; $envVars = @{}; $volumes = @{}; $prevFlag = ''
foreach ($a in $allArgs) {
  if ($foundImage) { $cliArgs += $a; continue }
  if ($prevFlag -eq '-e') { if ($a -match '^([^=]+)=(.*)$') { $envVars[$Matches[1]] = $Matches[2] }; $prevFlag = ''; continue }
  if ($prevFlag -eq '-v') { $parts = $a -split ':', 2; if ($parts.Count -ge 2) { $volumes[$parts[1]] = $parts[0] }; $prevFlag = ''; continue }
  if ($a -eq '-e') { $prevFlag = '-e'; continue }
  if ($a -eq '-v') { $prevFlag = '-v'; continue }
  $prevFlag = ''
  # The actual wrapper always places the image ref immediately before the CLI args,
  # so once we see a known command name the following args belong to the CLI.
  if ($a -eq 'survey' -or $a -eq 'pass' -or $a -eq 'registry') { $foundImage = $true; $cliArgs += $a; continue }
  if ($a -match '^[a-z]' -and $a -notmatch '^--' -and $a -notmatch '^host$' -and $a -match '(:|/)') { $foundImage = $true; continue }
  if ($a -match '^spice-') { $foundImage = $true; continue }
}
# Registry-login tests: keep the docker config the wrapper mounted (mirrors the C# mock).
if ($env:MOCK_DOCKER_CONFIG_COPY -and $volumes['/mnt/spice/docker-config:ro']) {
  $src = Join-Path $volumes['/mnt/spice/docker-config:ro'] 'config.json'
  if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination $env:MOCK_DOCKER_CONFIG_COPY -Force }
}
# If no --output was given and /mnt/output is mounted, write the default marker
# file to the host dir so tests can verify the volume mount (mirrors the C# mock).
$hasOutput = $false
foreach ($ca in $cliArgs) { if ($ca -eq '--output' -or $ca -like '--output=*') { $hasOutput = $true; break } }
if (-not $hasOutput -and $volumes['/mnt/output']) {
  $outDir = $volumes['/mnt/output']
  try {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    Set-Content -Path (Join-Path $outDir 'default-marker.txt') -Value 'DEFAULT'
  } catch {}
}
Write-Output '===SPICE_TEST_BEGIN==='
foreach ($ca in $cliArgs) { Write-Output "ARG:$ca" }
Write-Output "ENV:SPICE_PASS=$($envVars['SPICE_PASS'])"
Write-Output "ENV:SPICE_LABS_JVM_ARGS=$($envVars['SPICE_LABS_JVM_ARGS'])"
Write-Output "$([char]27)[32mCOLORED:green-text$([char]27)[0m"
Write-Output "$([char]27)[31mCOLORED:red-text$([char]27)[0m"
[Console]::Error.WriteLine('STDERR:test-error-output')
Write-Output '===SPICE_TEST_END==='
# Check for TEST_EXIT_CODE in -e args (mirrors the C# mock)
$exitCode = 0
$pf = ''
foreach ($a in $allArgs) {
  if ($pf -eq '-e' -and $a -match '^TEST_EXIT_CODE=(\d+)$') { $exitCode = [int]$Matches[1] }
  $pf = $a
}
exit $exitCode
'@
    # Shell mock handles runtime survey entrypoint calls directly (no pwsh needed)
    # and falls back to the pwsh mock for normal docker run calls.
    Set-Content -Path $mockDockerSh -Value @"
#!/bin/bash
if [ "`$1" = 'pull' ]; then exit 0; fi

# Detect runtime survey Docker calls by --entrypoint
_entrypoint=""
_vol_host=""
_prev=""
for _arg in "`$@"; do
  if [ "`$_prev" = "--entrypoint" ]; then _entrypoint="`$_arg"; fi
  if [ "`$_prev" = "-v" ]; then _vol_host="`${_arg%%:*}"; fi
  _prev="`$_arg"
done

# Phase 1: extraction (--entrypoint sh). MOCK_NO_RUNTIME_FILES stands in for an image
# without runtime surveys: nothing to copy, and the copy fails.
if [ "`$_entrypoint" = "sh" ] && [ "`${MOCK_NO_RUNTIME_FILES:-}" = "1" ]; then
  exit 1
fi

# Phase 1: extraction (--entrypoint sh) — create mock files in workdir
if [ "`$_entrypoint" = "sh" ] && [ -n "`$_vol_host" ] && [ -d "`$_vol_host" ]; then
  echo "mock" > "`$_vol_host/ancho.jar"
  echo "mock" > "`$_vol_host/spice-jfr.jfc"
  echo done
  exit 0
fi

# Phase 4: RuntimeCollect (--entrypoint java) — just succeed
if [ "`$_entrypoint" = "java" ]; then
  exit 0
fi

pwsh -NoProfile -File "$mockDockerPs1" "$@"
"@
    chmod +x $mockDockerSh 2>`$null

    # Mock java on PATH for JVM mode tests (mirrors the Windows java.exe mock)
    $script:JavaArgsFile = Join-Path $script:MockBinDir 'java-args.txt'
    $mockJavaSh = Join-Path $script:MockBinDir 'java'
    Set-Content -Path $mockJavaSh -Value @"
#!/bin/bash
if [ -n "`$JAVA_ARGS_FILE" ]; then
  echo "`$@" > "`$JAVA_ARGS_FILE"
fi
echo '===SPICE_TEST_BEGIN==='
for arg in "`$@"; do echo "ARG:`$arg"; done
echo '===SPICE_TEST_END==='
exit 0
"@
    chmod +x $mockJavaSh 2>`$null

    # A credential helper, as Docker Desktop installs one (mirrors the Windows .exe).
    $mockHelperSh = Join-Path $script:MockBinDir 'docker-credential-fake'
    Set-Content -Path $mockHelperSh -Value @'
#!/bin/bash
[ "$1" = get ] || exit 1
IFS= read -r registry || true
[ -n "$FAKE_HELPER_LOG" ] && echo "$registry" >> "$FAKE_HELPER_LOG"
case "$registry" in
  https://index.docker.io/v1/) echo '{"ServerURL":"https://index.docker.io/v1/","Username":"hubuser","Secret":"hub-s3cret"}' ;;
  ghcr.io) echo '{"ServerURL":"ghcr.io","Username":"ghuser","Secret":"gh-s3cret"}' ;;
  tok.example.com) echo '{"ServerURL":"tok.example.com","Username":"<token>","Secret":"tok-s3cret"}' ;;
  *) echo 'credentials not found in native keychain'; exit 1 ;;
esac
'@
    chmod +x $mockHelperSh 2>$null
  }

  function global:Convert-TestPathToDockerPath($p) {
    if ($IsWindows -or -not (Test-Path variable:IsWindows)) {
      if ($p -match '^([A-Za-z]):') { $p = $p -replace '^[A-Za-z]:', "/$($matches[1].ToLower())" }
      $p = $p -replace '\\', '/'
    }
    return $p
  }

  # Write a manifest describing the `registry` plugin, standing in for what the
  # IT image reports. The wrapper has no built-in knowledge of these
  # commands — that is the point — so a test exercising them must supply the
  # manifest, just as the real image does. Mirrors use_registry_manifest in
  # spice.bats; keep the two in step.
  function New-RegistryManifest {
    $path = Join-Path $script:TestDir 'registry.path-manifest'
    @'
# spice-path-manifest 1
V 1
G test-fixture
R /
R /etc
R /opt
R /usr
R /var
C spice
C spice/registry
C spice/registry/init
C spice/registry/discover
C spice/registry/run
C spice/registry/cbom
O spice/registry/init --config-only flag
O spice/registry/init --dir value path create=self
O spice/registry/init --file value path create=parent
O spice/registry/discover --config value path create=parent
O spice/registry/discover --output value path create=parent
O spice/registry/run --config value path create=parent
O spice/registry/run --discovery value path create=parent
O spice/registry/cbom --config value path create=parent
O spice/registry/cbom --rogues value path create=parent
O spice/registry/cbom --output value path create=self
'@ | Set-Content -LiteralPath $path -Encoding ascii
    return $path
  }

  # Write a manifest carrying a config-paths section naming the given paths — what
  # the image reports when asked about a config file. Mirrors
  # use_config_paths_manifest in spice.bats; keep the two in step.
  function New-ConfigPathsManifest([string[]]$Paths) {
    $path = Join-Path $script:TestDir 'config.path-manifest'
    $lines = @(
      '# spice-path-manifest 1', 'V 1', 'G test-fixture',
      'R /', 'R /etc', 'R /opt', 'R /usr', 'R /var',
      'C spice', 'C spice/survey', 'C spice/survey/inventory',
      'O spice --config value path create=parent',
      'P spice/survey/inventory 0 value',
      'P spice/survey/inventory 1 value path exists',
      '', '# spice-config-paths 1'
    )
    foreach ($p in $Paths) { $lines += "P $p" }
    ($lines -join "`n") | Set-Content -LiteralPath $path -Encoding ascii
    return $path
  }

  # ── Helper: run the wrapper with mock docker and parse output ────────────
  function Invoke-SpiceWrapper {
    [CmdletBinding()]
    param(
      [Parameter(Mandatory)]
      [string[]]$Arguments,
      [string]$SpicePass = 'test-pass-value',
      [string]$DockerFlags,
      [AllowNull()]
      [string]$SpiceImage = 'spice-wrapper-test',
      [string]$PathManifest
    )

    # Put mock docker first on PATH
    $env:PATH = "$($script:MockBinDir)$([System.IO.Path]::PathSeparator)$($env:PATH)"

    $env:SPICE_LABS_CLI_SKIP_PULL = '1'
    # The mock docker records every invocation, so a manifest refresh would clobber the
    # captured args. These tests exercise the manifest embedded in the wrapper.
    $env:SPICE_SKIP_MANIFEST_REFRESH = '1'
    if ($PathManifest) {
      $env:SPICE_PATH_MANIFEST = $PathManifest
    } else {
      Remove-Item env:SPICE_PATH_MANIFEST -ErrorAction SilentlyContinue
    }
    if ($null -eq $SpiceImage) {
      Remove-Item env:SPICE_IMAGE -ErrorAction SilentlyContinue
    } else {
      $env:SPICE_IMAGE = $SpiceImage
    }
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
    # The wrapper writes its own messages straight to the console error stream, which
    # *>&1 does not see when the script runs in this process.
    $stderrWriter = New-Object System.IO.StringWriter
    $savedStderr = [Console]::Error
    [Console]::SetError($stderrWriter)
    try {
      # Reset LASTEXITCODE by running a trivial native command that exits 0
      if ($IsWindows -or -not (Test-Path variable:IsWindows)) { cmd /c "exit /b 0" } else { true }
      $rawLines = @(& $script:WrapperScript @Arguments *>&1 | ForEach-Object { "$_" })
      $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }

    } catch {
      $rawLines = @("EXCEPTION:$($_.Exception.Message)")
      $exitCode = 1

    } finally {
      $ErrorActionPreference = $savedEAP
      [Console]::SetError($savedStderr)
    }
    $stderrLines = @($stderrWriter.ToString() -split "`r?`n" | Where-Object { $_ })

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
      Stderr        = $stderrLines
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
      # Identity mount: the container sees the input where the user typed it,
      # modulo the Windows drive-letter translation docker requires.
      $r.ContainerArgs | Should -Contain (Convert-TestPathToDockerPath $script:InputDir)
    }

    It 'survey inventory with single file input' {
      $file = Join-Path $script:InputDir 'file.txt'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $file)
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
      $r.ContainerArgs | Should -Contain (Convert-TestPathToDockerPath $file)
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

    It '--features it switches to IT image and strips flag' {
      $r = Invoke-SpiceWrapper -SpiceImage $null -Arguments @('--features', 'it', 'survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-it:latest'
      $r.ContainerArgs | Should -Not -Contain '--features'
      $r.ContainerArgs | Should -Not -Contain 'it'
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
    }

    It '--features=it switches to IT image and strips flag' {
      $r = Invoke-SpiceWrapper -SpiceImage $null -Arguments @('--features=it', 'survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-it:latest'
      $r.ContainerArgs | Should -Not -Contain '--features'
      $r.ContainerArgs | Should -Not -Contain 'it'
    }

    It 'SPICE_IMAGE env overrides --features it' {
      $r = Invoke-SpiceWrapper -SpiceImage 'spice-wrapper-custom' -Arguments @('--features', 'it', 'survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'spice-wrapper-custom'
      $r.DockerRunArgs | Should -Not -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-it:latest'
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
    }

    It '--features otpro switches to OT Pro image and strips flag' {
      $r = Invoke-SpiceWrapper -SpiceImage $null -Arguments @('--features', 'otpro', 'registry', 'discover')
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-otpro:latest'
      $r.ContainerArgs | Should -Not -Contain '--features'
      $r.ContainerArgs | Should -Not -Contain 'otpro'
      $r.ContainerArgs | Should -Contain 'registry'
      $r.ContainerArgs | Should -Contain 'discover'
    }

    It '--features=otpro switches to OT Pro image and strips flag' {
      $r = Invoke-SpiceWrapper -SpiceImage $null -Arguments @('--features=otpro', 'registry', 'discover')
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-otpro:latest'
      $r.ContainerArgs | Should -Not -Contain '--features'
      $r.ContainerArgs | Should -Not -Contain 'otpro'
    }

    It 'SPICE_DOCKER_NETWORK replaces the default host network' {
      $env:SPICE_DOCKER_NETWORK = 'none'
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
        $r.ExitCode | Should -Be 0
        $r.DockerRunArgs | Should -Contain 'none'
        $r.DockerRunArgs | Should -Not -Contain 'host'
      } finally {
        Remove-Item env:SPICE_DOCKER_NETWORK -ErrorAction SilentlyContinue
      }
    }

    It 'the default container network is host' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'host'
    }

    It '--features itpro switches to IT Pro image and strips flag' {
      $r = Invoke-SpiceWrapper -SpiceImage $null -Arguments @('--features', 'itpro', 'survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-itpro:latest'
      $r.ContainerArgs | Should -Not -Contain '--features'
      $r.ContainerArgs | Should -Not -Contain 'itpro'
      $r.ContainerArgs | Should -Contain 'survey'
      $r.ContainerArgs | Should -Contain 'inventory'
      $r.ContainerArgs | Should -Contain 'myapp'
    }

    It '--features=itpro switches to IT Pro image and strips flag' {
      $r = Invoke-SpiceWrapper -SpiceImage $null -Arguments @('--features=itpro', 'survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $r.DockerRunArgs | Should -Contain 'ghcr.io/spice-labs-inc/spice-labs-cli-itpro:latest'
      $r.ContainerArgs | Should -Not -Contain '--features'
      $r.ContainerArgs | Should -Not -Contain 'itpro'
    }
  }

  # ── Output directory ─────────────────────────────────────────────────────

  Context 'Output directory' {
    It '--output (space) creates dir and mounts volume' {
      $outDir = Join-Path $script:TestDir 'output-space'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, '--output', $outDir)
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain '--output'
      $r.ContainerArgs | Should -Contain (Convert-TestPathToDockerPath $outDir)
      $outDir | Should -Exist
    }

    It '--output= (equals) creates dir and mounts volume' {
      $outDir = Join-Path $script:TestDir 'output-eq'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir, "--output=$outDir")
      $r.ExitCode | Should -Be 0
      # The joined form is preserved; only the value is absolutised.
      $r.ContainerArgs | Should -Contain "--output=$(Convert-TestPathToDockerPath $outDir)"
      $outDir | Should -Exist
    }

    It 'default output dir created when --output omitted (bug #530)' {
      # When --output is omitted, the wrapper mounts the current directory at its own
      # path and makes it the container's working directory, so a relative write inside
      # the container lands in the user's current directory on the host.
      $defaultDir = Get-Location

      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      # Verify the marker file appears in the current directory on the host
      $marker = Join-Path $defaultDir 'default-marker.txt'
      $marker | Should -Exist
      # Verify the current directory is mounted in the docker run args
      $volArg = $r.DockerRunArgs | Where-Object { $_ -match [regex]::Escape("$defaultDir") }
      $volArg | Should -Not -BeNullOrEmpty
      Remove-Item $marker -ErrorAction SilentlyContinue
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
      $r.ContainerArgs | Should -Contain (Convert-TestPathToDockerPath $script:InputDir)
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

  # ── Image surveys: docker:// input and registry logins ─────────────────────

  Context 'Image surveys and registry logins' {
    BeforeEach {
      $script:CfgDir = Join-Path $script:TestDir 'dockercfg'
      New-Item -ItemType Directory -Path $script:CfgDir -Force | Out-Null
      $script:HandedOver = Join-Path $script:TestDir 'handed-over.json'
      $script:HelperLog = Join-Path $script:TestDir 'helper-asked.log'
      Set-Content -LiteralPath $script:HelperLog -Value '' -NoNewline
      $env:DOCKER_CONFIG = $script:CfgDir
      $env:MOCK_DOCKER_CONFIG_COPY = $script:HandedOver
      $env:FAKE_HELPER_LOG = $script:HelperLog
    }

    AfterEach {
      Remove-Item env:DOCKER_CONFIG, env:MOCK_DOCKER_CONFIG_COPY, env:FAKE_HELPER_LOG -ErrorAction SilentlyContinue
    }

    BeforeAll {
      function Set-DockerConfig([string]$Json) {
        [System.IO.File]::WriteAllText((Join-Path $script:CfgDir 'config.json'), $Json)
      }

      # The host directory mounted as the container's docker config.
      function Get-MountedDockerConfig($r) {
        $mount = @($r.DockerRunArgs | Where-Object { $_ -like '*:/mnt/spice/docker-config:ro' }) | Select-Object -First 1
        if ($mount) { return ($mount -replace ':/mnt/spice/docker-config:ro$', '') }
        return $null
      }

      function Get-HandedOverAuths {
        $cfg = Get-Content -LiteralPath $script:HandedOver -Raw | ConvertFrom-Json
        @($cfg.PSObject.Properties.Name) | Should -Be @('auths')
        return $cfg.auths
      }

      function ConvertTo-Base64([string]$s) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)) }
    }

    It 'passes a docker:// input through untouched' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://ghcr.io/acme/app:1.0', '--no-upload')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'docker://ghcr.io/acme/app:1.0'
    }

    It 'passes an oci:// input through untouched' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'oci://ghcr.io/acme/app:1.0', '--no-upload')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'oci://ghcr.io/acme/app:1.0'
    }

    It 'hands over the helper login for an oci:// input too' {
      Set-DockerConfig '{"credsStore":"fake"}'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'oci://ghcr.io/acme/app:1.0', '--no-upload')
      (Get-HandedOverAuths).'ghcr.io'.auth | Should -Be (ConvertTo-Base64 'ghuser:gh-s3cret')
      Test-Path -LiteralPath (Get-MountedDockerConfig $r) | Should -BeFalse
    }

    It 'passes an image alone (no subject) through and hands over its login' {
      Set-DockerConfig '{"credsStore":"fake"}'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'docker://ghcr.io/acme/web:2.4.1', '--no-upload')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'docker://ghcr.io/acme/web:2.4.1'
      (Get-HandedOverAuths).'ghcr.io'.auth | Should -Be (ConvertTo-Base64 'ghuser:gh-s3cret')
    }

    It 'lets an image-looking input reach the CLI instead of the missing-path error' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'nginx:1.27', '--no-upload')
      $r.ExitCode | Should -Be 0
      ($r.Stderr -join "`n") | Should -Not -Match 'Input path does not exist'
      $r.ContainerArgs | Should -Contain 'nginx:1.27'
    }

    It 'mounts a config with the login inline as it is' {
      Set-DockerConfig '{"auths":{"ghcr.io":{"auth":"aW5saW5lOng="}},"credsStore":"fake"}'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://ghcr.io/acme/app:1.0')
      Get-MountedDockerConfig $r | Should -Be $script:CfgDir
      (Get-Content -LiteralPath $script:HelperLog -Raw) | Should -BeNullOrEmpty
    }

    It 'hands over the credsStore login for this registry only, then removes it' {
      Set-DockerConfig '{"auths":{"ghcr.io":{}},"credsStore":"fake"}'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://ghcr.io/acme/app:1.0', '--no-upload')
      $r.ExitCode | Should -Be 0
      (Get-HandedOverAuths).'ghcr.io'.auth | Should -Be (ConvertTo-Base64 'ghuser:gh-s3cret')
      $mounted = Get-MountedDockerConfig $r
      $mounted | Should -Not -Be $script:CfgDir
      Test-Path -LiteralPath $mounted | Should -BeFalse
      (($r.RawOutput + $r.Stderr + $r.DockerRunArgs) -join "`n") | Should -Not -Match 's3cret'
    }

    It 'prefers a credHelpers entry over credsStore' {
      Set-DockerConfig '{"credsStore":"not-installed","credHelpers":{"ghcr.io":"fake"}}'
      $null = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://ghcr.io/acme/app:1.0')
      (Get-HandedOverAuths).'ghcr.io'.auth | Should -Be (ConvertTo-Base64 'ghuser:gh-s3cret')
    }

    It 'logs Docker Hub images in under Docker Hub''s key' {
      Set-DockerConfig '{"credsStore":"fake"}'
      $null = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://nginx:1.27')
      (Get-Content -LiteralPath $script:HelperLog) | Should -Contain 'https://index.docker.io/v1/'
      (Get-HandedOverAuths).'https://index.docker.io/v1/'.auth | Should -Be (ConvertTo-Base64 'hubuser:hub-s3cret')
    }

    It 'hands over an identity token as one' {
      Set-DockerConfig '{"credsStore":"fake"}'
      $null = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://tok.example.com/acme/app:1.0')
      (Get-HandedOverAuths).'tok.example.com'.identitytoken | Should -Be 'tok-s3cret'
    }

    It 'asks the helper for the survey image alias too' {
      Set-DockerConfig '{"credsStore":"fake"}'
      $null = Invoke-SpiceWrapper -Arguments @('survey', 'image', '--subject', 'myapp', 'ghcr.io/acme/app:1.0')
      (Get-Content -LiteralPath $script:HelperLog) | Should -Contain 'ghcr.io'
    }

    It 'falls back to the config as it is when the helper is missing' {
      Set-DockerConfig '{"credsStore":"not-installed"}'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://ghcr.io/acme/app:1.0')
      Get-MountedDockerConfig $r | Should -Be $script:CfgDir
    }

    It 'removes the temporary login when the run fails' {
      Set-DockerConfig '{"credsStore":"fake"}'
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', 'docker://ghcr.io/acme/app:1.0') -DockerFlags '-e TEST_EXIT_CODE=3'
      $r.ExitCode | Should -Be 3
      Test-Path -LiteralPath (Get-MountedDockerConfig $r) | Should -BeFalse
    }
  }

  Context 'Exit code' {
    It 'non-zero exit code propagated' {
      $r = Invoke-SpiceWrapper -Arguments @('survey', 'inventory', 'myapp', $script:InputDir) -DockerFlags '-e TEST_EXIT_CODE=42'
      $r.ExitCode | Should -Be 42
    }
  }

  # ── Registry command (same-path mounts) ────────────────────────────────────

  Context 'Registry command (same-path mounts)' {
    It 'registry init --dir rewrites relative path to absolute same-path' {
      $initDir = Join-Path $script:TestDir 'registry-init'
      New-Item -ItemType Directory -Path $initDir -Force | Out-Null
      Push-Location $script:TestDir
      try {
        $r = Invoke-SpiceWrapper -PathManifest (New-RegistryManifest) -Arguments @('registry', 'init', '--dir', './registry-init')
        $r.ExitCode | Should -Be 0
        $r.ContainerArgs | Should -Contain 'registry'
        $r.ContainerArgs | Should -Contain 'init'
        $r.ContainerArgs | Should -Contain '--dir'
        $dockerInitDir = Convert-TestPathToDockerPath $initDir
        $r.ContainerArgs | Should -Contain $dockerInitDir
      } finally { Pop-Location }
    }

    It 'registry init --config-only --file rewrites relative path to absolute same-path' {
      $outDir = Join-Path $script:TestDir 'init-config'
      New-Item -ItemType Directory -Path $outDir -Force | Out-Null
      Push-Location $script:TestDir
      try {
        $r = Invoke-SpiceWrapper -PathManifest (New-RegistryManifest) -Arguments @('registry', 'init', '--config-only', '--file', './init-config/allspice.toml')
        $r.ExitCode | Should -Be 0
        $r.ContainerArgs | Should -Contain 'registry'
        $r.ContainerArgs | Should -Contain 'init'
        $r.ContainerArgs | Should -Contain '--config-only'
        $r.ContainerArgs | Should -Contain '--file'
        $outFile = Join-Path $outDir 'allspice.toml'
        $dockerOutFile = Convert-TestPathToDockerPath $outFile
        $r.ContainerArgs | Should -Contain $dockerOutFile
      } finally { Pop-Location }
    }

    It 'registry discover --config rewrites relative path to absolute same-path' {
      $configDir = Join-Path $script:TestDir 'config'
      New-Item -ItemType Directory -Path $configDir -Force | Out-Null
      Push-Location $script:TestDir
      try {
        $r = Invoke-SpiceWrapper -PathManifest (New-RegistryManifest) -Arguments @('registry', 'discover', '--config', './config/allspice.toml')
        $r.ExitCode | Should -Be 0
        $r.ContainerArgs | Should -Contain 'registry'
        $r.ContainerArgs | Should -Contain 'discover'
        $r.ContainerArgs | Should -Contain '--config'
        $configFile = Join-Path $configDir 'allspice.toml'
        $dockerConfig = Convert-TestPathToDockerPath $configFile
        $r.ContainerArgs | Should -Contain $dockerConfig
      } finally { Pop-Location }
    }

    It 'registry run --config --discovery rewrites relative paths to absolute same-path' {
      $configDir = Join-Path $script:TestDir 'config'
      $discoveryDir = Join-Path $script:TestDir 'discovery'
      New-Item -ItemType Directory -Path $configDir -Force | Out-Null
      New-Item -ItemType Directory -Path $discoveryDir -Force | Out-Null
      Push-Location $script:TestDir
      try {
        $r = Invoke-SpiceWrapper -PathManifest (New-RegistryManifest) -Arguments @('registry', 'run', '--config', './config/allspice.toml', '--discovery', './discovery/packages.json')
        $r.ExitCode | Should -Be 0
        $r.ContainerArgs | Should -Contain 'registry'
        $r.ContainerArgs | Should -Contain 'run'
        $r.ContainerArgs | Should -Contain '--config'
        $configFile = Join-Path $configDir 'allspice.toml'
        $dockerConfig = Convert-TestPathToDockerPath $configFile
        $r.ContainerArgs | Should -Contain $dockerConfig
        $r.ContainerArgs | Should -Contain '--discovery'
        $discoveryFile = Join-Path $discoveryDir 'packages.json'
        $dockerDiscovery = Convert-TestPathToDockerPath $discoveryFile
        $r.ContainerArgs | Should -Contain $dockerDiscovery
      } finally { Pop-Location }
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
      $volMount = $r.DockerRunArgs | Where-Object { $_ -match [regex]::Escape($script:InputDir) }
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
        # JVM mode now launches via -cp (so classpath plugins load) + explicit main class,
        # not -jar.
        ($javaArgs -join ' ') | Should -Match '-cp'
        ($javaArgs -join ' ') | Should -Match 'fake\.jar'
        ($javaArgs -join ' ') | Should -Match 'io\.spicelabs\.cli\.SpiceLabsCLI'
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
      ($r.Stderr -join "`n") | Should -Match 'No subject specified'
      ($r.RawOutput -join "`n") | Should -Not -Match 'No subject specified'
    }

    It 'image without JFR settings reports it and cleans up' {
      $outdir = Join-Path (Join-Path $HOME '.spicelabs') "test-rt-nojfc-$PID"
      $env:MOCK_NO_RUNTIME_FILES = '1'
      try {
        $r = Invoke-SpiceWrapper -Arguments @('survey', 'runtime', 'myapp', '--jfr', '--no-upload', '--output', $outdir, '--', 'true')
        $r.ExitCode | Should -Be 1
        ($r.Stderr -join "`n") | Should -Match 'does not support runtime surveys'
        @(Get-ChildItem -Path $outdir -Directory -Filter 'survey-*' -ErrorAction SilentlyContinue).Count | Should -Be 0
      } finally {
        Remove-Item env:MOCK_NO_RUNTIME_FILES -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force $outdir -ErrorAction SilentlyContinue
      }
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
      $cmd = New-TestScript -Name 'dump-jto' -WinBody "Set-Content -Path '$dump' -Value `$env:JAVA_TOOL_OPTIONS" -UnixBody "echo `"`$JAVA_TOOL_OPTIONS`" > `"$dump`""
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

  # ── Configuration-file paths ─────────────────────────────────────────────

  Context 'Configuration-file paths' {
    It 'a path the configuration file names is mounted' {
      $out = Join-Path $script:TestDir 'out'
      $manifest = New-ConfigPathsManifest @((Join-Path $out 'staging'))
      $r = Invoke-SpiceWrapper -PathManifest $manifest -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      # create=parent, like a path option: the directory above the value is created
      # and mounted at its own path, so the CLI can create the value inside it.
      $out | Should -Exist
      $r.DockerRunArgs | Should -Contain "${out}:$(Convert-TestPathToDockerPath $out)"
    }

    It 'a config path under a reserved directory is relocated, with a warning' {
      if ($IsWindows -or -not (Test-Path variable:IsWindows)) {
        Set-ItResult -Skipped -Because 'reserved directories are image paths; the host has no /etc on Windows'
        return
      }
      $manifest = New-ConfigPathsManifest @('/etc/spice-test-staging')
      $r = Invoke-SpiceWrapper -PathManifest $manifest -Arguments @('survey', 'inventory', 'myapp', $script:InputDir)
      $r.ExitCode | Should -Be 0
      $relocated = $r.DockerRunArgs | Where-Object { $_ -match '^/etc:/mnt/spice/\d+$' }
      $relocated | Should -Not -BeNullOrEmpty
      # The value stays inside the config file, where the CLI will read it verbatim,
      # so the user is told it will not resolve where they wrote it.
      $warned = $r.Stderr | Where-Object { $_ -match 'WARN.*/etc/spice-test-staging is mounted at /mnt/spice/' }
      $warned | Should -Not -BeNullOrEmpty
    }

    It 'positional records are not mistaken for config paths' {
      # Both are `P …` lines; only those below the config-paths header are paths. A
      # manifest with positionals and no section must mount nothing extra.
      $manifest = Join-Path $script:TestDir 'positionals.path-manifest'
      @'
# spice-path-manifest 1
V 1
C spice
C spice/survey
C spice/survey/inventory
P spice/survey/inventory 0 value
P spice/survey/inventory 1 value path exists
'@ | Set-Content -LiteralPath $manifest -Encoding ascii
      Push-Location $script:TestDir
      try {
        $r = Invoke-SpiceWrapper -PathManifest $manifest -Arguments @('survey', 'inventory', 'myapp', './input')
        $r.ExitCode | Should -Be 0
        (Join-Path $script:TestDir 'spice') | Should -Not -Exist
        ($r.DockerRunArgs | Where-Object { $_ -match 'spice/survey' }) | Should -BeNullOrEmpty
      } finally { Pop-Location }
    }
  }

  # ── spice docs: the guide in a browser on the host ─────────────────────────
  # In Docker mode the container has no browser, so the wrapper asks it for HTML and opens
  # the page itself. Mock openers (`open` on macOS, `xdg-open` elsewhere) record what they
  # were asked to open. The tests' output is redirected, so only --browser opens anything.
  # On Windows the opener is Start-Process, which would launch the runner's own browser,
  # so the tests that open are skipped there.

  Context 'spice docs' {
    BeforeEach {
      $script:OpenedFile = Join-Path $script:TestDir 'opened.txt'
      $env:OPENED_FILE = $script:OpenedFile
      $env:XDG_CONFIG_HOME = Join-Path $script:TestDir 'xdg'
      $env:DISPLAY = ':0'
      Remove-Item env:WAYLAND_DISPLAY, env:SSH_CONNECTION, env:SSH_TTY, env:OPENER_EXIT -ErrorAction SilentlyContinue
      if ($IsLinux -or $IsMacOS) {
        foreach ($opener in @('open', 'xdg-open')) {
          $path = Join-Path $script:MockBinDir $opener
          Set-Content -Path $path -Value "#!/bin/bash`necho `"`$1`" > `"`$OPENED_FILE`"`nexit `"`${OPENER_EXIT:-0}`"`n"
          chmod +x $path
        }
        $mime = Join-Path $script:MockBinDir 'xdg-mime'
        Set-Content -Path $mime -Value "#!/bin/bash`necho firefox.desktop`n"
        chmod +x $mime
      }
    }

    AfterEach {
      Remove-Item env:OPENED_FILE, env:XDG_CONFIG_HOME, env:DISPLAY, env:SSH_CONNECTION, env:OPENER_EXIT -ErrorAction SilentlyContinue
      if ($IsLinux -or $IsMacOS) {
        foreach ($f in @('open', 'xdg-open', 'xdg-mime')) {
          Remove-Item (Join-Path $script:MockBinDir $f) -ErrorAction SilentlyContinue
        }
      }
    }

    It 'passes through for the container to print when output is redirected' {
      $r = Invoke-SpiceWrapper -Arguments @('docs', 'completion')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'docs'
      $r.ContainerArgs | Should -Contain 'completion'
      $r.ContainerArgs | Should -Not -Contain '--html'
      Test-Path $script:OpenedFile | Should -BeFalse
    }

    It '--browser fetches HTML from the container and opens it on the host' -Skip:(-not ($IsLinux -or $IsMacOS)) {
      $r = Invoke-SpiceWrapper -Arguments @('docs', '--browser', 'completion')
      $r.ExitCode | Should -Be 0
      ($r.RawOutput -join "`n") | Should -Match 'Opened the guide in your browser:'
      $opened = (Get-Content $script:OpenedFile).Trim()
      $opened | Should -Match 'guide\.html$'
      # What the container was asked for is what landed in the file the browser opens.
      $page = Get-Content $opened
      $page | Should -Contain 'ARG:docs'
      $page | Should -Contain 'ARG:completion'
      $page | Should -Contain 'ARG:--html'
      $page | Should -Not -Contain 'ARG:--browser'
    }

    It '--browser over SSH refuses without running the container' {
      $env:SSH_CONNECTION = '10.0.0.1 22 10.0.0.2 22'
      $r = Invoke-SpiceWrapper -Arguments @('docs', '--browser')
      $r.ExitCode | Should -Be 1
      ($r.Stderr -join "`n") | Should -Match 'Cannot open a browser: this is an SSH session'
      $r.DockerRunArgs | Should -BeNullOrEmpty
    }

    It '--browser with an opener that fails is an error' -Skip:(-not ($IsLinux -or $IsMacOS)) {
      $env:OPENER_EXIT = '1'
      $r = Invoke-SpiceWrapper -Arguments @('docs', '--browser')
      $r.ExitCode | Should -Be 1
      ($r.Stderr -join "`n") | Should -Match 'could not open'
    }

    It '--markdown passes through even when a browser is available' {
      $r = Invoke-SpiceWrapper -Arguments @('docs', '--markdown', 'intro')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain '--markdown'
      $r.ContainerArgs | Should -Not -Contain '--html'
      Test-Path $script:OpenedFile | Should -BeFalse
    }

    It "spice's own docs command reaches the container untouched" {
      $r = Invoke-SpiceWrapper -Arguments @('docs', '--json')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain 'docs'
      $r.ContainerArgs | Should -Contain '--json'
      $r.ContainerArgs | Should -Not -Contain '--html'
      Test-Path $script:OpenedFile | Should -BeFalse
    }

    It '--commands reaches the container untouched' {
      $r = Invoke-SpiceWrapper -Arguments @('docs', '--commands')
      $r.ExitCode | Should -Be 0
      $r.ContainerArgs | Should -Contain '--commands'
      $r.ContainerArgs | Should -Not -Contain '--html'
      Test-Path $script:OpenedFile | Should -BeFalse
    }

    It 'when the fetch fails, runs the command as given for the container to answer' -Skip:(-not ($IsLinux -or $IsMacOS)) {
      # Like an image without a guide: `docs --html` fails. Each run is logged on its own line.
      $real = Join-Path $script:MockBinDir 'docker'
      $kept = Join-Path $script:MockBinDir 'docker-image'
      $runs = Join-Path $script:TestDir 'docker-runs.txt'
      Move-Item $real $kept
      Set-Content -Path $real -Value "#!/bin/bash`necho `"`$*`" >> '$runs'`nif [[ `" `$* `" == *`" --html `"* ]]; then echo 'ERROR no guide'; exit 1; fi`nexec '$kept' `"`$@`"`n"
      chmod +x $real
      try {
        $r = Invoke-SpiceWrapper -Arguments @('docs', '--browser', 'nope')
        $lines = @(Get-Content $runs)
        $lines.Count | Should -Be 2
        $lines[0] | Should -Match ' docs nope --html$'
        $lines[1] | Should -Match ' docs nope --browser$'
        Test-Path $script:OpenedFile | Should -BeFalse
      } finally {
        Move-Item $kept $real -Force
      }
    }

    It 'JVM mode leaves the choice to the CLI on the host' {
      $jar = Join-Path $script:TestDir 'fake.jar'
      Set-Content -Path $jar -Value 'fake'
      $env:SPICE_LABS_CLI_USE_JVM = '1'
      $env:SPICE_LABS_CLI_JAR = $jar
      $env:JAVA_ARGS_FILE = $script:JavaArgsFile
      try {
        $r = Invoke-SpiceWrapper -Arguments @('docs', '--browser', 'completion')
        (@(Get-Content $script:JavaArgsFile) -join ' ') | Should -Match 'docs --browser completion'
        Test-Path $script:OpenedFile | Should -BeFalse
      } finally {
        Remove-Item env:SPICE_LABS_CLI_USE_JVM, env:SPICE_LABS_CLI_JAR, env:JAVA_ARGS_FILE -ErrorAction SilentlyContinue
      }
    }
  }
}
