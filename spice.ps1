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
  try {
    return (Resolve-Path -LiteralPath $path -ErrorAction Stop).ProviderPath
  } catch {
    Write-Host "ERROR ❌ Input path does not exist: $path"
    Write-Host "INFO  Use --help for usage information."
    exit 2
  }
}

function Convert-ToDockerPath($path) {
  if ($IsWindows) {
    if ($path -match '^([A-Za-z]):') { $path = $path -replace '^[A-Za-z]:', "/$($matches[1].ToLower())" }
    return $path -replace '\\', '/'
  }
  return $path
}

# Which arguments are host paths is not decided here. It is read from a path
# manifest rendered from the live picocli model inside the image — including
# plugin-contributed commands — so this script needs no knowledge of any specific
# flag. The two regions below are generated; edit shared/path-manifest.ps1 and
# run scripts/update-path-manifest.sh.

# ── BEGIN SHARED path-manifest.ps1 ──
# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Spice Labs, Inc. & Contributors
#
# ─────────────────────────────────────────────────────────────────────────────
# Path-manifest driven argument walking — PowerShell mirror of
# shared/path-manifest.bash. See that file for the design; this one differs only
# where the platform forces it:
#
#   * Windows container paths are not host paths, so an identity mount still
#     rewrites `C:\work` to `/c/work` and records the pair in SPICE_PATH_MAP for
#     PathTranslator to reverse. On Linux/macOS Convert-ToDockerPath is the
#     identity function and no rewriting happens, matching bash exactly.
#   * Hashtables are available, so the lookup tables are real hashtables rather
#     than the delimited strings bash 3.2 forces.
#
# CANONICAL SOURCE: spice-labs-cli/shared/path-manifest.ps1
# Spliced into spice.ps1 by scripts/update-path-manifest.sh; CI checks the copy.
#
# Windows PowerShell 5.1 compatible — no `??`, no ternary, no `-Parallel`.
# ─────────────────────────────────────────────────────────────────────────────

$script:MfRoot = 'spice'
$script:MfCmds = @{}
$script:MfAttrs = @{}
$script:MfInherited = @{}
$script:MfFlat = @{}
$script:MfReservedExact = @{}
$script:MfReservedPrefix = @()

$script:MfArgs = @()
$script:MfVolumes = @()
$script:MfPathMap = @()
$script:MfMountMap = @{}
$script:MfIdentityDirs = @()
$script:MfMountN = 0

# ── Loading ──────────────────────────────────────────────────────────────────

# Resolve a manifest into the tables above. Every tier is best-effort and the
# embedded built-ins manifest is the floor, so this can never abort the CLI.
function Mf-Load {
  $text = ""

  if ($env:SPICE_PATH_MANIFEST -and (Test-Path -LiteralPath $env:SPICE_PATH_MANIFEST)) {
    try { $text = Get-Content -LiteralPath $env:SPICE_PATH_MANIFEST -Raw } catch { $text = "" }
  }
  if (-not $text) { $text = Mf-Refresh }
  if (-not $text) {
    $installed = Join-Path (Mf-DataDir) 'path-manifest'
    if (Test-Path -LiteralPath $installed) {
      try { $text = Get-Content -LiteralPath $installed -Raw } catch { $text = "" }
    }
  }
  if (-not $text) { $text = $MfEmbeddedManifest }

  Mf-Parse $text
}

function Mf-CacheDir {
  if ($env:SPICE_CACHE_DIR) { return (Join-Path $env:SPICE_CACHE_DIR 'path-manifest') }
  if ($IsWindows -and $env:LOCALAPPDATA) {
    return (Join-Path (Join-Path $env:LOCALAPPDATA 'spice') 'path-manifest')
  }
  $base = $env:XDG_CACHE_HOME
  if (-not $base) { $base = Join-Path $HOME '.cache' }
  return (Join-Path (Join-Path $base 'spice') 'path-manifest')
}

function Mf-DataDir {
  if ($IsWindows -and $env:LOCALAPPDATA) { return (Join-Path $env:LOCALAPPDATA 'spice') }
  $base = $env:XDG_DATA_HOME
  if (-not $base) { $base = Join-Path (Join-Path $HOME '.local') 'share' }
  return (Join-Path $base 'spice')
}

