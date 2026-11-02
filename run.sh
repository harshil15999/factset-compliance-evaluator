#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${ROOT}/backend/target/transaction-compliance-evaluator-0.1.0.jar"

# Build backend jar if missing
if [ ! -f "$JAR" ]; then
  echo "[INFO] Building backend jar..." >&2
  "${ROOT}/mvnw" -f "${ROOT}/backend/pom.xml" package -DskipTests -q
fi

# Execute compliance evaluator with provided files or default customer files
if [ "$#" -eq 0 ]; then
  # Auto-start Web UI daemon in background if not already active
  if ! nc -z 127.0.0.1 3000 2>/dev/null && ! curl -s http://127.0.0.1:3000/ >/dev/null 2>&1; then
    if [ -f "${ROOT}/frontend/.output/server/index.mjs" ]; then
      HOST=0.0.0.0 PORT=3000 nohup node "${ROOT}/frontend/.output/server/index.mjs" >/tmp/compliance-ui.log 2>&1 &
      sleep 1
    fi
  fi

  echo "[INFO] Web UI Dashboard: http://localhost:3000" >&2
  echo "[INFO] Pyroscope Profiler: http://localhost:4040" >&2

  # Attempt to launch default browser if graphical display environment is present
  if [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; then
    (xdg-open "http://localhost:3000" >/dev/null 2>&1 || true) &
  fi

  exec java -jar "$JAR" "${ROOT}/transactions.json" "${ROOT}/rules.csv"
else
  exec java -jar "$JAR" "$@"
fi
