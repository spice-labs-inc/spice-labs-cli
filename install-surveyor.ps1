# To install : `irm -UseBasicParsing -Uri https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/install-surveyor.ps1 | iex`
#
# The PowerShell counterpart of install-surveyor.sh. Interactive when there is a
# console to ask on: asks where to install, for a credential, and which
# PowerShell profiles to set up. Docker must be installed and running. A Spice
# License selects its edition's image, which is pulled through the Spice Labs
# registry (SPICE_REGISTRY overrides it) after logging in to it; otherwise the
# image is spicelabs/spice-labs-cli, from Docker Hub.
#
# Credentials are saved in the same file, and in the same form, as by
# install-surveyor.sh: on Windows in %APPDATA%\spice\credentials, elsewhere in
# ~/.config/spice/credentials. Profiles read it without running it.
#
# Options, which `iex` can't pass, so also as environment variables:
#
#   & ([scriptblock]::Create((irm -UseBasicParsing -Uri <url>))) -Yes -Dir C:\Tools
#
#   -Yes              accept the defaults without asking (SPICE_INSTALL_NONINTERACTIVE=1)
#   -Dir DIR          install spice into DIR (SPICE_INSTALL_DIR)
#   -NoModifyProfile  leave PowerShell profiles, and on Windows the user PATH,
#                     untouched (SPICE_INSTALL_NO_MODIFY_PROFILE=1)
#
# Changes are made as a transaction: everything is downloaded, generated and
# written beside its destination first, then moved into place, and if anything
# fails or is interrupted before the last move, every change already made is
# undone. Nothing here calls `exit`, which would close the window `iex` runs in.

param([switch]$Yes, [string]$Dir = "", [switch]$NoModifyProfile)

