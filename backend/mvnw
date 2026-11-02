#!/usr/bin/env bash
set -euo pipefail

# Find mvn on PATH or in standard user cache
if command -v mvn >/dev/null 2>&1; then
    exec mvn "$@"
fi

LOCAL_MVN=$(find "$HOME/.m2/wrapper/dists" -name "mvn" -type f -perm -111 2>/dev/null | head -n 1 || true)
if [ -n "$LOCAL_MVN" ] && [ -x "$LOCAL_MVN" ]; then
    exec "$LOCAL_MVN" "$@"
fi

echo "Error: Maven ('mvn') not found on PATH or in ~/.m2/wrapper/dists. Please install Maven or Java 17+." >&2
exit 1
