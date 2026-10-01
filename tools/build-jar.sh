#!/usr/bin/env bash
#
# AstraSyntax - offline jar build (fallback path)
#
# Maven and Gradle produce the release jar:
#   mvn -B clean package          -> target/AstraSyntax-1.21.11-26.2.jar
#   gradle clean build            -> build/libs/AstraSyntax-1.21.11-26.2.jar
#
# This script reproduces exactly what those builds do, using only the tools that
# are available in an offline sandbox (the Eclipse batch compiler in tools/ and
# Python's zipfile). It is intentionally dependency-free so a build can always
# be verified even when Maven Central, the Gradle distribution or the plugin
# portal are unreachable.
#
# Steps (identical in order to `mvn package`):
#   1. compile src/main/java with the Eclipse compiler (release 21, UTF-8)
#   2. copy src/main/resources, expanding ${project.version} in plugin.yml
#   3. stage the runtime JDBC jars into astra/lib/
#   4. package dist/AstraSyntax-<version>.jar with a real manifest
#
# Usage: tools/build-jar.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-1.21.11-26.2}"
JAVA_HOME="${JAVA_HOME:-/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime}"
JAVA="$JAVA_HOME/bin/java"
ECJ="tools/org.eclipse.jdt.core.compiler.batch_3.45.0.v20260224-0835.jar"
SPIGOT="libs/spigot-api-1.21.jar"
OUT_CLASSES="build/classes"
DIST="dist/AstraSyntax-${VERSION}.jar"

[ -x "$JAVA" ] || { echo "No java runtime at $JAVA (set JAVA_HOME)" >&2; exit 1; }
[ -f "$ECJ" ] || { echo "Missing $ECJ (the offline compiler)" >&2; exit 1; }
[ -f "$SPIGOT" ] || { echo "Missing $SPIGOT (the server API)" >&2; exit 1; }

echo "[1/4] compiling main sources"
rm -rf "$OUT_CLASSES"
mkdir -p "$OUT_CLASSES"
# shellcheck disable=SC2046
"$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 21 -target 21 -encoding UTF-8 -nowarn \
    -cp "$SPIGOT" -d "$OUT_CLASSES" $(find src/main/java -name '*.java')

echo "[2/4] copying resources (plugin.yml version -> $VERSION)"
python3 - "$VERSION" "$OUT_CLASSES" <<'PY'
import pathlib, shutil, sys
version, out = sys.argv[1], pathlib.Path(sys.argv[2])
src = pathlib.Path('src/main/resources')
for path in sorted(src.rglob('*')):
    if path.is_dir():
        continue
    target = out / path.relative_to(src)
    target.parent.mkdir(parents=True, exist_ok=True)
    if path.name == 'plugin.yml':
        target.write_text(path.read_text(encoding='utf-8').replace('${project.version}', version), encoding='utf-8')
    else:
        shutil.copy2(path, target)
print("    resources:", sum(1 for p in out.rglob('*') if p.is_file() and p.suffix in {'.yml', '.ar'}), "file(s)")
PY

echo "[3/4] staging JDBC drivers into astra/lib"
mkdir -p "$OUT_CLASSES/astra/lib"
for jar in libs/sqlite-jdbc-*.jar libs/mysql-connector-j-*.jar; do
    [ -f "$jar" ] || continue
    cp "$jar" "$OUT_CLASSES/astra/lib/"
    echo "    + astra/lib/$(basename "$jar")"
done

echo "[4/4] packaging $DIST"
mkdir -p dist
python3 - "$DIST" "$OUT_CLASSES" "$VERSION" <<'PY'
import hashlib, pathlib, sys, zipfile

target, classes, version = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]

# Fixed timestamp + sorted entries + fixed permissions: two builds from the same
# sources produce the same jar, byte for byte (the sha256 below is reproducible).
STAMP = (2026, 1, 1, 0, 0, 0)

def write(jar, name, data):
    info = zipfile.ZipInfo(name, date_time=STAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    jar.writestr(info, data)

manifest = "\r\n".join([
    'Manifest-Version: 1.0',
    'Implementation-Title: AstraSyntax',
    f'Implementation-Version: {version}',
    'Implementation-Vendor: AstraLab - LivingCritz DevSolentz',
    'Astra-Target: 1.21.11-26.2',
    'Built-By: tools/build-jar.sh (offline Eclipse compiler path)',
    '', '',
]).encode('utf-8')
files = sorted(p for p in classes.rglob('*') if p.is_file())
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as jar:
    write(jar, 'META-INF/MANIFEST.MF', manifest)
    for path in files:
        write(jar, path.relative_to(classes).as_posix(), path.read_bytes())
digest = hashlib.sha256(target.read_bytes()).hexdigest()
print(f"    {target}: {len(files)} entries, {target.stat().st_size} bytes")
print(f"    sha256 {digest}")
PY
echo "done: $DIST"
