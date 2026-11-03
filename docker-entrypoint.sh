#!/usr/bin/env bash
set -e

# Support direct commands or convenience subcommands
if [ "$1" = "web" ] || [ "$1" = "./start-web.sh" ] || [ "$1" = "start-web" ]; then
    shift || true
    exec ./start-web.sh "$@"
elif [ "$1" = "run" ] || [ "$1" = "./run.sh" ]; then
    shift || true
    exec ./run.sh "$@"
elif [ "$1" = "schema" ] || [ "$1" = "validate" ] || [ "$1" = "validate-rules" ]; then
    exec ./run.sh "$@"
elif [ "$1" = "./mvnw" ] || [ "$1" = "mvnw" ]; then
    shift || true
    exec ./mvnw "$@"
elif [ "$1" = "mvn" ]; then
    shift || true
    exec mvn "$@"
elif [ "$1" = "test" ]; then
    shift || true
    exec ./mvnw -f backend/pom.xml test "$@"
elif [ "$1" = "bash" ] || [ "$1" = "sh" ]; then
    exec "$@"
else
    # Allow passing custom transactions and rules arguments directly to run.sh
    exec ./run.sh "$@"
fi