# Return the manifest for $imageRef, cached by image ID so a `docker pull`
# invalidates it automatically. Returns "" on any failure.
function Mf-Refresh {
  if ($env:SPICE_SKIP_MANIFEST_REFRESH -eq '1') { return "" }
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return "" }

  $ErrorActionPreference = 'Continue'
  $imageId = (docker image inspect --format '{{.Id}}' "$imageRef" 2>$null | Select-Object -First 1)
  if (-not $imageId) { return "" }

  $cacheDir = Mf-CacheDir
  $cacheFile = Join-Path $cacheDir ($imageId -replace '^sha256:', '')
  if (Test-Path -LiteralPath $cacheFile) {
    try { return (Get-Content -LiteralPath $cacheFile -Raw) } catch { return "" }
  }

  $text = (docker run --rm @pullFlag "$imageRef" path-manifest 2>$null) -join "`n"
  if (-not $text -or -not ($text -match '(?m)^# spice-path-manifest ')) { return "" }

  try {
    if (-not (Test-Path -LiteralPath $cacheDir)) {
      New-Item -ItemType Directory -Path $cacheDir -Force | Out-Null
    }
    Set-Content -LiteralPath $cacheFile -Value $text -NoNewline
    # Keep the cache from growing without bound as images come and go.
    Get-ChildItem -LiteralPath $cacheDir -File |
      Sort-Object LastWriteTime -Descending |
      Select-Object -Skip 10 |
      ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue }
  } catch {
    # An unwritable cache just means the next invocation queries again.
  }
  return $text
}

# Build the lookup tables. Unrecognised lines are ignored rather than rejected,
# so log output leaking onto stdout in the container cannot corrupt them.
function Mf-Parse($text) {
  $script:MfCmds = @{}
  $script:MfAttrs = @{}
  $script:MfInherited = @{}
  $script:MfFlat = @{}
  $script:MfReservedExact = @{}
  $script:MfReservedPrefix = @()

  # Matched anywhere rather than at the start: a warning logged to stdout inside
  # the container would otherwise be enough to reject an otherwise good manifest.
  # `\r?` because the manifest embedded in this script arrives with whatever line
  # endings git checked it out with — CRLF on Windows — and `$` in a .NET regex
  # matches before the `\n`, i.e. after the `\r`.
  if (-not $text -or -not ($text -match '(?m)^# spice-path-manifest 1\r?$')) { return }

  $firstCmd = ""
  foreach ($rawLine in ($text -split "`r?`n")) {
    # Trimmed for the same reason: a trailing \r would otherwise survive into the
    # last field of every record.
    $line = $rawLine.Trim()
    if (-not $line) { continue }
    $f = $line -split '\s+'
    if ($f.Count -lt 2) { continue }
    switch ($f[0]) {
      'C' {
        if (-not $firstCmd) { $firstCmd = $f[1] }
        $script:MfCmds[$f[1]] = $true
      }
      'R'  { $script:MfReservedExact[$f[1]] = $true }
      'RP' { $script:MfReservedPrefix += $f[1] }
      default {
        if ($f[0] -ne 'O' -and $f[0] -ne 'P') { continue }
        if ($f.Count -lt 3) { continue }
        $name = $f[2]
        # Positionals are keyed by index so they cannot collide with a flag.
        if ($f[0] -eq 'P') { $name = "#$($f[2])" }
        $attrs = " " + (($f[3..($f.Count - 1)]) -join ' ') + " "
        $key = "$($f[1])|$name"
        $script:MfAttrs[$key] = $attrs
        if ($attrs -like '* inherit *') { $script:MfInherited[$key] = $attrs }
        if ($f[0] -eq 'O') { Mf-FlatMerge $name $attrs }
      }
    }
  }
  if ($firstCmd) { $script:MfRoot = $firstCmd }
}

# Merge one option's attributes into the flat (command-agnostic) fallback. The
# union is deliberately conservative — `create=parent`, never `exists` — so
# falling back can neither create a directory where a file belongs nor reject a
# path the command would have accepted.
function Mf-FlatMerge($name, $attrs) {
  $existing = ""
  if ($script:MfFlat.ContainsKey($name)) { $existing = $script:MfFlat[$name] }
  $both = $existing + $attrs
  $merged = " "
  if ($both -like '* value *') { $merged += "value " }
  if ($both -like '* path *') { $merged += "path create=parent " }
  if ($both -like '* hostonly *') { $merged += "hostonly " }
  $script:MfFlat[$name] = $merged
}

