#!/usr/bin/env bash
#
# AstraSyntax - source archive build
#
# Produces dist/AstraSyntax-<version>-src.zip: the complete source tree that the jar was
# built from (sources, resources, the nine configs, examples, tests, build files, tools).
#
# Excluded on purpose:
#   build/ target/ dist/ .git/   generated output
#   libs/                        dependency jars (Maven/Gradle fetch them; see README §14)
#   tools/*.jar                  the offline Eclipse compiler
#
# Usage: tools/build-src-zip.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-1.21.11-26.2}"
OUT="dist/AstraSyntax-${VERSION}-src.zip"
mkdir -p dist

python3 - "$OUT" "$VERSION" <<'PY'
import hashlib, pathlib, sys, zipfile

target, version = pathlib.Path(sys.argv[1]), sys.argv[2]
root = pathlib.Path('.')

SKIP_DIRS = {'build', 'target', 'dist', '.git', 'libs', '.gradle', '.idea'}
STAMP = (2026, 1, 1, 0, 0, 0)

def included(path):
    if any(part in SKIP_DIRS for part in path.parts):
        return False
    if path.suffix == '.jar' or path.name.endswith('.7z'):
        return False
    return True

files = sorted(p for p in root.rglob('*') if p.is_file() and included(p))

note = (
    "AstraSyntax " + version + " - source archive\r\n"
    "=================================================\r\n"
    "\r\n"
    "This archive contains everything the plugin was built from: src/main/java (sources),\r\n"
    "src/main/resources (plugin.yml + the nine supplied configs + examples), src/test/java\r\n"
    "(the JUnit suite), pom.xml, build.gradle, settings.gradle, tools/ and this README.\r\n"
    "\r\n"
    "Build the jar:\r\n"
    "  mvn -B clean package    -> target/AstraSyntax-" + version + ".jar\r\n"
    "  gradle clean build      -> build/libs/AstraSyntax-" + version + ".jar\r\n"
    "\r\n"
    "Excluded from this archive: build/, target/, dist/, .git/, and the third-party\r\n"
    "dependency jars in libs/ (Maven/Gradle download them; see README section 14).\r\n"
    "The offline fallback build (tools/build-jar.sh) additionally needs libs/*.jar and\r\n"
    "tools/org.eclipse.jdt.core.compiler.batch_*.jar, which are not redistributed here.\r\n"
).encode('utf-8')

with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as zip_file:
    info = zipfile.ZipInfo('SOURCE-ZIP.txt', date_time=STAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zip_file.writestr(info, note)
    for path in files:
        data = path.read_bytes()
        info = zipfile.ZipInfo(path.as_posix(), date_time=STAMP)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o755 if path.suffix == '.sh' else 0o644) << 16
        zip_file.writestr(info, data)

digest = hashlib.sha256(target.read_bytes()).hexdigest()
# A sidecar checksum keeps the number verifiable without making the archive self-referential.
checksum = target.with_suffix(target.suffix + '.sha256')
checksum.write_text(f"{digest}  {target.name}\n", encoding='utf-8')
print(f"    {target}: {len(files) + 1} entries, {target.stat().st_size} bytes")
print(f"    {checksum}: {digest}")
PY
echo "done: $OUT"
