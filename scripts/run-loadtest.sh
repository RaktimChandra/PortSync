#!/usr/bin/env bash
# Runs the measured load test against a running stack and prints the report.
# Usage: scripts/run-loadtest.sh [events] [rate]   (defaults: 20000 events, rate 0 = burst)
set -euo pipefail
EVENTS=${1:-20000}
RATE=${2:-0}
LOADTEST_EVENTS=$EVENTS LOADTEST_RATE=$RATE docker compose --profile loadtest run --rm loadtest
echo
ls -1t loadtest-results/*.md | head -1 | xargs cat