# ── Lookup ───────────────────────────────────────────────────────────────────

# Attributes of <cmdpath> <name>: the exact command, then any ancestor that
# declares the arg inherited, then the flat union. The flat tier is what makes a
# manifest older than the image degrade instead of mis-parsing the command line.
function Mf-Attrs($cmdpath, $name) {
  $key = "$cmdpath|$name"
  if ($script:MfAttrs.ContainsKey($key)) { return $script:MfAttrs[$key] }
  $ancestor = $cmdpath
  while ($ancestor -match '/') {
    $ancestor = $ancestor -replace '/[^/]*$', ''
    $key = "$ancestor|$name"
    if ($script:MfInherited.ContainsKey($key)) { return $script:MfInherited[$key] }
  }
  if ($script:MfFlat.ContainsKey($name)) { return $script:MfFlat[$name] }
  return $null
}

function Mf-Has($attrs, $attribute) {
  if (-not $attrs) { return $false }
  return ($attrs -like "* $attribute *")
}

function Mf-IsCmd($cmdpath) { return $script:MfCmds.ContainsKey($cmdpath) }

# Whether $token is the leaf name of any command, at any depth. Used where a
# branch scans arguments without tracking its position in the command tree.
function Mf-IsCmdToken($token) {
  foreach ($c in $script:MfCmds.Keys) {
    if ($c -like "*/$token") { return $true }
  }
  return $false
}

function Mf-TakesValue($cmdpath, $name) { return (Mf-Has (Mf-Attrs $cmdpath $name) 'value') }
function Mf-IsHostOnly($cmdpath, $name) { return (Mf-Has (Mf-Attrs $cmdpath $name) 'hostonly') }
function Mf-IsPath($cmdpath, $name) { return (Mf-Has (Mf-Attrs $cmdpath $name) 'path') }

# Whether a directory must not be shadowed by a bind mount. Filesystem roots
# match exactly — /var must be protected while the temp directories beneath it
# stay mountable — whereas install directories match by prefix, since everything
# below /opt/allspice belongs to the image.
function Mf-Reserved($dir) {
  $normalized = ($dir -replace '\\', '/').TrimEnd('/')
  if (-not $normalized) { $normalized = '/' }
  if ($script:MfReservedExact.ContainsKey($normalized)) { return $true }
  foreach ($prefix in $script:MfReservedPrefix) {
    if ($normalized -eq $prefix -or $normalized.StartsWith($prefix + '/')) { return $true }
  }
  return $false
}

function Mf-UnderIdentityMount($dir) {
  foreach ($prefix in $script:MfIdentityDirs) {
    if ($dir.StartsWith($prefix + [IO.Path]::DirectorySeparatorChar) -or
        $dir.StartsWith($prefix + '/')) { return $true }
  }
  return $false
}

# ── Mounting ─────────────────────────────────────────────────────────────────

# Add a deduped bind mount, recording identity mounts so nested paths reuse them
# rather than stacking a redundant mount inside one.
function Mf-Mount($hostDir, $containerDir) {
  if ($script:MfMountMap.ContainsKey($hostDir)) { return }
  $script:MfMountMap[$hostDir] = $containerDir
  $script:MfVolumes += '-v'
  $script:MfVolumes += "${hostDir}:${containerDir}"
  if ($containerDir -eq (Convert-ToDockerPath $hostDir)) { $script:MfIdentityDirs += $hostDir }
}

