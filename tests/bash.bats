#!/usr/bin/env bats

setup() {
  export SPICE_PASS=fake
  mkdir -p tmp
}

teardown() {
  rm -rf tmp
}

# smoke test: --help
@test "help matches golden" {
  run ./spice --help
  [ "$status" -eq 0 ]
  diff -u tests/golden/help.stdout.txt <(echo "$output")
}

# ...repeat for scan-artifacts, upload-adgs, upload-deployment-events
