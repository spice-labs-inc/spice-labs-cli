# PowerShell tab completion for the spice CLI
# Installed by: irm -UseBasicParsing -Uri https://install.spicelabs.io | iex

Register-ArgumentCompleter -Native -CommandName spice, spice.ps1 -ScriptBlock {
  param($wordToComplete, $commandAst, $cursorPosition)

  $tokens = $commandAst.ToString().Substring(0, $cursorPosition) -split '\s+' |
    Where-Object { $_ -ne '' }

  # Walk tokens to find subcommand context
  $cmd = ''
  $subcmd = ''
  for ($i = 1; $i -lt $tokens.Count; $i++) {
    switch ($tokens[$i]) {
      'survey'    { $cmd = 'survey' }
      'pass'      { $cmd = 'pass' }
      'inventory' { if ($cmd -eq 'survey') { $subcmd = 'inventory' } }
      'decode'    { if ($cmd -eq 'pass')   { $subcmd = 'decode' } }
    }
  }

  # Flags that consume the next argument
  $valueFlags = @('--output', '--threads', '--max-records', '--chunk-size',
    '--log-level', '--log-file', '--tag-json', '--goat-rodeo-args', '--ginger-args')

  # If previous token is a value flag, offer contextual completions
  $prev = if ($tokens.Count -ge 2) { $tokens[-1] } else { '' }
  if ($prev -ne $wordToComplete -and $prev -in $valueFlags) {
    switch ($prev) {
      '--log-level' {
        @('debug', 'info', 'warn', 'error', 'trace') |
          Where-Object { $_ -like "$wordToComplete*" } |
          ForEach-Object { [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_) }
        return
      }
      default { return }  # numeric or free-form — no completions
    }
  }

  # Build candidate list based on context
  $candidates = switch ($cmd) {
    'survey' {
      switch ($subcmd) {
        'inventory' {
          @('--output', '--no-upload', '--upload-only', '--tag-json', '--threads',
            '--max-records', '--chunk-size', '--log-level', '--log-file',
            '--goat-rodeo-args', '--ginger-args', '--help', '--version')
        }
        default { @('inventory', '--help', '--version') }
      }
    }
    'pass' {
      switch ($subcmd) {
        'decode' { @('--help', '--version') }
        default  { @('decode', '--help', '--version') }
      }
    }
    default { @('survey', 'pass', '--help', '--version') }
  }

  $candidates | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
    [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
  }
}
