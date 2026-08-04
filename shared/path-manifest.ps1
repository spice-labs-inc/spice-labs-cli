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
