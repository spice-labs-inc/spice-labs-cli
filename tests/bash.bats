#!/usr/bin/env bats

setup() {
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

@test "run matches golden" {
  run ./spice
  [ "$status" -eq 0 ]
  diff -u tests/golden/run.stdout.txt <(echo "$output" | sed "s/:.*/:/g" | sed "s/ is .*/ is/g")
}

@test "run no spice pass matches golden" {
  unset SPICE_PASS
  run ./spice
  [ "$status" -eq 1 ]
  diff -u tests/golden/run.no.spicepass.stdout.txt <(echo "$output")
}

@test "run invalid spice pass matches golden" {
  export SPICE_PASS="invalid_spice_pass"
  run ./spice
  [ "$status" -eq 1 ]
  diff -u tests/golden/run.invalid.spicepass.stdout.txt <(echo "$output" | sed "s/input:.*/input:/g" | sed "s/output:.*/output:/g")
}

# ...repeat for scan-artifacts, upload-adgs, upload-deployment-events
