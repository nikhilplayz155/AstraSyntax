#!/usr/bin/env bash
#
# AstraSyntax - offline test runner (fallback path)
#
# Maven / Gradle run the same suite:
#   mvn -B test        gradle test
#
# Usage: tools/run-tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime}"
JAVA="$JAVA_HOME/bin/java"
ECJ="tools/org.eclipse.jdt.core.compiler.batch_3.45.0.v20260224-0835.jar"
SPIGOT="libs/spigot-api-1.21.jar"
JUNIT="$(ls libs/junit-*.jar libs/opentest4j-*.jar libs/apiguardian-*.jar | tr '\n' ':')"
RUNNER="build/test-runner"

mkdir -p build/classes build/test-classes build/test-stubs "$RUNNER"

echo "[1/5] main sources"
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
    -cp "$SPIGOT" -d build/classes $(find src/main/java -name '*.java')

echo "[2/5] sandbox guava shim (test-only, never packaged)"
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
    -d build/test-stubs $(find tools/sandbox/guava-shim -name '*.java')

echo "[3/5] junit launcher"
if [ ! -f "$RUNNER/TestRunner.class" ] || [ tools/sandbox/TestRunner.java -nt "$RUNNER/TestRunner.class" ]; then
    "$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
        -cp "$SPIGOT:$JUNIT" -d "$RUNNER" tools/sandbox/TestRunner.java
fi

echo "[4/5] tests"
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
    -cp "build/classes:build/test-stubs:$JUNIT" -d build/test-classes $(find src/test/java -name '*.java')

echo "[5/5] running JUnit"
"$JAVA" -cp "build/test-classes:build/classes:$RUNNER:build/test-stubs:$SPIGOT:$JUNIT" TestRunner build/test-classes
