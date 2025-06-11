Describe "PowerShell wrapper" {
  BeforeAll {
    $env:SPICE_PASS = 'fake'
    New-Item -ItemType Directory -Path tmp -Force | Out‐Null
  }

  It "run matches golden" {
    $out = & pwsh ./spice.ps1 --command run --input tests/input/empty-dir --no-pull
    $out –join "`n" | Should –BeExactly (Get-Content tests/golden/run.stdout.txt –Raw)
  }

  # ... more It blocks for each command
}