# Resolve one path argument for use inside the container, adding whatever bind
# mount it needs, and return the container-side path.
function Mount-Path($value, $create, $mustExist) {
  if ($value -eq '~') { $value = $HOME }
  elseif ($value -like '~/*' -or $value -like '~\*') {
    $value = Join-Path $HOME ($value -replace '^~[/\\]')
  }

  # A URL or a bare key=value is not a host path even on a Path-typed option.
  if ($value -match '://') { return $value }

  $isDir = Test-Path -LiteralPath $value -PathType Container
  if (Test-Path -LiteralPath $value) {
    if ($isDir) { $dir = $value } else { $dir = Split-Path -Parent $value }
  } elseif ($mustExist) {
    Write-Host "ERROR ❌ Input path does not exist: $value"
    Write-Host "INFO  Use --help for usage information."
    exit 2
  } elseif ($create -eq 'self') {
    try { New-Item -ItemType Directory -Path $value -Force | Out-Null } catch { return $value }
    $dir = $value
    $isDir = $true
  } else {
    $dir = Split-Path -Parent $value
    if (-not $dir) { $dir = "." }
    try { New-Item -ItemType Directory -Path $dir -Force | Out-Null } catch { return $value }
  }
  if (-not $dir) { $dir = "." }

  # If the directory still isn't there, the value was probably never a path.
  # Pass it through untouched and let the CLI produce the real diagnostic.
  if (-not (Test-Path -LiteralPath $dir -PathType Container)) { return $value }
  try { $dirAbs = (Resolve-Path -LiteralPath $dir -ErrorAction Stop).ProviderPath } catch { return $value }
  $dirAbs = $dirAbs.TrimEnd([IO.Path]::DirectorySeparatorChar)
  if ($isDir) { $abs = $dirAbs } else { $abs = Join-Path $dirAbs (Split-Path -Leaf $value) }

  $target = $null
  if ($script:MfMountMap.ContainsKey($dirAbs)) {
    $target = $script:MfMountMap[$dirAbs]
  } elseif (Mf-UnderIdentityMount $dirAbs) {
    # Already visible through an ancestor's identity mount.
    $target = Convert-ToDockerPath $dirAbs
  } else {
    if (Mf-Reserved (Convert-ToDockerPath $dirAbs)) {
      # Mounting here would hide part of the image, so relocate and record the
      # mapping for PathTranslator to reverse in user-facing messages.
      $script:MfMountN++
      $target = "/mnt/spice/$($script:MfMountN)"
      $script:MfPathMap += "${target}:${dirAbs}"
    } else {
      $target = Convert-ToDockerPath $dirAbs
      if ($target -ne $dirAbs) { $script:MfPathMap += "${target}:${dirAbs}" }
    }
    Mf-Mount $dirAbs $target
  }

  if ($abs -eq $dirAbs) { return $target }
  return ($target.TrimEnd('/') + '/' + (Split-Path -Leaf $value))
}

# ── Argument walking ─────────────────────────────────────────────────────────

# Rewrite $argList into $script:MfArgs, mounting every argument the manifest
# calls a path.
function Walk-Args($argList) {
  $cmdpath = $script:MfRoot
  $posidx = 0
  $pending = ""
  $endOpts = $false
  $script:MfArgs = @()

  foreach ($arg in $argList) {
    if ($endOpts) { $script:MfArgs += $arg; continue }

    # The value of a flag seen on the previous iteration.
    if ($pending) {
      if (Mf-IsHostOnly $cmdpath $pending) {
        # the wrapper handles it; strip both tokens
      } elseif (Mf-IsPath $cmdpath $pending) {
        $a = Mf-Attrs $cmdpath $pending
        $script:MfArgs += $pending
        $script:MfArgs += (Mount-Path $arg (Mf-CreateMode $a) (Mf-Has $a 'exists'))
      } else {
        $script:MfArgs += $pending
        $script:MfArgs += $arg
      }
      $pending = ""
      continue
    }

    if ($arg -eq '--') { $endOpts = $true; $script:MfArgs += $arg; continue }

    if ($arg -like '-*') {
      if ($arg -match '^([^=]+)=(.*)$') {
        $flag = $matches[1]
        $val = $matches[2]
        if (Mf-IsHostOnly $cmdpath $flag) {
          continue
        } elseif (Mf-IsPath $cmdpath $flag) {
          $a = Mf-Attrs $cmdpath $flag
          $script:MfArgs += "$flag=$(Mount-Path $val (Mf-CreateMode $a) (Mf-Has $a 'exists'))"
        } else {
          $script:MfArgs += $arg
        }
      } elseif (Mf-TakesValue $cmdpath $arg) {
        $pending = $arg
      } elseif (Mf-IsHostOnly $cmdpath $arg) {
        # a valueless host-only flag
      } else {
        $script:MfArgs += $arg
      }
      continue
    }

    # A positional token descends into a subcommand only before any positional
    # has been consumed here, so a subject named `run` is still a subject.
    if ($posidx -eq 0 -and (Mf-IsCmd "$cmdpath/$arg")) {
      $cmdpath = "$cmdpath/$arg"
      $script:MfArgs += $arg
      continue
    }

    $a = Mf-Attrs $cmdpath "#$posidx"
    if (Mf-Has $a 'path') {
      $script:MfArgs += (Mount-Path $arg (Mf-CreateMode $a) (Mf-Has $a 'exists'))
    } else {
      $script:MfArgs += $arg
    }
    $posidx++
  }

  # A trailing flag with no value: pass it through so picocli reports it.
  if ($pending) { $script:MfArgs += $pending }
}

