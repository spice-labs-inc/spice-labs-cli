#!/usr/bin/env bats

setup() {
  export SPICE_PASS=fake
  mkdir -p tmp
}

teardown() {
  rm -rf tmp
}

# smoke test: --command=run
@test "bash run matches golden" {
  run ./spice --command run --input tests/input/empty-dir --no-pull
  [ "$status" -eq 0 ]
  diff -u tests/golden/run.stdout.txt <(echo "$output")
}

# ...repeat for scan-artifacts, upload-adgs, upload-deployment-events
