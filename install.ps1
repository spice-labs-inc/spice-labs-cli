# To install : `irm -UseBasicParsing -Uri https://install.spicelabs.io | iex`

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
if (-not ($env:PATH -split ";" | Where-Object { $_ -eq $TargetDir })) {
    Write-Host "⚠️  $TargetDir is not in your PATH. Add it to your user environment variables:"
    Write-Host "    $env:USERPROFILE\.spice\bin"
} else {
    Write-Host "✅ spice installed and ready to use"
}

if (-not $env:SPICE_PASS) {
    Write-Host "⚠️  SPICE_PASS is not set. Set it in your shell env to use the CLI:"
    Write-Host '    $env:SPICE_PASS = "your-secret-token"'
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "⚠️  Docker is not installed or not in PATH. The spice CLI uses Docker unless JVM mode is enabled."
    Write-Host "  → Install Docker from https://docs.docker.com/get-docker/"
}
