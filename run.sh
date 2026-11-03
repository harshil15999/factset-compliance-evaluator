#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${ROOT}/backend/target/transaction-compliance-evaluator-0.1.0.jar"
DATA_DIR="${ROOT}/data"
DEFAULT_TX="${DATA_DIR}/transactions.json"
DEFAULT_RULES="${DATA_DIR}/rules.csv"

# Ensure runtime logs directory exists so Logback never fails on appender initialization
mkdir -p "${ROOT}/logs"

# Check for Java 17+
if ! command -v java >/dev/null 2>&1; then
  echo "[ERROR] Java runtime ('java') not found on PATH." >&2
  echo "[FIX]   Install OpenJDK 17 on Ubuntu with:" >&2
  echo "        sudo apt update && sudo apt install -y openjdk-17-jre-headless" >&2
  exit 1
fi

# Build backend jar if missing
if [ ! -f "$JAR" ]; then
  echo "[INFO] Building backend jar..." >&2
  "${ROOT}/mvnw" -f "${ROOT}/backend/pom.xml" package -DskipTests -q
fi

if [ "$#" -eq 0 ]; then
  # No arguments: evaluate default data files in data/
  exec java -jar "$JAR" "$DEFAULT_TX" "$DEFAULT_RULES"
elif [ "$1" = "schema" ]; then
  exec java -jar "$JAR" schema
elif [ "$1" = "validate" ]; then
  if [ "$#" -eq 3 ]; then
    exec java -jar "$JAR" validate "$2" "$3"
  else
    exec java -jar "$JAR" validate "$DEFAULT_TX" "$DEFAULT_RULES"
  fi
elif [ "$1" = "validate-rules" ]; then
  if [ "$#" -ge 2 ]; then
    exec java -jar "$JAR" validate-rules "$2"
  else
    exec java -jar "$JAR" validate-rules "$DEFAULT_RULES"
  fi
else
  exec java -jar "$JAR" "$@"
fi
