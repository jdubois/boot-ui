#!/usr/bin/env python3
"""Assemble the Maven Central upload bundle from a local Maven repository.

Usage: assemble_central_bundle.py [--unsigned] LOCAL_REPOSITORY VERSION OUTPUT_ZIP

The release workflow installs the publication reactor and then bundles only the files each published
module's version directory holds for that version. The coordinates are check-central-bundle.py's PUBLISHED, an
allow-list: bootui-agent-bridge, which bootui-agent shades, and the two parent POMs are installed with the
reactor but never bundled, and every bundled module carries the flattened, parentless POM flatten-maven-plugin
installed. check-central-bundle.py then checks the bundle's coordinates and POMs.

--unsigned is for stage-release-candidate.sh only, which builds without signing; release.yml never passes it. Resolver bookkeeping (``_remote.repositories``,
``maven-metadata-local.xml``, ``*.lastUpdated``) never reaches the bundle: Maven Central rejects any bundle
directory without a POM, which is exactly what the central-publishing-maven-plugin produced under Maven
3.10, whose installer writes that bookkeeping into the plugin's staging directory.

Only the md5 and sha1 checksums Central requires are generated; signatures get none.
"""

from __future__ import annotations

import hashlib
import importlib.util
import sys
import zipfile
from pathlib import Path

GROUP_PATH = "com/julien-dubois/bootui"



def _load_bundle_check():
    path = Path(__file__).resolve().with_name("check-central-bundle.py")
    spec = importlib.util.spec_from_file_location("check_central_bundle", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Every published artifact: the bundle checker's list, the one the assembler, the checker and their tests read.
# check-release-integrity.sh keeps it equal to its PUBLISHED_ARTIFACTS, the availability poll list, and
# consumer-smoke-tests.sh.
ARTIFACT_IDS = _load_bundle_check().PUBLISHED

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


def _check_module(artifact_id: str, version: str, names: set[str], signed: bool = True) -> None:
    prefix = f"{artifact_id}-{version}"
    if f"{prefix}.pom" not in names:
        raise BundleError(f"{artifact_id} {version} has no POM")
    if f"{prefix}.jar" in names:
        for classifier in ("sources", "javadoc"):
            if f"{prefix}-{classifier}.jar" not in names:
                raise BundleError(f"{artifact_id} {version} has no {classifier} jar")
    for name in names:
        if signed and not name.endswith(".asc") and f"{name}.asc" not in names:
            raise BundleError(f"{artifact_id} {version} has an unsigned file: {name}")
        if not signed and name.endswith(".asc"):
            raise BundleError(f"{artifact_id} {version} is signed, but the bundle was assembled as unsigned: {name}")


def assemble(local_repository: Path, version: str, output: Path, signed: bool = True) -> list[str]:
    entries: list[tuple[str, bytes]] = []
    for artifact_id in ARTIFACT_IDS:
        relative_dir = f"{GROUP_PATH}/{artifact_id}/{version}"
        version_dir = local_repository / relative_dir
        if not version_dir.is_dir():
            raise BundleError(f"{artifact_id} {version} is not installed in {local_repository}")
        files = _publishable_files(version_dir, artifact_id, version)
        _check_module(artifact_id, version, {path.name for path in files}, signed)
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
    arguments = argv[1:]
    signed = True
    if arguments and arguments[0] == "--unsigned":
        signed = False
        arguments = arguments[1:]
    if len(arguments) != 3:
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    local_repository, version, output = Path(arguments[0]), arguments[1], Path(arguments[2])
    try:
        entries = assemble(local_repository, version, output, signed)
    except BundleError as error:
        print(f"::error::Cannot assemble the Maven Central bundle: {error}", file=sys.stderr)
        return 1
    for entry in entries:
        print(entry)
    print(f"Assembled {len(entries)} files for {len(ARTIFACT_IDS)} artifacts into {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
