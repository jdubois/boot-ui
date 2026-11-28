#!/usr/bin/env python3
"""Replace next-release Quarkus 4 installation markers before sealing a release."""

import argparse
from pathlib import Path
import re
import sys

DOCUMENTS = (
    "README.md",
    "docs/FRAMEWORK-SUPPORT.md",
    "docs/setup/quarkus.md",
    "docs/QUARKUS-SUPPORT.md",
    "docs/REPOSITORY.md",
    "skills/bootui/SKILL.md",
    "plugins/bootui/skills/bootui/SKILL.md",
)
START = "<!-- bootui-quarkus-4:next-release:start -->"
END = "<!-- bootui-quarkus-4:next-release:end -->"
TOKEN = "NEXT_BOOTUI_RELEASE"
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
BLOCK = re.compile(re.escape(START) + r"\n[^\0]*?" + re.escape(END) + r"\n?")
XML = re.compile(
    r"(<artifactId>bootui-quarkus-4(?:-deployment)?</artifactId>\s*<version>)"
    rf"({VERSION}|{TOKEN})(</version>)"
)
COORDINATE = re.compile(
    r"(com\.julien-dubois\.bootui:bootui-quarkus-4(?:-deployment)?:)"
    rf"({VERSION}|{TOKEN})(?![0-9A-Za-z_.-])"
)


def synchronize(root, version, *, check):
    if re.fullmatch(VERSION, version) is None:
        raise ValueError("Expected a stable release version")
    changes = {}
    for relative in DOCUMENTS:
        path = root / relative
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"Invalid release documentation destination: {relative}")
        content = path.read_text(encoding="utf-8")
        blocks = list(BLOCK.finditer(content))
        if content.count(START) != len(blocks) or content.count(END) != len(blocks):
            raise ValueError(f"Malformed Quarkus 4 next-release block in {relative}")
        updated = BLOCK.sub("", content)
        updated = XML.sub(lambda match: match[1] + version + match[3], updated)
        updated = COORDINATE.sub(lambda match: match[1] + version, updated)
        if TOKEN in updated:
            raise ValueError(f"Unbound Quarkus 4 version marker in {relative}")
        if updated != content:
            changes[path] = updated
    if check and changes:
        raise ValueError("Quarkus 4 release documentation is stale: " + ", ".join(str(path.relative_to(root)) for path in changes))
    if not check:
        # Validate every document before writing any: malformed markers cannot leave a partial bump.
        for path, content in changes.items():
            path.write_text(content, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("update", "check"))
    parser.add_argument("version")
    args = parser.parse_args()
    try:
        synchronize(Path(__file__).resolve().parents[2], args.version, check=args.mode == "check")
    except (OSError, ValueError) as error:
        print(f"Quarkus 4 release documentation: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
