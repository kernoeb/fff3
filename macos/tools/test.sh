#!/usr/bin/env bash
# Compiles the real protocol sources against tools/protocol-tests.swift and runs them.
set -euo pipefail
cd "$(dirname "$0")/.."

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# main.swift is the only file allowed top-level code.
cp tools/protocol-tests.swift "$work/main.swift"
swiftc -O -o "$work/tests" Sources/Fff3Core/Protocol/*.swift Sources/Fff3Core/Music/JMath.swift "$work/main.swift"
"$work/tests"