function Mf-CreateMode($attrs) {
  if (Mf-Has $attrs 'create=self') { return 'self' }
  return 'parent'
}
# ── END SHARED path-manifest.ps1 ──

# ── BEGIN GENERATED PATH MANIFEST ──
$MfEmbeddedManifest = @'
# spice-path-manifest 1
V 1
G unknown
R /
R /bin
R /boot
R /dev
R /etc
R /lib
R /lib32
R /lib64
R /libx32
R /opt
R /proc
R /root
R /run
R /sbin
R /srv
R /sys
R /usr
R /var
C spice
O spice --config value path create=parent
O spice -h flag
O spice --help flag
O spice -V flag
O spice --version flag
C spice/survey
O spice/survey -h flag
O spice/survey --help flag
O spice/survey -V flag
O spice/survey --version flag
C spice/survey/inventory
O spice/survey/inventory --output value path create=self
O spice/survey/inventory --no-upload flag
O spice/survey/inventory --upload-only flag
O spice/survey/inventory --tag-json value
O spice/survey/inventory --threads value
O spice/survey/inventory --max-records value
O spice/survey/inventory --chunk-size value
O spice/survey/inventory --log-level value
O spice/survey/inventory --log-file value hostonly
O spice/survey/inventory --analysis-args value
O spice/survey/inventory --upload-args value
O spice/survey/inventory -h flag
O spice/survey/inventory --help flag
O spice/survey/inventory -V flag
O spice/survey/inventory --version flag
P spice/survey/inventory 0 value
P spice/survey/inventory 1 value path create=self exists
C spice/survey/runtime
O spice/survey/runtime --jfr flag
O spice/survey/runtime --native-only flag
O spice/survey/runtime --keep-recording flag
O spice/survey/runtime --no-upload flag
O spice/survey/runtime --output value path create=self
O spice/survey/runtime --log-level value
O spice/survey/runtime --chunk-size value
O spice/survey/runtime --anchor value path create=parent
O spice/survey/runtime -h flag
O spice/survey/runtime --help flag
O spice/survey/runtime -V flag
O spice/survey/runtime --version flag
P spice/survey/runtime 0 value
C spice/pass
O spice/pass -h flag
O spice/pass --help flag
O spice/pass -V flag
O spice/pass --version flag
C spice/pass/decode
O spice/pass/decode -h flag
O spice/pass/decode --help flag
O spice/pass/decode -V flag
O spice/pass/decode --version flag
C spice/generate-completion
O spice/generate-completion -h flag
O spice/generate-completion --help flag
O spice/generate-completion -V flag
O spice/generate-completion --version flag
C spice/generate-powershell-completion
C spice/path-manifest
C spice/config
O spice/config -h flag
O spice/config --help flag
O spice/config -V flag
O spice/config --version flag
C spice/config/explain
O spice/config/explain --group value
O spice/config/explain -h flag
O spice/config/explain --help flag
O spice/config/explain -V flag
O spice/config/explain --version flag
P spice/config/explain 0 value
'@
# ── END GENERATED PATH MANIFEST ──

