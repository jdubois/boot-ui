#!/usr/bin/env bash
set -euo pipefail

example_dir="$(cd "$(dirname "$0")" && pwd)"
source_root="$(cd "$example_dir/../.." && pwd)"
wrapper_root="$source_root"
if [[ -f "$example_dir/mvnw" ]]; then
  wrapper_root="$example_dir"
fi
case "${1:-}" in
  spring|quarkus) project="$example_dir/$1" ;;
  *) echo "Usage: bash examples/ci/bootstrap.sh spring|quarkus" >&2; exit 2 ;;
esac

# A generated kit already includes the wrapper; a source checkout reuses the root wrapper.
if [[ ! -f "$project/mvnw" ]]; then
  test -f "$wrapper_root/mvnw"
  test -f "$wrapper_root/.mvn/wrapper/maven-wrapper.properties"
  mkdir -p "$project/.mvn/wrapper"
  cp "$wrapper_root/mvnw" "$project/"
  if [[ -f "$wrapper_root/mvnw.cmd" ]]; then
    cp "$wrapper_root/mvnw.cmd" "$project/"
  fi
  cp "$wrapper_root/.mvn/wrapper/maven-wrapper.properties" "$project/.mvn/wrapper/"
  chmod +x "$project/mvnw"
fi
if [[ ! -f "$project/pom.xml" ]]; then
  # Only source-checkout bootstrapping. Released kits already contain rendered POMs.
  python3 - "$source_root" "$project" <<'PY'
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root, project = map(Path, sys.argv[1:])
properties = ET.parse(root / "pom.xml").getroot().find(
    "{http://maven.apache.org/POM/4.0.0}properties"
)
values = {
    "@BOOTUI_VERSION@": os.environ.get("BOOTUI_VERSION", "UNRELEASED"),
    "@SPRING_BOOT_VERSION@": properties.findtext(
        "{http://maven.apache.org/POM/4.0.0}spring-boot.version"
    ),
    "@QUARKUS_VERSION@": properties.findtext(
        "{http://maven.apache.org/POM/4.0.0}quarkus.platform.version"
    ),
}
text = (project / "pom.xml.template").read_text(encoding="utf-8")
for token, value in values.items():
    text = text.replace(token, value)
(project / "pom.xml").write_text(text, encoding="utf-8")
PY
fi
