#!/usr/bin/env bash
# Runs Maven inside a JDK 17 container so the host only needs Docker.
# The Docker socket is mounted so Testcontainers can start sibling containers.
# docker-java in Testcontainers 1.20.x defaults to Docker API 1.32, which Docker Engine 29+ rejects;
# api.version=1.44 (via ~/.docker-java.properties) fixes that and is accepted by every Engine >= 25.
# Usage: scripts/mvn.sh -q verify
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOCKET="/var/run/docker.sock"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    # Git Bash on Windows: stop path mangling and use the Windows-style path for the bind mount.
    export MSYS_NO_PATHCONV=1
    PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd -W)"
    SOCKET="//var/run/docker.sock"
    ;;
esac

TTY_ARGS=()
if [ -t 0 ] && [ -t 1 ]; then TTY_ARGS=(-it); fi

exec docker run --rm "${TTY_ARGS[@]}" \
  --add-host=host.docker.internal:host-gateway \
  -v "$SOCKET:/var/run/docker.sock" \
  -v "$PROJECT_DIR:/workspace" \
  -v sensor-platform-m2:/root/.m2 \
  -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-17 \
  sh -c 'echo "api.version=1.44" > /root/.docker-java.properties; exec mvn -B "$@"' mvn "$@"
