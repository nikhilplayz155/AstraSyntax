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
CLASSES="build/classes"
VERSION="1.21.11-26.2"

mkdir -p "$CLASSES" build/test-classes build/test-stubs "$RUNNER"

echo "[1/6] main sources"
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
    -cp "$SPIGOT" -d "$CLASSES" $(find src/main/java -name '*.java')

echo "[2/6] main resources (Maven/Gradle do this in process-resources)"
python3 - "$CLASSES" "$VERSION" <<'PY'
import pathlib, shutil, sys
out = pathlib.Path(sys.argv[1])
version = sys.argv[2]
src = pathlib.Path('src/main/resources')
copied = 0
for path in sorted(src.rglob('*')):
    if path.is_dir():
        continue
    target = out / path.relative_to(src)
    target.parent.mkdir(parents=True, exist_ok=True)
    if path.name == 'plugin.yml':
        target.write_text(path.read_text(encoding='utf-8').replace('${project.version}', version), encoding='utf-8')
    else:
        shutil.copy2(path, target)
    copied += 1
print("    " + str(copied) + " resource(s)")
PY

echo "[3/6] sandbox guava shim (test-only, never packaged)"
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
    -d build/test-stubs $(find tools/sandbox/guava-shim -name '*.java')

echo "[4/6] junit launcher"
if [ ! -f "$RUNNER/TestRunner.class" ] || [ tools/sandbox/TestRunner.java -nt "$RUNNER/TestRunner.class" ]; then
    "$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
        -cp "$SPIGOT:$JUNIT" -d "$RUNNER" tools/sandbox/TestRunner.java
fi

echo "[5/6] tests"
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -source 21 -target 21 -encoding UTF-8 -nowarn \
    -cp "$CLASSES:build/test-stubs:$JUNIT" -d build/test-classes $(find src/test/java -name '*.java')

echo "[6/6] running JUnit"
"$JAVA" -cp "build/test-classes:$CLASSES:$RUNNER:build/test-stubs:$SPIGOT:$JUNIT" TestRunner build/test-classes
