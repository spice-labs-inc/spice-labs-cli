# ps5-check.ps1 — Validate that a PowerShell script parses cleanly in the current PS version.
# Intended to be called via: powershell.exe -NoProfile -ExecutionPolicy Bypass -File ps5-check.ps1 <script>
param(
  [Parameter(Mandatory)]
  [string]$ScriptPath
)

$errors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile(
  $ScriptPath, [ref]$null, [ref]$errors
)

if ($errors.Count -gt 0) {
  foreach ($e in $errors) {
    Write-Host "PARSE ERROR: $e" -ForegroundColor Red
  }
  exit 1
}

Write-Host "OK: $ScriptPath parsed without errors (PS $($PSVersionTable.PSVersion))" -ForegroundColor Green
exit 0
