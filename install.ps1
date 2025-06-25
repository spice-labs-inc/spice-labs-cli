#To install : `irm https://github.com/spice-labs-inc/spice-labs-cli/releases/latest/download/install.ps1 | iex`

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