function Install-SpiceSurveyor {
  param([switch]$Yes, [string]$Dir = "", [switch]$NoModifyProfile)

  $ErrorActionPreference = 'Stop'
  if (-not (Test-Path variable:IsWindows)) { $IsWindows = $true }

  # ── Locations ──────────────────────────────────────────────────────────────

  $ScriptUrl = "https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice.ps1"
  $DefaultImage = "spicelabs/spice-labs-cli:" + $(if ($env:SPICE_IMAGE_TAG) { $env:SPICE_IMAGE_TAG } else { "latest" })
  # The registry (bael) that the licensed editions' images are pulled through
  $Registry = if ($env:SPICE_REGISTRY) { $env:SPICE_REGISTRY } else { "surveyor.spicelabs.io" }
  # The registry can't yet accept a Spice License or Spice Pass, so for now the
  # login is the user "download", with the password in SPICE_PASSWORD. Once it
  # can, log in with the credential itself instead, and drop SPICE_PASSWORD.
  $LoginUser = "download"

  $UserHome = if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }
  $Sep = [IO.Path]::PathSeparator
  $DefaultDir = Join-Path (Join-Path $UserHome '.spice') 'bin'
  $CompletionDir = Join-Path (Join-Path $UserHome '.spice') 'completions'
  $CompletionPath = Join-Path $CompletionDir 'spice-completion.ps1'
  if ($IsWindows) {
    $ConfigDir = Join-Path $env:APPDATA 'spice'
    $DataDir = Join-Path $env:LOCALAPPDATA 'spice'
  } else {
    $xdgConfig = if ($env:XDG_CONFIG_HOME) { $env:XDG_CONFIG_HOME } else { Join-Path $HOME '.config' }
    $xdgData = if ($env:XDG_DATA_HOME) { $env:XDG_DATA_HOME } else { Join-Path (Join-Path $HOME '.local') 'share' }
    $ConfigDir = Join-Path $xdgConfig 'spice'
    $DataDir = Join-Path $xdgData 'spice'
  }
  $CredentialsFile = Join-Path $ConfigDir 'credentials'
  $EnvironmentFile = Join-Path $ConfigDir 'environment'
  $ManifestFile = Join-Path $DataDir 'path-manifest'
  $dockerDir = if ($env:DOCKER_CONFIG) { $env:DOCKER_CONFIG } else { Join-Path $UserHome '.docker' }
  $DockerConfigFile = Join-Path $dockerDir 'config.json'
  $BlockBegin = "# >>> spice >>>"
  $BlockEnd = "# <<< spice <<<"
  $Legacy = "# Spice CLI tab completion"
  $ESC = [char]27

  # ── Output ─────────────────────────────────────────────────────────────────

  # Symbols are built from code points, so that every string here is ASCII.
  # Windows PowerShell reads a script without a BOM in the ANSI code page, where
  # some bytes of UTF-8 emoji are curly quotes, which end strings; and `irm`
  # decodes one served without a charset as ISO-8859-1.
  function U([int[]]$CodePoints) { ($CodePoints | ForEach-Object { [char]::ConvertFromUtf32($_) }) -join '' }
  $Bullet = U 0x2022
  $IconBulb = U 0x1F4A1
  $IconCheck = U 0x2705
  $IconCross = U 0x274C
  $IconFolder = U 0x1F4C1
  $IconKey = U 0x1F511
  $IconLock = U 0x1F510
  $IconMemo = U 0x1F4DD
  $IconPackage = U 0x1F4E6
  $IconUndo = U 0x21A9, 0xFE0F
  $IconWarn = U 0x26A0, 0xFE0F


  # Paths, commands and variable names are shown in cyan italics on a terminal
  $styled = $Host.UI.SupportsVirtualTerminal -and -not [Console]::IsOutputRedirected `
    -and -not $env:NO_COLOR -and $env:TERM -ne 'dumb'

  function C([string]$Text) {
    if ($styled) { "$ESC[3;36m$Text$ESC[23;39m" } else { $Text }
  }

  # Shows a path with the home directory abbreviated to ~
  function P([string]$Path) {
    if ($Path.StartsWith($UserHome + [IO.Path]::DirectorySeparatorChar)) {
      C ("~" + $Path.Substring($UserHome.Length))
    } else {
      C $Path
    }
  }

  function Say([string]$Text = "") { Write-Host $Text }

  # Stops the installation; the message has been shown, and rollback follows
  function Die([string]$Text) {
    Write-Host "$($IconCross) $Text"
    throw (New-Object System.OperationCanceledException "spice-install")
  }

  # Runs a native command with stderr captured, or with -Quiet discarded, rather
  # than turned into errors, which Windows PowerShell does under Stop
  function Invoke-Native([string]$Command, [string[]]$Arguments, [string]$InputText = $null, [switch]$Quiet) {
    $ErrorActionPreference = 'Continue'
    if ($Quiet) {
      $out = & $Command @Arguments 2>$null
    } elseif ($null -ne $InputText) {
      $out = $InputText | & $Command @Arguments 2>&1
    } else {
      $out = & $Command @Arguments 2>&1
    }
    @{ Code = $LASTEXITCODE; Output = (($out | ForEach-Object { "$_" }) -join "`n").Trim() }
  }

  # ── Transaction ────────────────────────────────────────────────────────────
  #
  # The journal records each committed change, oldest first, with what undoes
  # it: a directory created, a file replaced or created (with its backup), a
  # registry login, or the user PATH as it was. A registry login is undone by
  # logging out, but only if there was no login for that registry before:
  # Docker can't give back a login it has replaced. A pulled image isn't
  # removed, as it's only a cache, and removing it would just mean downloading
  # it again on the next attempt.

  $journal = New-Object System.Collections.ArrayList
  $prepared = New-Object System.Collections.ArrayList
  $stage = $null

  # File.Replace gets [NullString]::Value for "no backup", because PowerShell
  # passes $null to a .NET string parameter as an empty string
  function Restore-File($Target, $Backup) {
    $item = Get-Item -LiteralPath $Target -Force -ErrorAction SilentlyContinue
    if ($item -and $item.LinkType) {
      [IO.File]::WriteAllBytes($Target, [IO.File]::ReadAllBytes($Backup))
      Remove-Item -LiteralPath $Backup -Force
    } elseif ($item) {
      [IO.File]::Replace($Backup, $Target, [NullString]::Value)
    } else {
      [IO.File]::Move($Backup, $Target)
    }
  }

  function Undo-Journal {
    if ($journal.Count -eq 0) { return }
    Write-Host "$($IconUndo)  Installation failed; undoing the changes made so far."
    for ($i = $journal.Count - 1; $i -ge 0; $i--) {
      $entry = $journal[$i]
      try {
        switch ($entry.Kind) {
          'dir' {
            if (-not (Get-ChildItem -LiteralPath $entry.Target -Force)) { Remove-Item -LiteralPath $entry.Target -Force }
          }
          'file' {
            if ($entry.Backup) { Restore-File $entry.Target $entry.Backup }
            else { Remove-Item -LiteralPath $entry.Target -Force -ErrorAction SilentlyContinue }
          }
          'login' { $null = Invoke-Native docker @('logout', $entry.Target) }
          'userpath' { Set-UserPath $entry.Value }
        }
      } catch {
        Write-Host "$($IconWarn)  Couldn't undo a change to $(C $entry.Target): $_"
      }
    }
    $journal.Clear()
  }

  # Creates a directory and any missing parents, journalling each
  function New-Dir([string]$Path) {
    $missing = @()
    $d = $Path
    while ($d -and -not (Test-Path -LiteralPath $d)) {
      $missing = @($d) + $missing
      $d = Split-Path -Parent $d
    }
    $null = New-Item -ItemType Directory -Force -Path $Path
    foreach ($m in $missing) { [void]$journal.Add(@{ Kind = 'dir'; Target = $m }) }
  }

  # Copies SOURCE beside TARGET, ready to be moved into place by Complete-File.
  # A private file is made readable only by its owner before it has any content.
  function Write-Prepared([string]$Source, [string]$Target, [switch]$Private) {
    $new = "$Target.spice-new.$PID"
    [void]$prepared.Add($new)
    if ($Private -and -not $IsWindows) {
      $null = New-Item -ItemType File -Force -Path $new
      $null = Invoke-Native chmod @('600', $new)
    }
    [IO.File]::WriteAllBytes($new, [IO.File]::ReadAllBytes($Source))
  }

  # Moves the file prepared for TARGET into place, keeping a backup of the old one
  function Complete-File([string]$Target) {
    $new = "$Target.spice-new.$PID"
    $backup = $null
    $item = Get-Item -LiteralPath $Target -Force -ErrorAction SilentlyContinue
    if ($item) {
      $backup = "$Target.spice-old.$PID"
      [IO.File]::WriteAllBytes($backup, [IO.File]::ReadAllBytes($Target))
    }
    $entry = @{ Kind = 'file'; Target = $Target; Backup = $backup }
    if ($item -and $item.LinkType) {
      # Write through symlinks, so files managed by a dotfiles repo stay managed.
      # This isn't atomic, so it's journalled first: rollback restores the
      # file, however far the write got.
      [void]$journal.Add($entry)
      [IO.File]::WriteAllBytes($Target, [IO.File]::ReadAllBytes($new))
      Remove-Item -LiteralPath $new -Force
      return
    }
    # A move either happens or doesn't, so it's journalled only once it has
    try {
      if ($item) { [IO.File]::Replace($new, $Target, [NullString]::Value) }
      else { [IO.File]::Move($new, $Target) }
    } catch {
      if ($backup) { Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue }
      throw
    }
    [void]$journal.Add($entry)
  }

  # Removes TARGET, keeping a backup so rollback can restore it
  function Remove-Journalled([string]$Target) {
    $backup = "$Target.spice-old.$PID"
    [IO.File]::WriteAllBytes($backup, [IO.File]::ReadAllBytes($Target))
    [void]$journal.Add(@{ Kind = 'file'; Target = $Target; Backup = $backup })
    Remove-Item -LiteralPath $Target -Force
  }

  # Ends the transaction: the changes are kept, and the backups are not needed
  function Complete-Journal {
    foreach ($entry in $journal) {
      if ($entry.Kind -eq 'file' -and $entry.Backup) { Remove-Item -LiteralPath $entry.Backup -Force -ErrorAction SilentlyContinue }
    }
    $journal.Clear()
  }

  # ── User PATH (Windows) ────────────────────────────────────────────────────
  #
  # Read and written in the registry directly, as .NET would expand references
  # like %USERPROFILE% in it, and write them back expanded.

  function Get-UserPath {
    $key = Get-Item -LiteralPath 'HKCU:\Environment'
    @{
      Value = $key.GetValue('Path', '', 'DoNotExpandEnvironmentNames')
      Kind = $(if ($key.GetValueNames() -contains 'Path') { $key.GetValueKind('Path') } else { 'ExpandString' })
    }
  }

  function Set-UserPath($Saved) {
    Set-ItemProperty -LiteralPath 'HKCU:\Environment' -Name Path -Value $Saved.Value -Type $Saved.Kind
    # Setting any user variable through .NET tells running programs to reread
    # the environment, as Explorer must for new windows to see the change
    [Environment]::SetEnvironmentVariable('SPICE_INSTALL_REFRESH', '1', 'User')
    [Environment]::SetEnvironmentVariable('SPICE_INSTALL_REFRESH', $null, 'User')
  }

  function Test-OnPath([string]$Path) {
    @($env:PATH -split [regex]::Escape($Sep)) -contains $Path
  }

  # ── Prompts ────────────────────────────────────────────────────────────────

  function Ask([string]$Prompt, [string]$Default) {
    Write-Host $Prompt -NoNewline
    $reply = Read-Host
    if ($reply) { $reply.Trim() } else { $Default }
  }

  function Confirm([string]$Prompt, [bool]$Default) {
    while ($true) {
      $hint = if ($Default) { "[Y/n]" } else { "[y/N]" }
      $reply = Ask "$Prompt $hint " $(if ($Default) { 'y' } else { 'n' })
      if ($reply -match '^(y|yes)$') { return $true }
      if ($reply -match '^(n|no)$') { return $false }
    }
  }

  function Read-Password([string]$Prompt) {
    Write-Host $Prompt -NoNewline
    $secure = Read-Host -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  }

  # ── Credential ─────────────────────────────────────────────────────────────
  #
  # A Spice License and a Spice Pass are both JWTs. Their claims are read here
  # without verifying the signature, only to describe the credential and to
  # choose the image for its edition: spice verifies a License when it runs.

  # The value of VAR in a file of `export VAR='value'` lines, if it's there
  function Get-SavedValue([string]$File, [string]$Var) {
    if (-not (Test-Path -LiteralPath $File)) { return "" }
    $value = ""
    foreach ($line in [IO.File]::ReadAllLines($File)) {
      if ($line -match "^export $Var='(.*)'$") { $value = $Matches[1] }
    }
    $value
  }

  function ConvertFrom-Base64Url([string]$Text) {
    $b64 = $Text.Replace('-', '+').Replace('_', '/')
    switch ($b64.Length % 4) { 2 { $b64 += '==' } 3 { $b64 += '=' } }
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
  }

  function Get-CredentialInfo([string]$Token) {
    $info = @{ Kind = 'unknown'; Edition = ''; Expires = $null }
    $parts = $Token.Split('.')
    if ($parts.Count -ne 3) { return $info }
    try {
      $header = ConvertFrom-Json (ConvertFrom-Base64Url $parts[0])
      $payload = ConvertFrom-Json (ConvertFrom-Base64Url $parts[1])
    } catch {
      return $info
    }
    # The same markers spice checks: a License's typ header and x-type claim
    $type = [string]$payload.'x-type'
    if ([string]$header.typ -eq 'spice-license+jwt' -or $type -eq 'license') { $info.Kind = 'license' }
    elseif ($type) { $info.Kind = 'pass' }
    $info.Edition = [string]$payload.'x-edition'
    if ($payload.exp -is [ValueType]) { $info.Expires = [long]$payload.exp }
    $info
  }

  function Test-Expired($Info) {
    $null -ne $Info.Expires -and $Info.Expires -lt [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  }

  # The editions, as named in surveyor's editions.json
  function Get-EditionTitle([string]$Edition) {
    switch ($Edition) {
      'cli' { 'Surveyor CLI' }
      'otpro' { 'Surveyor OT Pro' }
      'it' { 'Surveyor IT' }
      'itpro' { 'Surveyor IT Pro' }
      default { $Edition }
    }
  }

  # The image for an edition, pulled through the registry; nothing for the default
  function Get-EditionImage([string]$Edition) {
    if (@('otpro', 'it', 'itpro') -contains $Edition) { "$Registry/spice-labs-inc/surveyor-${Edition}:latest" } else { "" }
  }

  # Describes a credential, e.g. "a Spice License for Surveyor IT Pro, valid
  # until 1 January 2028"
  function Get-CredentialDescription($Info) {
    switch ($Info.Kind) {
      'license' {
        $d = "a Spice License"
        if ($Info.Edition) { $d += " for $(Get-EditionTitle $Info.Edition)" }
      }
      'pass' { $d = "a Spice Pass" }
      default { return "a credential of an unknown form" }
    }
    if ($null -ne $Info.Expires) {
      $date = [DateTimeOffset]::FromUnixTimeSeconds($Info.Expires).UtcDateTime.ToString(
        'd MMMM yyyy', [Globalization.CultureInfo]::InvariantCulture)
      if (Test-Expired $Info) { $d += ", which expired on $date" } else { $d += ", valid until $date" }
    }
    $d
  }

  $cred = @{ Var = ""; Value = "" }

  function Select-Credential {
    Say ""
    Say "$($IconKey) $(C spice) needs a Spice License or a Spice Pass to run."
    $have = $false
    foreach ($var in @('SPICE_LICENSE', 'SPICE_PASS')) {
      $value = [Environment]::GetEnvironmentVariable($var)
      if ($value) {
        Say "   $(C $var) is set in this shell: $(Get-CredentialDescription (Get-CredentialInfo $value))."
        $have = $true
      }
      $value = Get-SavedValue $CredentialsFile $var
      if ($value) {
        Say "   $(C $var) is saved in $(P $CredentialsFile): $(Get-CredentialDescription (Get-CredentialInfo $value))."
        $have = $true
      }
    }
    $skip = if ($have) { "keep it" } else { "skip" }
    Say ""
    Say "Paste your Spice License or Spice Pass, or the path to a file containing it."

    while ($true) {
      $cred.Var = ""
      $cred.Value = ""
      $entered = Ask "Credential [Enter to $skip]: " ""
      if ($entered.StartsWith('~')) { $entered = $UserHome + $entered.Substring(1) }
      $isFile = $false
      try { $isFile = $entered -and (Test-Path -LiteralPath $entered -PathType Leaf) } catch { $isFile = $false }
      if ($isFile) { $entered = [IO.File]::ReadAllText($entered) }
      $value = $entered -replace '\s', ''
      if (-not $value) { return }
      # The credentials file must parse the same way everywhere it's read, so
      # the value is limited to the characters a token can contain.
      if ($value -notmatch '^[A-Za-z0-9._~+/=-]+$') {
        Say "   That isn't a Spice License or a Spice Pass. Try again, or press Enter to $skip."
        continue
      }

      $info = Get-CredentialInfo $value
      switch ($info.Kind) {
        'license' { $cred.Var = 'SPICE_LICENSE' }
        'pass' { $cred.Var = 'SPICE_PASS' }
        default {
          Say "   This doesn't look like a Spice License or a Spice Pass. Which is it?"
          Say ""
          Say "  1) Spice License: issued to your organization by Spice Labs"
          Say "  2) Spice Pass: downloaded from your Spice Labs project dashboard"
          Say "  3) Neither: enter it again"
          Say ""
          while ($true) {
            $reply = Ask "Please select [3]: " "3"
            if ($reply -eq '1') { $cred.Var = 'SPICE_LICENSE'; break }
            if ($reply -eq '2') { $cred.Var = 'SPICE_PASS'; break }
            if ($reply -eq '3') { break }
          }
        }
      }
      if (-not $cred.Var) { continue }
      if ($info.Kind -ne 'unknown') { Say "   This is $(Get-CredentialDescription $info)." }
      if ((Test-Expired $info) -and -not (Confirm "   Save it anyway?" $false)) { continue }
      $cred.Value = $value
      return
    }
  }

  # The credentials file: the existing one, with the new credential replaced
  function Get-CredentialsText {
    $lines = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $CredentialsFile) {
      foreach ($line in [IO.File]::ReadAllLines($CredentialsFile)) {
        if (-not $line.StartsWith("export $($cred.Var)=")) { $lines.Add($line) }
      }
    } else {
      $lines.Add("# Spice Labs credentials, loaded by the spice block in your shell init script.")
      $lines.Add("# Every line here must be valid in sh, bash, zsh and fish.")
    }
    $lines.Add("export $($cred.Var)='$($cred.Value)'")
    ($lines -join "`n") + "`n"
  }

  # ── Image ──────────────────────────────────────────────────────────────────
  #
  # The image is the one for the edition of the Spice License that spice will
  # run with, saved as SPICE_IMAGE in the environment file, which the wrapper
  # uses instead of its default. A SPICE_IMAGE the user set themselves is left
  # alone.

  $image = @{ Ref = ""; Save = ""; Action = ""; Edition = ""; Login = $false; Password = "" }

  function Select-Image {
    $ours = Get-SavedValue $EnvironmentFile 'SPICE_IMAGE'
    if ($env:SPICE_IMAGE -and $env:SPICE_IMAGE -ne $ours) {
      $image.Ref = $env:SPICE_IMAGE
    } else {
      # The first License among: the credential entered now, then those set in
      # this shell or saved, under either variable
      foreach ($value in @($cred.Value, $env:SPICE_LICENSE, (Get-SavedValue $CredentialsFile 'SPICE_LICENSE'),
                           $env:SPICE_PASS, (Get-SavedValue $CredentialsFile 'SPICE_PASS'))) {
        if (-not $value) { continue }
        $info = Get-CredentialInfo $value
        if ($info.Kind -eq 'license') { $image.Edition = $info.Edition; break }
      }
      $image.Save = Get-EditionImage $image.Edition
      $image.Ref = if ($image.Save) { $image.Save } else { $DefaultImage }
      if ($image.Save -and $image.Save -ne $ours) { $image.Action = 'write' }
      elseif (-not $image.Save -and (Test-Path -LiteralPath $EnvironmentFile)) { $image.Action = 'remove' }
    }
    $image.Login = $image.Ref.StartsWith("$Registry/")
  }

  function Test-EnvironmentKept {
    $image.Action -eq 'write' -or ((Test-Path -LiteralPath $EnvironmentFile) -and $image.Action -ne 'remove')
  }

  function Get-EnvironmentText {
    "# Settings for spice, loaded by the spice block in your shell init script.`n" +
    "# The spice installer rewrites this file when run again.`n" +
    "export SPICE_IMAGE='$($image.Save)'`n"
  }

  function Select-RegistryPassword {
    $image.Password = $env:SPICE_PASSWORD
    if (-not $image.Password -and $interactive) {
      Say ""
      Say "$($IconLock) Downloading $(Get-EditionTitle $image.Edition) needs a login to $(C $Registry)."
      $image.Password = Read-Password "Password for $(C $LoginUser): "
    }
    if (-not $image.Password) {
      Die "Logging in to $(C $Registry) needs a password. Set $(C SPICE_PASSWORD) to it, then run the installer again."
    }
  }

  # Logs in to the registry, journalling the login if there wasn't one already
  function Invoke-RegistryLogin {
    $new = -not ((Test-Path -LiteralPath $DockerConfigFile) -and
      (Select-String -LiteralPath $DockerConfigFile -SimpleMatch "`"$Registry`"" -Quiet))
    $r = Invoke-Native docker @('login', $Registry, '--username', $LoginUser, '--password-stdin') $image.Password
    if ($r.Code -ne 0) { Die "Couldn't log in to $(C $Registry): $($r.Output)" }
    if ($new) { [void]$journal.Add(@{ Kind = 'login'; Target = $Registry }) }
  }

  function Test-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
      Die "spice needs Docker, which isn't installed. Install it from $(C https://docs.docker.com/get-docker/), then run the installer again."
    }
    if ((Invoke-Native docker @('info')).Code -ne 0) {
      Die "Docker is installed, but isn't running. Start it, then run the installer again."
    }
  }

  # ── Install directory ──────────────────────────────────────────────────────

  # Makes a directory absolute and tidy, or returns nothing if it can't be used
  function Get-NormalDir([string]$Path) {
    if ($Path -eq '~' -or $Path.StartsWith('~/') -or $Path.StartsWith('~\')) { $Path = $UserHome + $Path.Substring(1) }
    if (-not [IO.Path]::IsPathRooted($Path)) { $Path = Join-Path (Get-Location).ProviderPath $Path }
    $Path = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($Path)
    if ($Path.Length -gt $root.Length) { $Path = $Path.TrimEnd('\', '/') }
    # The directory is written into profiles and PATH
    if ($Path.Contains('"') -or $Path.Contains("`n") -or ($Path -replace '^[A-Za-z]:', '').Contains([string]$Sep)) { return "" }
    $Path
  }

  # Whether a directory is writable, or could be created
  function Test-Writable([string]$Path) {
    $d = $Path
    while ($d -and -not (Test-Path -LiteralPath $d)) { $d = Split-Path -Parent $d }
    if (-not $d) { return $false }
    $probe = Join-Path $d ".spice-write-test.$PID"
    try {
      [IO.File]::WriteAllText($probe, "")
      Remove-Item -LiteralPath $probe -Force
      $true
    } catch {
      $false
    }
  }

  function Find-Existing {
    $cmd = Get-Command spice.ps1 -CommandType ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source) { Split-Path -Parent $cmd.Source } else { "" }
  }

  function Select-Dir([string]$Existing) {
    $list = New-Object System.Collections.Generic.List[string]
    foreach ($d in @($Existing, $DefaultDir)) {
      if ($d -and -not $list.Contains($d)) { $list.Add($d) }
    }
    foreach ($entry in ($env:PATH -split [regex]::Escape($Sep))) {
      if (-not $entry -or -not [IO.Path]::IsPathRooted($entry)) { continue }
      $d = Get-NormalDir $entry
      if (-not $d -or -not (Test-Path -LiteralPath $d -PathType Container)) { continue }
      # On Windows, offer only the user's own directories, never system ones,
      # which an administrator could write to
      if ($IsWindows -and -not ($d.StartsWith($UserHome, [StringComparison]::OrdinalIgnoreCase) -or
          ($env:LOCALAPPDATA -and $d.StartsWith($env:LOCALAPPDATA, [StringComparison]::OrdinalIgnoreCase)))) { continue }
      if (-not (Test-Writable $d)) { continue }
      if (-not $list.Contains($d)) { $list.Add($d) }
    }

    Say ""
    Say "$($IconFolder) Where should $(C spice) be installed?"
    Say ""
    for ($i = 0; $i -lt $list.Count; $i++) {
      $note = ""
      if ($list[$i] -eq $Existing) { $note = "  (current install)" }
      if (-not (Test-OnPath $list[$i])) { $note += "  (will be added to $(C PATH))" }
      Say "  $($i + 1)) $(P $list[$i])$note"
    }
    $other = $list.Count + 1
    Say "  $other) Somewhere else"
    Say ""

    while ($true) {
      $reply = Ask "Please select a location [1]: " "1"
      $n = 0
      if (-not [int]::TryParse($reply, [ref]$n)) { continue }
      if ($n -ge 1 -and $n -le $list.Count) { return $list[$n - 1] }
      if ($n -eq $other) {
        while ($true) {
          $reply = Ask "Directory: " ""
          if (-not $reply) { continue }
          $d = Get-NormalDir $reply
          if (-not $d) { Say "   Directory names can't contain `" or $(C $Sep)." }
          elseif (-not (Test-Writable $d)) { Say "   You can't write to $(P $d)." }
          else { return $d }
        }
      }
    }
  }

  # ── Profiles ───────────────────────────────────────────────────────────────

  # Whether a profile already has spice lines: a block, or an older installer's
  function Test-HasSpice([string]$File) {
    (Test-Path -LiteralPath $File) -and
      ([bool](Select-String -LiteralPath $File -Pattern ('^(' + [regex]::Escape($BlockBegin) + '|' + [regex]::Escape($Legacy) + ')$') -Quiet))
  }

  # The candidate profiles, each with a label and whether it's selected by default
  function Get-ProfileCandidates {
    $list = @()
    if ($IsWindows) {
      $docs = [Environment]::GetFolderPath('MyDocuments')
      $ps7 = Join-Path (Join-Path $docs 'PowerShell') 'profile.ps1'
      $ps5 = Join-Path (Join-Path $docs 'WindowsPowerShell') 'profile.ps1'
      if ($PSVersionTable.PSVersion.Major -ge 6 -or (Test-Path -LiteralPath $ps7) -or (Get-Command pwsh -ErrorAction SilentlyContinue)) {
        $list += @{ Path = $ps7; Label = "PowerShell 7"; Default = $true }
      }
      # Windows PowerShell runs spice.cmd, so needs the credentials even if it
      # isn't the shell in use
      $list += @{ Path = $ps5; Label = "Windows PowerShell"; Default = $true }
    } else {
      $list += @{ Path = $PROFILE.CurrentUserAllHosts; Label = "PowerShell"; Default = $true }
    }
    foreach ($c in $list) { if (Test-HasSpice $c.Path) { $c.Default = $true } }
    $list
  }

  function Select-Profiles {
    $candidates = @(Get-ProfileCandidates)
    if (-not $interactive) { return @($candidates | Where-Object { $_.Default } | ForEach-Object { $_.Path }) }
    if ($candidates.Count -eq 1) {
      Say ""
      if (Confirm "$($IconMemo) Set up $(C spice) in $(P $candidates[0].Path)?" $true) { return @($candidates[0].Path) }
      return @()
    }

    Say ""
    Say "$($IconMemo) Which PowerShell profiles should set up $(C spice)?"
    Say ""
    $defaults = @()
    for ($i = 0; $i -lt $candidates.Count; $i++) {
      Say "  $($i + 1)) $(P $candidates[$i].Path)  ($($candidates[$i].Label))"
      if ($candidates[$i].Default) { $defaults += ($i + 1) }
    }
    Say ""
    $default = if ($defaults) { $defaults -join ' ' } else { 'none' }
    while ($true) {
      $reply = Ask "Please specify numbers, separated by spaces or $(C none) [$default]: " $default
      if ($reply -eq 'none') { return @() }
      $chosen = @()
      $ok = $true
      foreach ($word in ($reply -split '\s+' | Where-Object { $_ })) {
        $n = 0
        if (-not [int]::TryParse($word, [ref]$n) -or $n -lt 1 -or $n -gt $candidates.Count) { $ok = $false; break }
        if ($chosen -notcontains $candidates[$n - 1].Path) { $chosen += $candidates[$n - 1].Path }
      }
      if ($ok) { return $chosen }
    }
  }

  function Test-Profile([string]$File) {
    $dir = Split-Path -Parent $File
    if (-not (Test-Writable $dir)) { Die "Can't write to $(P $dir)." }
    if (Test-Path -LiteralPath $File) {
      $lines = [IO.File]::ReadAllLines($File)
      $begins = @($lines | Where-Object { $_ -eq $BlockBegin }).Count
      $ends = @($lines | Where-Object { $_ -eq $BlockEnd }).Count
      if ($begins -gt 1 -or $begins -ne $ends) {
        Die "$(P $File) has a damaged $(C spice) block. Remove the lines between $(C $BlockBegin) and $(C $BlockEnd), then run the installer again."
      }
    }
  }

  function Quote([string]$Text) { "'" + $Text.Replace("'", "''") + "'" }

  # Whether a profile's spice block adds DIR to PATH
  function Test-BlockAddsPath([string]$File, [string]$Path) {
    if (-not (Test-Path -LiteralPath $File)) { return $false }
    $inBlock = $false
    foreach ($line in [IO.File]::ReadAllLines($File)) {
      if ($line -eq $BlockBegin) { $inBlock = $true }
      elseif ($line -eq $BlockEnd) { $inBlock = $false }
      elseif ($inBlock -and $line.Contains('PATH') -and $line.Contains($Path)) { return $true }
    }
    $false
  }

  # The spice block for a profile. On Windows, PATH is set in the user
  # environment instead; elsewhere the block adds the install directory, if it
  # isn't on PATH already or if this block put it there in an earlier run.
  function Get-ProfileBlock([string]$File) {
    $block = @($BlockBegin, "# Added by the spice installer, which rewrites this block when run again.")
    if (-not $IsWindows -and (-not (Test-OnPath $installDir) -or (Test-BlockAddsPath $File $installDir))) {
      $q = Quote $installDir
      $block += 'if (@($env:PATH -split [IO.Path]::PathSeparator) -notcontains ' + $q + ') { $env:PATH += [IO.Path]::PathSeparator + ' + $q + ' }'
    }
    $files = @()
    if ((Test-Path -LiteralPath $CredentialsFile) -or $cred.Var) { $files += Quote $CredentialsFile }
    if (Test-EnvironmentKept) { $files += Quote $EnvironmentFile }
    if ($files) {
      # Read, not run: these files are shared with the sh installer's shells
      $block += 'foreach ($spiceFile in @(' + ($files -join ', ') + ')) {'
      $block += '  if (Test-Path -LiteralPath $spiceFile) {'
      $block += '    foreach ($spiceLine in [IO.File]::ReadAllLines($spiceFile)) {'
      $block += '      if ($spiceLine -match "^export (SPICE_[A-Z_]+)=''(.*)''$") { Set-Item -LiteralPath "env:$($Matches[1])" -Value $Matches[2] }'
      $block += '    }'
      $block += '  }'
      $block += '}'
      $block += "Remove-Variable spiceFile, spiceLine -ErrorAction SilentlyContinue"
    }
    $q = Quote $CompletionPath
    $block += "if (Test-Path -LiteralPath $q) { . $q }"
    $block += $BlockEnd
    $block
  }

  # A profile's lines, with its spice block replaced by BLOCK, or appended.
  # Lines that older installers appended without a block are removed.
  function Get-RewrittenProfile([string[]]$Lines, [string[]]$Block) {
    $out = New-Object System.Collections.Generic.List[string]
    $blanks = 0
    $done = $false
    $inBlock = $false
    $inLegacy = $false
    $printed = $false
    $flush = { while ($blanks -gt 0) { $out.Add(''); $blanks-- } }
    $emit = { foreach ($b in $Block) { $out.Add($b) }; $done = $true }
    foreach ($line in $Lines) {
      if ($line -eq $BlockBegin) { . $flush; if (-not $done) { . $emit }; $inBlock = $true; continue }
      if ($inBlock) { if ($line -eq $BlockEnd) { $inBlock = $false }; continue }
      if ($line -eq $Legacy) { $inLegacy = $true; if (-not $done) { . $flush; . $emit }; continue }
      if ($inLegacy -and $line.Contains($CompletionPath)) { continue }
      $inLegacy = $false
      if ($line -eq '') { $blanks++; continue }
      . $flush
      $out.Add($line)
      $printed = $true
    }
    if (-not $done -and $printed -and $blanks -eq 0) { $out.Add('') }
    . $flush
    if (-not $done) { . $emit }
    , $out.ToArray()
  }

  # Reads a profile, keeping its encoding and line endings: Windows PowerShell
  # reads a file without a BOM in the ANSI code page, so one that isn't valid
  # UTF-8 is read and written in that code page, not converted.
  function Read-Profile([string]$File) {
    $p = @{ Lines = @(); Encoding = $null; NewLine = $(if ($IsWindows) { "`r`n" } else { "`n" }) }
    if (-not (Test-Path -LiteralPath $File)) {
      $p.Encoding = New-Object Text.UTF8Encoding($IsWindows)
      return $p
    }
    $bytes = [IO.File]::ReadAllBytes($File)
    $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    try {
      $text = (New-Object Text.UTF8Encoding($bom, $true)).GetString($bytes)
      if ($bom) { $text = $text.Substring(1) }
      $p.Encoding = New-Object Text.UTF8Encoding($bom)
    } catch {
      $p.Encoding = [Text.Encoding]::Default
      $text = $p.Encoding.GetString($bytes)
    }
    if ($text.Contains("`r`n")) { $p.NewLine = "`r`n" } elseif ($text.Contains("`n")) { $p.NewLine = "`n" }
    $p.Lines = @($text -split "\r?\n")
    # The final newline leaves an empty last element, which isn't a line
    if ($p.Lines.Count -gt 0 -and $p.Lines[-1] -eq '') { $p.Lines = @($p.Lines | Select-Object -SkipLast 1) }
    $p
  }

  # ── Main ───────────────────────────────────────────────────────────────────

  $interactive = -not ($Yes -or $env:SPICE_INSTALL_NONINTERACTIVE -eq '1')
  if ($interactive -and ([Console]::IsInputRedirected -or -not [Environment]::UserInteractive -or
      ([Environment]::GetCommandLineArgs() | Where-Object { $_ -match '^-NonI' }))) {
    $interactive = $false
    Say "$($IconBulb) No console to ask questions on, so installing with the defaults."
  }
  if (-not $Dir -and $env:SPICE_INSTALL_DIR) { $Dir = $env:SPICE_INSTALL_DIR }
  $modifyProfile = -not ($NoModifyProfile -or $env:SPICE_INSTALL_NO_MODIFY_PROFILE -eq '1')

  $completed = $false
  try {
    Test-Docker

    # 1. Questions. Nothing is changed yet.

    $existing = Find-Existing
    if ($Dir) {
      $installDir = Get-NormalDir $Dir
      if (-not $installDir) { Die "$(C -Dir) can't contain `" or $(C $Sep)." }
    } elseif ($interactive) {
      $installDir = Select-Dir $existing
    } else {
      $installDir = if ($existing) { $existing } else { $DefaultDir }
    }
    if (-not (Test-Writable $installDir)) { Die "Can't write to $(P $installDir)." }

    if ($interactive) { Select-Credential }
    Select-Image
    if ($image.Login) { Select-RegistryPassword }
    $profiles = @()
    if ($modifyProfile) { $profiles = @(Select-Profiles) }
    foreach ($f in $profiles) { Test-Profile $f }

    $userPath = $null
    if ($IsWindows -and $modifyProfile -and -not (Test-OnPath $installDir)) {
      $userPath = Get-UserPath
      $entries = @($userPath.Value -split ';' | Where-Object { $_ })
      if ($entries -contains $installDir) { $userPath = $null }
    }

    # 2. Write the new files into a staging directory, and confirm.

    $stage = Join-Path ([IO.Path]::GetTempPath()) "spice-install.$PID.$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    $null = New-Item -ItemType Directory -Path $stage
    if (-not $IsWindows) { $null = Invoke-Native chmod @('700', $stage) }
    $utf8 = New-Object Text.UTF8Encoding($false)
    if ($cred.Var) { [IO.File]::WriteAllText((Join-Path $stage 'credentials'), (Get-CredentialsText), $utf8) }
    if ($image.Action -eq 'write') { [IO.File]::WriteAllText((Join-Path $stage 'environment'), (Get-EnvironmentText), $utf8) }

    $changedProfiles = @()
    $n = 0
    foreach ($f in $profiles) {
      $n++
      $p = Read-Profile $f
      $lines = Get-RewrittenProfile $p.Lines (Get-ProfileBlock $f)
      $text = ($lines -join $p.NewLine) + $p.NewLine
      $staged = Join-Path $stage "profile.$n"
      [IO.File]::WriteAllText($staged, $text, $p.Encoding)
      if ((Test-Path -LiteralPath $f) -and
          ([Convert]::ToBase64String([IO.File]::ReadAllBytes($f)) -eq [Convert]::ToBase64String([IO.File]::ReadAllBytes($staged)))) {
        continue
      }
      $changedProfiles += @{ Path = $f; Staged = $staged }
    }

    Say ""
    Say "Ready to install:"
    Say "  $($Bullet) $(C spice) in $(P $installDir)"
    if ($cred.Var) { Say "  $($Bullet) $(C $cred.Var) saved in $(P $CredentialsFile)" }
    if ($image.Login) { Say "  $($Bullet) a Docker login to $(C $Registry)" }
    Say "  $($Bullet) the image $(C $image.Ref), with tab completion from it"
    if ($image.Action -eq 'write') { Say "  $($Bullet) $(C SPICE_IMAGE) saved in $(P $EnvironmentFile)" }
    if ($image.Action -eq 'remove') { Say "  $($Bullet) $(P $EnvironmentFile) removed, so $(C spice) uses its default image" }
    if ($userPath) { Say "  $($Bullet) $(P $installDir) added to your user $(C PATH)" }
    foreach ($c in $changedProfiles) { Say "  $($Bullet) $(C spice) set up in $(P $c.Path)" }
    if ($interactive -and -not (Confirm "Proceed with installation?" $true)) {
      Say "Nothing was changed."
      $completed = $true
      return
    }

    # 3. Download the script and the image, and generate from the image.

    Say ""
    Say "$($IconPackage) Downloading $(C spice)"
    $script = Join-Path $stage 'spice.ps1'
    try {
      Invoke-WebRequest -UseBasicParsing -Uri $ScriptUrl -OutFile $script
    } catch {
      Die "Couldn't download $(C $ScriptUrl): $_"
    }
    if (-not ([IO.File]::ReadAllText($script).StartsWith('#'))) { Die "The download from $(C $ScriptUrl) isn't a script." }

    if ($image.Login) { Invoke-RegistryLogin }
    Say "$($IconPackage) Downloading $(C $image.Ref)"
    if ($interactive) {
      $ErrorActionPreference = 'Continue'
      & docker pull $image.Ref
      $code = $LASTEXITCODE
      $ErrorActionPreference = 'Stop'
    } else {
      $code = (Invoke-Native docker @('pull', '-q', $image.Ref)).Code
    }
    if ($code -ne 0) { Die "Couldn't download $(C $image.Ref)." }

    # Plugin-aware tab completion and the path manifest both come from the image
    # (no static fallback), so they reflect whatever plugins (e.g. `registry`) it
    # ships, and are skipped gracefully if they can't be generated. The script
    # runs in a new process, as this one's execution policy may not allow it.
    # SKIP_PULL keeps stdout clean for capture. The manifest tells the first
    # invocation which arguments to bind-mount; the wrapper refreshes it itself
    # when the image changes.
    $shell = (Get-Process -Id $PID).Path
    $saved = @{ SPICE_IMAGE = $env:SPICE_IMAGE; SPICE_LABS_CLI_SKIP_PULL = $env:SPICE_LABS_CLI_SKIP_PULL; SPICE_SKIP_MANIFEST_REFRESH = $env:SPICE_SKIP_MANIFEST_REFRESH }
    $env:SPICE_IMAGE = $image.Ref
    $env:SPICE_LABS_CLI_SKIP_PULL = '1'
    $env:SPICE_SKIP_MANIFEST_REFRESH = '1'
    try {
      $haveCompletion = $false
      $r = Invoke-Native $shell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script, 'generate-powershell-completion') -Quiet
      if ($r.Code -eq 0 -and $r.Output) {
        [IO.File]::WriteAllText((Join-Path $stage 'spice-completion.ps1'), $r.Output + "`n", $utf8)
        $haveCompletion = $true
      }
      $haveManifest = $false
      $r = Invoke-Native $shell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script, 'path-manifest') -Quiet
      if ($r.Code -eq 0 -and $r.Output -match '(?m)^# spice-path-manifest ') {
        [IO.File]::WriteAllText((Join-Path $stage 'path-manifest'), $r.Output + "`n", $utf8)
        $haveManifest = $true
      }
    } finally {
      foreach ($k in $saved.Keys) { Set-Item -LiteralPath "env:$k" -Value $saved[$k] -ErrorAction SilentlyContinue }
    }

    # 4. Stage every file beside its destination, then move them all into place.

    New-Dir $installDir
    Write-Prepared $script (Join-Path $installDir 'spice.ps1')
    if ($IsWindows) {
      $shim = Join-Path $stage 'spice.cmd'
      [IO.File]::WriteAllText($shim, "@echo off`r`npowershell -ExecutionPolicy Bypass -File `"$(Join-Path $installDir 'spice.ps1')`" %*`r`n")
      Write-Prepared $shim (Join-Path $installDir 'spice.cmd')
    }
    if ($haveCompletion) {
      New-Dir $CompletionDir
      Write-Prepared (Join-Path $stage 'spice-completion.ps1') $CompletionPath
    }
    if ($haveManifest) {
      New-Dir $DataDir
      Write-Prepared (Join-Path $stage 'path-manifest') $ManifestFile
    }
    if ($cred.Var) {
      New-Dir $ConfigDir
      Write-Prepared (Join-Path $stage 'credentials') $CredentialsFile -Private
    }
    if ($image.Action -eq 'write') {
      New-Dir $ConfigDir
      Write-Prepared (Join-Path $stage 'environment') $EnvironmentFile
    }
    foreach ($c in $changedProfiles) {
      New-Dir (Split-Path -Parent $c.Path)
      Write-Prepared $c.Staged $c.Path
    }

    Complete-File (Join-Path $installDir 'spice.ps1')
    if ($IsWindows) { Complete-File (Join-Path $installDir 'spice.cmd') }
    if ($haveCompletion) { Complete-File $CompletionPath }
    if ($haveManifest) { Complete-File $ManifestFile }
    if ($cred.Var) { Complete-File $CredentialsFile }
    if ($image.Action -eq 'write') { Complete-File $EnvironmentFile }
    if ($image.Action -eq 'remove') { Remove-Journalled $EnvironmentFile }
    foreach ($c in $changedProfiles) { Complete-File $c.Path }
    if ($userPath) {
      [void]$journal.Add(@{ Kind = 'userpath'; Target = 'PATH'; Value = $userPath })
      $value = if ($userPath.Value) { $userPath.Value.TrimEnd(';') + ';' + $installDir } else { $installDir }
      Set-UserPath @{ Value = $value; Kind = $userPath.Kind }
      $env:PATH = $env:PATH.TrimEnd(';') + ';' + $installDir
    }
    Complete-Journal
    $completed = $true

    # 5. Report.

    Say ""
    Say "$($IconCheck) $(C spice) installed in $(P $installDir)"
    if ($existing -and $existing -ne $installDir) {
      Say "$($IconBulb) Another $(C spice) is still installed in $(P $existing); remove it if you no longer need it."
    }
    if ($image.Edition) { Say "$($IconCheck) Using $(Get-EditionTitle $image.Edition), from $(C $image.Ref)" }
    if (-not $haveCompletion) {
      Say "$($IconBulb) Couldn't generate tab completion from the image. Generate it later with:"
      Say "   $(C "spice generate-powershell-completion > `"$CompletionPath`"")"
    }
    if ($profiles) {
      Say "$($IconCheck) PowerShell profiles set up: open a new window to start using $(C spice)"
    } else {
      Say "$($IconBulb) To set up PowerShell, add these lines to your profile ($(P $PROFILE.CurrentUserAllHosts)):"
      foreach ($line in (Get-ProfileBlock $PROFILE.CurrentUserAllHosts)) { Say "   $(C $line)" }
    }
    if ($IsWindows -and $profiles) {
      $policy = Get-ExecutionPolicy
      if (@('Restricted', 'AllSigned', 'Undefined') -contains [string]$policy) {
        Say "$($IconWarn)  PowerShell's execution policy is $(C $policy), so it won't run your profile, or $(C spice.ps1). To allow both:"
        Say "   $(C 'Set-ExecutionPolicy -Scope CurrentUser RemoteSigned')"
      }
    }
    if (-not $env:SPICE_PASS -and -not $env:SPICE_LICENSE -and -not (Test-Path -LiteralPath $CredentialsFile)) {
      Say "$($IconWarn)  Neither $(C SPICE_PASS) nor $(C SPICE_LICENSE) is set. Run the installer again to save one, or set it in your profile:"
      Say "   $(C '$env:SPICE_PASS = "your-secret-token"')"
      Say "   $(C '$env:SPICE_LICENSE = "your-license"')"
    }
  } catch [System.OperationCanceledException] {
    # Die has shown the message
  } catch {
    Write-Host "$($IconCross) $_"
  } finally {
    foreach ($f in $prepared) { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
    if (-not $completed) { Undo-Journal }
    if ($stage) { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue }
  }
}

Install-SpiceSurveyor -Yes:$Yes -Dir $Dir -NoModifyProfile:$NoModifyProfile
