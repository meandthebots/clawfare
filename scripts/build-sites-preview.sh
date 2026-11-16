#!/usr/bin/env bash
set -euo pipefail

# Build a portable read-only sample HTML page, with no provider calls and no
# access to user data. The database is disposable and never leaves this job.
out="build/sites/clawfare-sample.html"
if [ "$#" -ge 1 ]; then out="$1"; fi
mkdir -p "$(dirname "$out")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export CLAWFARE_DB="$work/data.db"

# Explicitly deny API credentials in the preview generation process.
export SERPAPI_API_KEY=""
export SEARCHAPI_API_KEY=""
export TRAVELPAYOUTS_API_TOKEN=""
export DUFFEL_ACCESS_TOKEN=""
export DUFFEL_LIVE_ENABLED="false"

./gradlew run --args="fare load-sample"
./gradlew run --args="report html florida-dec-2026 --out $out"

test -s "$out"
grep -q "florida-dec-2026" "$out"
if grep -q 'window.__SNAPSHOT__ = null' "$out"; then
    echo "Expected a self-contained snapshot, not a live API frontend" >&2
    exit 1
fi
printf 'Created static Clawfare demo: %s\n' "$out"
