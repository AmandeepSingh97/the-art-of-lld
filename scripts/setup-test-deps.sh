#!/usr/bin/env bash
# Downloads the JUnit 5 Platform Console Standalone launcher used to compile
# and run the test suites in every problem directory of this repo.
#
# Usage:
#   ./scripts/setup-test-deps.sh
#
# This writes junit-platform-console-standalone.jar into tools/ at the repo
# root. Each problem's IMPLEMENTATION_GUIDE.md / README.md test instructions
# assume the jar is reachable via a relative path from that problem's
# directory (e.g. ../tools/junit-platform-console-standalone.jar).
set -euo pipefail

JUNIT_VERSION="1.10.2"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${REPO_ROOT}/tools"
TARGET_JAR="${TARGET_DIR}/junit-platform-console-standalone.jar"

mkdir -p "${TARGET_DIR}"

if [ -f "${TARGET_JAR}" ]; then
  echo "Already present: ${TARGET_JAR}"
  exit 0
fi

URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar"

echo "Downloading JUnit ${JUNIT_VERSION} console standalone jar..."
curl -fL -o "${TARGET_JAR}" "${URL}"
echo "Done: ${TARGET_JAR}"
