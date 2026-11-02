#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${ROOT}/backend/target/transaction-compliance-evaluator-0.1.0.jar"

# 1. Ensure backend jar exists
if [ ! -f "$JAR" ]; then
  echo "[INFO] Building backend jar..." >&2
  "${ROOT}/mvnw" -f "${ROOT}/backend/pom.xml" package -DskipTests -q
fi

# 2. Ensure frontend bundle is built
if [ ! -f "${ROOT}/frontend/.output/server/index.mjs" ]; then
  echo "[INFO] Building frontend production bundle..." >&2
  npm --prefix "${ROOT}/frontend" run build
fi

# 3. Check if server is already running on port 3000
if curl -s -I http://127.0.0.1:3000 >/dev/null 2>&1; then
  echo "[INFO] Web app is already running and accessible at http://127.0.0.1:3000" >&2
  exit 0
fi

# 4. Start the server
export HOST=0.0.0.0
export PORT=3000
echo "[INFO] Starting Compliance Evaluator Web UI on http://localhost:3000..." >&2
exec node "${ROOT}/frontend/.output/server/index.mjs"
