#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
JAR="${ROOT}/backend/target/transaction-compliance-evaluator-0.1.0.jar"
DATA_DIR="${ROOT}/data"
DEFAULT_TX="${DATA_DIR}/transactions.json"
DEFAULT_RULES="${DATA_DIR}/rules.csv"

# Ensure runtime logs directory exists so Logback never fails on appender initialization
mkdir -p "${ROOT}/logs"

# 1. Check Node.js
if ! command -v node >/dev/null 2>&1; then
  echo "[ERROR] Node.js ('node') not found on PATH." >&2
  echo "[FIX]   Install Node.js 20 LTS on Ubuntu with:" >&2
  echo "        curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - && sudo apt install -y nodejs" >&2
  exit 1
fi

# 2. Check Java (required by Web API to execute backend compliance engine)
if ! command -v java >/dev/null 2>&1; then
  echo "[ERROR] Java 17+ is required by the Web API to run compliance evaluation." >&2
  echo "[FIX]   Install OpenJDK 17 on Ubuntu with:" >&2
  echo "        sudo apt update && sudo apt install -y openjdk-17-jre-headless" >&2
  exit 1
fi

# 3. Ensure backend jar exists
if [ ! -f "$JAR" ]; then
  echo "[INFO] Building backend jar..." >&2
  "${ROOT}/mvnw" -f "${ROOT}/backend/pom.xml" package -DskipTests -q
fi

# 4. Ensure frontend dependencies and production bundle are built
if [ ! -f "${ROOT}/frontend/.output/server/index.mjs" ]; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "[ERROR] 'npm' not found on PATH. Cannot build frontend bundle." >&2
    exit 1
  fi
  if [ ! -d "${ROOT}/frontend/node_modules" ]; then
    echo "[INFO] Installing frontend dependencies (npm ci)..." >&2
    npm --prefix "${ROOT}/frontend" ci --prefer-offline 2>/dev/null || npm --prefix "${ROOT}/frontend" install
  fi
  echo "[INFO] Building frontend production bundle..." >&2
  npm --prefix "${ROOT}/frontend" run build
fi

# 5. Check if server is already running on port 3000
port_is_open() {
  if command -v curl >/dev/null 2>&1; then
    curl -s -I http://127.0.0.1:3000 >/dev/null 2>&1
  elif (exec 3<>/dev/tcp/127.0.0.1/3000) 2>/dev/null; then
    exec 3<&-
    exec 3>&-
    return 0
  else
    return 1
  fi
}

if port_is_open; then
  echo "[INFO] Web app is already running and accessible at http://127.0.0.1:3000" >&2
  exit 0
fi

# 6. Start the server
export HOST=0.0.0.0
export PORT=3000
echo "[INFO] Starting Compliance Evaluator Web UI on http://localhost:3000..." >&2
exec node "${ROOT}/frontend/.output/server/index.mjs"
