#Requires -Module Pester
<#
  Pester 5 tests for PowerShell tab completion (completions/spice-completion.ps1).

  Run:  Invoke-Pester ./test/wrapper/completions.Tests.ps1 -Output Detailed
#>

BeforeAll {
  $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
  $script:RepoRoot = (Resolve-Path (Join-Path (Join-Path $scriptDir '..') '..')).Path

  # Source the completion script and capture the script block
  $completionFile = Join-Path (Join-Path $script:RepoRoot 'completions') 'spice-completion.ps1'
  
  # Extract the ScriptBlock from Register-ArgumentCompleter by intercepting the call
  $script:CompleterBlock = $null
  function Register-ArgumentCompleter {
    param([switch]$Native, $CommandName, $ScriptBlock)
    $script:CompleterBlock = $ScriptBlock
  }
  . $completionFile
  if (-not $script:CompleterBlock) { throw 'Failed to capture completer script block' }

  # Helper: invoke the completer directly for a command line
  # Returns an array of completion text values
  function Get-SpiceCompletions {
    param([string]$CommandLine)
    # The completer receives: wordToComplete, commandAst, cursorPosition
    # We need to figure out wordToComplete from the command line
    $parts = $CommandLine -split '\s+'
    $wordToComplete = ''
    if ($CommandLine -notmatch '\s$') {
      $wordToComplete = $parts[-1]
    }
    # Build a fake AST-like object that has a ToString() method
    $fakeAst = New-Object PSObject
    $cl = $CommandLine
    $fakeAst | Add-Member -MemberType ScriptMethod -Name 'ToString' -Value { $cl }.GetNewClosure() -Force
    $results = @(& $script:CompleterBlock $wordToComplete $fakeAst $CommandLine.Length)
    if ($results) {
      return @($results | ForEach-Object {
        if ($_ -is [System.Management.Automation.CompletionResult]) { $_.CompletionText } else { "$_" }
      })
    }
    return @()
  }
}

Describe 'PowerShell tab completion' {

  # ── Top-level completions ──────────────────────────────────────────────────

  Context 'Top level' {
    It 'completes survey and pass' {
      $c = Get-SpiceCompletions 'spice '
      $c | Should -Contain 'survey'
      $c | Should -Contain 'pass'
    }

    It 'completes --help and --version' {
      $c = Get-SpiceCompletions 'spice '
      $c | Should -Contain '--help'
      $c | Should -Contain '--version'
    }

    It 'partial "s" completes to survey' {
      $c = Get-SpiceCompletions 'spice s'
      $c | Should -Contain 'survey'
    }

    It 'partial "p" completes to pass' {
      $c = Get-SpiceCompletions 'spice p'
      $c | Should -Contain 'pass'
    }
  }

  # ── Survey subcommand ──────────────────────────────────────────────────────

  Context 'Survey' {
    It 'completes inventory' {
      $c = Get-SpiceCompletions 'spice survey '
      $c | Should -Contain 'inventory'
    }

    It 'completes runtime' {
      $c = Get-SpiceCompletions 'spice survey '
      $c | Should -Contain 'runtime'
    }

    It 'completes --help' {
      $c = Get-SpiceCompletions 'spice survey '
      $c | Should -Contain '--help'
    }
  }

  # ── Survey inventory options ───────────────────────────────────────────────

  Context 'Survey inventory options' {
    It 'completes flags' {
      $c = Get-SpiceCompletions 'spice survey inventory myapp /path --'
      $c | Should -Contain '--output'
      $c | Should -Contain '--threads'
      $c | Should -Contain '--no-upload'
      $c | Should -Contain '--log-level'
      $c | Should -Contain '--log-file'
      $c | Should -Contain '--tag-json'
      $c | Should -Contain '--ginger-args'
      $c | Should -Contain '--goat-rodeo-args'
    }

    It '--log-level completes levels' {
      $c = Get-SpiceCompletions 'spice survey inventory myapp /path --log-level '
      $c | Should -Contain 'debug'
      $c | Should -Contain 'info'
      $c | Should -Contain 'warn'
      $c | Should -Contain 'error'
      $c | Should -Contain 'trace'
    }

    It '--threads has no completions' {
      $c = Get-SpiceCompletions 'spice survey inventory myapp /path --threads '
      $c | Should -BeNullOrEmpty
    }

    It '--tag-json has no completions' {
      $c = Get-SpiceCompletions 'spice survey inventory myapp /path --tag-json '
      $c | Should -BeNullOrEmpty
    }
  }

  # ── Survey runtime options ─────────────────────────────────────────────────

  Context 'Survey runtime options' {
    It 'completes flags' {
      $c = Get-SpiceCompletions 'spice survey runtime myapp --'
      $c | Should -Contain '--jfr'
      $c | Should -Contain '--native-only'
      $c | Should -Contain '--no-upload'
      $c | Should -Contain '--keep-recording'
      $c | Should -Contain '--output'
      $c | Should -Contain '--chunk-size'
      $c | Should -Contain '--log-level'
      $c | Should -Contain '--log-file'
    }

    It 'does not offer inventory-only flags' {
      $c = Get-SpiceCompletions 'spice survey runtime myapp --'
      $c | Should -Not -Contain '--tag-json'
      $c | Should -Not -Contain '--upload-only'
      $c | Should -Not -Contain '--goat-rodeo-args'
      $c | Should -Not -Contain '--ginger-args'
      $c | Should -Not -Contain '--max-records'
      $c | Should -Not -Contain '--threads'
    }

    It '--log-level completes levels' {
      $c = Get-SpiceCompletions 'spice survey runtime myapp --log-level '
      $c | Should -Contain 'debug'
      $c | Should -Contain 'info'
      $c | Should -Contain 'warn'
      $c | Should -Contain 'error'
    }
  }

  # ── Pass subcommand ────────────────────────────────────────────────────────

  Context 'Pass' {
    It 'completes decode' {
      $c = Get-SpiceCompletions 'spice pass '
      $c | Should -Contain 'decode'
    }

    It 'completes --help' {
      $c = Get-SpiceCompletions 'spice pass '
      $c | Should -Contain '--help'
    }
  }

  Context 'Pass decode' {
    It 'completes --help and --version' {
      $c = Get-SpiceCompletions 'spice pass decode '
      $c | Should -Contain '--help'
      $c | Should -Contain '--version'
    }

    It 'no extra subcommands' {
      $c = Get-SpiceCompletions 'spice pass decode '
      $c | Should -Not -Contain 'survey'
      $c | Should -Not -Contain 'inventory'
    }
  }
}