$jar = if ($env:SPICE_LABS_CLI_JAR) { $env:SPICE_LABS_CLI_JAR } else { "/opt/spice-labs-cli/spice-labs-cli.jar" }
$img = if ($env:SPICE_IMAGE) { $env:SPICE_IMAGE } else { "spicelabs/spice-labs-cli" }
$tag = if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" }
# If SPICE_IMAGE is set, use it verbatim (no tag appended). Otherwise build
# the ref from the resolved image and tag.
$imageRef = if ($env:SPICE_IMAGE) { $env:SPICE_IMAGE } else { "${img}:${tag}" }

# ── Feature flag parsing (must happen before Docker checks / image pull) ──────
#
# --features enterprise → enterprise image (allspice + sassafras)
# --features federal     → federal image (enterprise + report_cli + rogues gallery)
# Strip the flag so it is not forwarded to the CLI container.

$features = ""
$parsedArgs = @()
$prevArg = ""
foreach ($arg in $args) {
  if ($arg -match '^--features=(.*)$') {
    $features = $matches[1]
    continue
  } elseif ($prevArg -eq '--features') {
    $features = $arg
    $prevArg = ""
    continue
  } elseif ($arg -eq '--features') {
    $prevArg = $arg
    continue
  }
  $parsedArgs += $arg
}
if (-not $env:SPICE_IMAGE) {
  switch ($features) {
    "enterprise" { $imageRef = "ghcr.io/spice-labs-inc/spice-labs-cli-enterprise:latest" }
    "federal"    { $imageRef = "ghcr.io/spice-labs-inc/spice-labs-cli-federal:latest" }
  }
}
$args = $parsedArgs

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
  # Plugins (e.g. the `registry` command) ride beside the jar in plugins/; add them to
  # the classpath. -cp (not -jar) requires naming the main class explicitly.
  $pluginsDir = Join-Path (Split-Path $jar -Parent) 'plugins'
  $cp = if (Test-Path $pluginsDir) { "$jar$([IO.Path]::PathSeparator)$pluginsDir/*" } else { $jar }
  & java $jvmArgs -cp $cp io.spicelabs.cli.SpiceLabsCLI @filtered
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
    if ($debugMode) { docker pull "$imageRef" }
    else { docker pull --quiet "$imageRef" | Out-Null }
  } catch {
    Write-Warning "[!] Failed to pull $imageRef"
    $localExists = $false
    try { docker image inspect "$imageRef" | Out-Null; $localExists = $true } catch {}
    if (-not $localExists) {
      Write-Error "[X] Image $imageRef not found locally either."
      Write-Host "   The image may not exist yet, or you may not have access."
      Write-Host "   For enterprise/federal features, ensure --features matches an available image."
      exit 1
    }
    Write-Host "   Using local copy."
  }
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

# ── Path manifest ────────────────────────────────────────────────────────────
# Load before any argument walking: everything below asks the manifest which
# arguments are paths rather than deciding for itself.

