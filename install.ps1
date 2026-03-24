# To install : `irm -UseBasicParsing -Uri https://install.spicelabs.io | iex`

# Compatibility shim for Windows PowerShell 5.1 where $IsWindows is not defined
if (-not (Test-Path variable:IsWindows)) {
    $IsWindows = $true
}

# Ensure USERPROFILE is set (for Linux compatibility)
if (-not $env:USERPROFILE) {
    $env:USERPROFILE = $env:HOME
}

$TargetDir = "$env:USERPROFILE\.spice\bin"
$ScriptUrl = "https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/spice.ps1"
$ScriptPath = "$TargetDir\spice.ps1"

Write-Host "📦 Installing spice.ps1 to $TargetDir"

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
Invoke-WebRequest -Uri $ScriptUrl -OutFile $ScriptPath

# Optionally create a shim
$ShimPath = "$TargetDir\spice.cmd"
Set-Content -Path $ShimPath -Value "@echo off`npowershell -ExecutionPolicy Bypass -File `"$ScriptPath`" %*"

# Add to PATH if not present
# On Linux/macOS the PATH delimiter is ":" and paths use "/", on Windows it is ";" and "\"
$pathDelimiter = if ($IsWindows) { ";" } else { ":" }
$normalizedTarget = if ($IsWindows) { $TargetDir } else { $TargetDir -replace "\\", "/" }

if (-not ($env:PATH -split $pathDelimiter | Where-Object { $_ -eq $normalizedTarget })) {
    Write-Host "⚠️  $normalizedTarget is not in your PATH. Add it to your user environment variables:"
    Write-Host "    $normalizedTarget"
    if (-not $IsWindows) {
        Write-Host "💡 On Linux/macOS, prepend it so spice.ps1 takes precedence over any existing spice binary:"
        Write-Host "    `$env:PATH = `"$normalizedTarget`:`$env:PATH`""
    }
} else {
    # Warn if a conflicting spice binary exists earlier in PATH than spice.ps1
    $spiceCmd = Get-Command spice -ErrorAction SilentlyContinue
    if ($spiceCmd -and $spiceCmd.Source -ne "$normalizedTarget/spice.ps1" -and $spiceCmd.Source -ne "$normalizedTarget\spice.ps1") {
        Write-Host "⚠️  Another 'spice' binary was found at $($spiceCmd.Source) which may shadow spice.ps1."
        Write-Host "    Ensure $normalizedTarget appears before $([System.IO.Path]::GetDirectoryName($spiceCmd.Source)) in your PATH."
    } else {
        Write-Host "✅ spice installed and ready to use"
    }
}

if (-not $env:SPICE_PASS) {
    Write-Host "⚠️  SPICE_PASS is not set. Set it in your shell env to use the CLI:"
    Write-Host '    $env:SPICE_PASS = "your-secret-token"'
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "⚠️  Docker is not installed or not in PATH. The spice CLI uses Docker unless JVM mode is enabled."
    Write-Host "  → Install Docker from https://docs.docker.com/get-docker/"
}

