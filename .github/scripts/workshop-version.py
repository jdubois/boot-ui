#!/usr/bin/env python3
"""Keep the workshop's sample, CLI, dependency, and Java agent on one release."""

import argparse
from pathlib import Path
import re
import sys


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
PIN = re.compile(r"this edition pins \*\*(" + VERSION + r")\*\*")
REFERENCE = re.compile(
    r"(?:--branch v|--version |-Version |bootui-agent-|<version>)"
    r"([0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?)"
)


def synchronize(root, version, *, check):
    if not re.fullmatch(VERSION, version):
        raise ValueError(f"Invalid workshop release version: {version}")
    overview = root / "docs/workshop/README.md"
    pins = PIN.findall(overview.read_text(encoding="utf-8"))
    if len(pins) != 1:
        raise ValueError(f"{overview}: expected exactly one workshop edition pin")
    previous = pins[0]
    replacement = re.compile(r"(?<![0-9.])" + re.escape(previous) + r"(?![0-9]|\.[0-9])")
    contents = {}
    errors = []
    for directory in ("docs/workshop", "workshop"):
        files = sorted((root / directory).glob("*.md"))
        if not files:
            raise ValueError(f"{root / directory}: workshop Markdown files are missing")
        for path in files:
            content = path.read_text(encoding="utf-8")
            if not check:
                content = replacement.sub(lambda _: version, content)
            contents[path] = content
            for match in REFERENCE.finditer(content):
                if match[1] != version:
                    errors.append(f"{path.relative_to(root)}: expected {version}, found {match[0]}")
    if check and previous != version:
        errors.append(f"docs/workshop/README.md: edition pins {previous}, expected {version}")
    if errors:
        raise ValueError("\n".join(errors))
    if not check:
        for path, content in contents.items():
            if content != path.read_text(encoding="utf-8"):
                path.write_text(content, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("update", "check"))
    parser.add_argument("version")
    args = parser.parse_args()
    try:
        synchronize(Path(__file__).resolve().parents[2], args.version, check=args.mode == "check")
    except (OSError, ValueError) as error:
        print(f"Workshop version: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