Mf-Load

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
  $rtAnchor = ""
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

    # Capture --anchor value (the host jar this survey is of; mounted into the
    # collect container below so its gitoid can be hashed for CBOM correlation)
    if ($arg -match '^--anchor=(.*)$') { $rtAnchor = $matches[1]; continue }
    elseif ($rtPrev -eq '--anchor') { $rtAnchor = $arg; $rtPrev = ""; continue }
    elseif ($arg -eq '--anchor') { $rtPrev = $arg; continue }

    # Handle value-consuming flags
    if ($rtPrev) {
      $rtCliArgs += $rtPrev; $rtCliArgs += $arg; $rtPrev = ""; continue
    }

    if ($arg -like '-*') {
      if ($arg -eq '--no-upload') { $rtNoUpload = $true }
      if ($arg -eq '--native-only') { $rtNativeOnly = $true }
      if ($arg -eq '--keep-recording') { $rtKeepRecording = $true }
      if (Mf-TakesValue "$($script:MfRoot)/survey/runtime" $arg) { $rtPrev = $arg }
      else { $rtCliArgs += $arg }
      continue
    }

    # Positional: subcommands pass through, first non-subcommand is subject
    if (Mf-IsCmdToken $arg) {
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
  $p1Args += @("$imageRef")
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
    $dlArgs += @("$imageRef")
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

  $rtAnchorMount = @()
  if ($rtAnchor) {
    # Mounted like any other path argument, so --anchor stops being special-cased.
    $rtAnchorDocker = Mount-Path $rtAnchor 'parent' $true
    $rtAnchorMount = $script:MfVolumes
    $rtCollectArgs += @('--anchor', $rtAnchorDocker)
  }

  $p4Args = @('run', '--rm', '--entrypoint', 'java')
  $p4Args += @($userFlag)
  $p4Args += @('--network', 'host')
  $p4Args += @($pullFlag)
  $p4Args += @('-v', "${rtWorkdirHost}:${rtWorkdirDocker}")
  $p4Args += $rtAnchorMount
  $p4Args += @('-e', "SPICE_PASS=$spicePass")
  $p4Args += @("$imageRef")
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

# ── Argument walking and mounts ──────────────────────────────────────────────
#
# One pass for every command, built-in or plugin-contributed. Each argument the
# manifest calls a path is absolutised and bind-mounted at its own host path, so
# the container sees it where the user typed it (modulo the Windows drive-letter
# translation) and nothing needs rewriting — except a path under a reserved
# directory, which is remapped and recorded in SPICE_PATH_MAP for the CLI to
# reverse in its messages.

# ── Configuration file ───────────────────────────────────────────────────────
#
# Discovery runs HERE, on the host, not in the container: the container is given
# neither HOME nor XDG_*, so `spice` running inside it would look in the
# container's home rather than the user's. Resolving here and passing the result
# as --config also means the file is bind-mounted like any other path argument.
#
# Windows-native locations, not XDG: %APPDATA% (per-user, roaming) then
# %PROGRAMDATA% (machine-wide). Roaming rather than %LOCALAPPDATA% because
# configuration is user intent and should follow the user between machines,
# unlike the manifest cache above. First match wins.
function Get-SpiceConfigFile {
  foreach ($root in @($env:APPDATA, $env:PROGRAMDATA)) {
    if ($root) {
      $candidate = Join-Path (Join-Path $root 'spice') 'config.toml'
      if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
  }
  return $null
}

# Only when the user did not name one themselves.
$hasConfigArg = $false
foreach ($a in $args) {
  if ($a -eq '--config' -or ($a -is [string] -and $a.StartsWith('--config='))) {
    $hasConfigArg = $true
    break
  }
}
if (-not $hasConfigArg) {
  $spiceConfigFile = Get-SpiceConfigFile
  # Before the subcommand: --config is spice's own option, not inherited.
  if ($spiceConfigFile) { $args = @('--config', $spiceConfigFile) + @($args) }
}

Walk-Args $args

# Always make the working directory visible, so relative paths the CLI resolves
# itself (and its default output location) land on the host.
$cwd = (Get-Location).Path
$cwdDocker = Convert-ToDockerPath $cwd
if (-not (Mf-Reserved $cwdDocker) -and -not (Mf-UnderIdentityMount $cwd)) {
  Mf-Mount $cwd $cwdDocker
  if ($cwdDocker -ne $cwd) { $script:MfPathMap += "${cwdDocker}:${cwd}" }
}
$workdirFlag = @()
if (-not (Mf-Reserved $cwdDocker)) { $workdirFlag = @('-w', $cwdDocker) }

$dockerArgs = $script:MfArgs
$volumes = $script:MfVolumes
$pathMap = $script:MfPathMap

# ── Run ──────────────────────────────────────────────────────────────────────

# Serialize path map into a newline-separated string for the container
$env:SPICE_PATH_MAP = ($pathMap -join "`n")

$envArgs = @()
if ($env:SPICE_LABS_JVM_ARGS) { $envArgs += "-e"; $envArgs += "SPICE_LABS_JVM_ARGS" }
$envArgs += "-e"; $envArgs += "SPICE_PATH_MAP"

$dockerFlags = @()
if ($env:SPICE_DOCKER_FLAGS) { $dockerFlags = $env:SPICE_DOCKER_FLAGS -split '\s+' }

# 'Continue' prevents docker stderr from becoming a terminating error
$ErrorActionPreference = 'Continue'
docker run --rm `
  @userFlag `
  @pullFlag @dockerFlags `
  --network host `
  @volumes `
  @workdirFlag `
  -e "SPICE_PASS=$spicePass" `
  @envArgs `
   "$imageRef" `
   @dockerArgs
exit $LASTEXITCODE
