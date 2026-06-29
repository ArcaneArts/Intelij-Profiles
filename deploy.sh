#!/usr/bin/env bash
# Build the plugin and install it into your latest local IntelliJ IDEA, then restart the IDE to load it.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew deployToIde "$@"
