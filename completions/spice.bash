# Bash completion for the spice CLI
# Installed by: curl -sSfL https://install.spicelabs.io | bash

_spice_completions() {
  local cur prev words cword
  _init_completion || return

  # Walk the command line to determine subcommand context
  local cmd="" subcmd=""
  for ((i = 1; i < cword; i++)); do
    case "${words[i]}" in
      survey)  cmd="survey" ;;
      pass)    cmd="pass" ;;
      inventory|static|runtime)
        [[ "$cmd" == "survey" ]] && subcmd="${words[i]}" ;;
      decode)
        [[ "$cmd" == "pass" ]] && subcmd="${words[i]}" ;;
    esac
  done

  # Options that consume the next argument (used to skip value completion)
  local value_flags="--output --threads --max-records --chunk-size --log-level --log-file --tag-json --goat-rodeo-args --ginger-args"

  # If previous word is a value-consuming flag, complete appropriately
  case "$prev" in
    --output|--log-file)
      _filedir
      return ;;
    --log-level)
      COMPREPLY=($(compgen -W "debug info warn error trace" -- "$cur"))
      return ;;
    --threads|--max-records|--chunk-size)
      return ;;  # numeric — no completions
    --tag-json|--goat-rodeo-args|--ginger-args)
      return ;;  # free-form — no completions
  esac

  # Complete based on context
  case "$cmd" in
    survey)
      case "$subcmd" in
        inventory)
          if [[ "$cur" == -* ]]; then
            COMPREPLY=($(compgen -W "--output --no-upload --upload-only --tag-json --threads --max-records --chunk-size --log-level --log-file --goat-rodeo-args --ginger-args --help --version" -- "$cur"))
          else
            _filedir
          fi ;;
        runtime)
          if [[ "$cur" == -* ]]; then
            COMPREPLY=($(compgen -W "--jfr --native-only --no-upload --keep-recording --output --chunk-size --log-level --log-file --help --version" -- "$cur"))
          else
            _filedir
          fi ;;
        *)
          COMPREPLY=($(compgen -W "inventory runtime --help --version" -- "$cur")) ;;
      esac ;;
    pass)
      case "$subcmd" in
        decode)
          COMPREPLY=($(compgen -W "--help --version" -- "$cur")) ;;
        *)
          COMPREPLY=($(compgen -W "decode --help --version" -- "$cur")) ;;
      esac ;;
    *)
      COMPREPLY=($(compgen -W "survey pass --help --version" -- "$cur")) ;;
  esac
}

complete -F _spice_completions spice
