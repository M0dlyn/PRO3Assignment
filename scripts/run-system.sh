#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=target/traceability-1.0.0.jar
if [[ ! -f "$JAR" ]]; then echo 'Build first: mvn clean verify' >&2; exit 1; fi
# Local demonstration credentials only. Override these variables for your own deployment.
export BROKER_TOKEN="${BROKER_TOKEN:-local-demo-broker}"
export RECALL_TOKEN="${RECALL_TOKEN:-local-demo-recall}"
mkdir -p data/logs
pids=()
cleanup() {
  trap - EXIT INT TERM
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT TERM
for role in broker receiving cutting packing central; do
  java -cp "$JAR" dk.via.pro3.system.SystemMain "$role" > "data/logs/$role.log" 2>&1 &
  pids+=("$!")
done
echo 'Starting broker :8070, receiving :8081, cutting :8082, packing :8083, central HTTP :8084 and gRPC :9090.'
echo 'Logs: data/logs/. Use another terminal: python3 scripts/demo.py. Ctrl+C stops only these processes.'
while true; do
  for pid in "${pids[@]}"; do
    if ! kill -0 "$pid" 2>/dev/null; then echo 'A service exited; inspect data/logs/.' >&2; exit 1; fi
  done
  sleep 1
done
