#!/usr/bin/env bash
# Builds the Profiles plugin and drops the installable zip into OUT/.
# Usage: ./build.sh   (any extra args are passed through to Gradle)
set -euo pipefail
cd "$(dirname "$0")"

./gradlew buildOut "$@"

echo
echo "Plugin written to: $(pwd)/OUT"
ls -1 OUT
