#!/usr/bin/env python3
"""Assemble the Maven Central upload bundle from a local Maven repository.

Usage: assemble_central_bundle.py LOCAL_REPOSITORY VERSION OUTPUT_ZIP

The release workflow installs the publication reactor and then bundles only the files each published
module's version directory holds for that version. Resolver bookkeeping (``_remote.repositories``,
``maven-metadata-local.xml``, ``*.lastUpdated``) never reaches the bundle: Maven Central rejects any bundle
directory without a POM, which is exactly what the central-publishing-maven-plugin produced under Maven
3.10, whose installer writes that bookkeeping into the plugin's staging directory.

Only the md5 and sha1 checksums Central requires are generated; signatures get none.
"""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

GROUP_PATH = "com/julien-dubois/bootui"

# Every published artifact. Keep in sync with the release workflow's publication reactor and its Maven
# Central availability poll list; check-release-integrity.sh enforces the latter.
ARTIFACT_IDS = (
    "bootui-parent",
    "bootui-core",
    "bootui-engine",
    "bootui-spring-autoconfigure",
    "bootui-spring-boot-starter",
    "bootui-spring-boot-starter-reactive",
    "bootui-ui",
    "bootui-quarkus-parent",
    "bootui-quarkus",
    "bootui-quarkus-deployment",
    "bootui-client",
    "bootui-cli",
)

CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
RESOLVER_BOOKKEEPING = ("_remote.repositories", "maven-metadata")


class BundleError(Exception):
    pass


def _is_bookkeeping(name: str) -> bool:
    return name.startswith(RESOLVER_BOOKKEEPING) or name.endswith(".lastUpdated")


def _publishable_files(version_dir: Path, artifact_id: str, version: str) -> list[Path]:
    prefix = f"{artifact_id}-{version}"
    files = []
    for path in sorted(version_dir.iterdir()):
        name = path.name
        if not path.is_file() or _is_bookkeeping(name) or name.endswith(CHECKSUM_SUFFIXES):
            continue
        if not (name.startswith(prefix + ".") or name.startswith(prefix + "-")):
            raise BundleError(f"unexpected file in {version_dir}: {name}")
        files.append(path)
    return files


def _check_module(artifact_id: str, version: str, names: set[str]) -> None:
    prefix = f"{artifact_id}-{version}"
    if f"{prefix}.pom" not in names:
        raise BundleError(f"{artifact_id} {version} has no POM")
    if f"{prefix}.jar" in names:
        for classifier in ("sources", "javadoc"):
            if f"{prefix}-{classifier}.jar" not in names:
                raise BundleError(f"{artifact_id} {version} has no {classifier} jar")
    for name in names:
        if not name.endswith(".asc") and f"{name}.asc" not in names:
            raise BundleError(f"{artifact_id} {version} has an unsigned file: {name}")


def assemble(local_repository: Path, version: str, output: Path) -> list[str]:
    entries: list[tuple[str, bytes]] = []
    for artifact_id in ARTIFACT_IDS:
        relative_dir = f"{GROUP_PATH}/{artifact_id}/{version}"
        version_dir = local_repository / relative_dir
        if not version_dir.is_dir():
            raise BundleError(f"{artifact_id} {version} is not installed in {local_repository}")
        files = _publishable_files(version_dir, artifact_id, version)
        _check_module(artifact_id, version, {path.name for path in files})
        for path in files:
            content = path.read_bytes()
            entry = f"{relative_dir}/{path.name}"
            entries.append((entry, content))
            if not path.name.endswith(".asc"):
                entries.append((f"{entry}.md5", hashlib.md5(content).hexdigest().encode()))
                entries.append((f"{entry}.sha1", hashlib.sha1(content).hexdigest().encode()))

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for entry, content in entries:
            bundle.writestr(entry, content)
    return [entry for entry, _ in entries]


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    local_repository, version, output = Path(argv[1]), argv[2], Path(argv[3])
    try:
        entries = assemble(local_repository, version, output)
    except BundleError as error:
        print(f"::error::Cannot assemble the Maven Central bundle: {error}", file=sys.stderr)
        return 1
    for entry in entries:
        print(entry)
    print(f"Assembled {len(entries)} files for {len(ARTIFACT_IDS)} artifacts into {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
