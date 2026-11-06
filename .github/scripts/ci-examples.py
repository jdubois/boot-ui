#!/usr/bin/env python3
"""Render CI documentation from the example sources and prepare the released starter kit."""

import argparse
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

DOCUMENTS = ("README.md", "docs/CI.md", "docs/CLI.md", "docs/ci/framework-tests.md", "docs/ci/reference.md")
REGION = re.compile(
    r"<!-- bootui-ci:(release|kit|source:[A-Za-z0-9_./-]+):start -->\n"
    r"[\s\S]*?<!-- bootui-ci:\1:end -->"
)
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
ASSET = "docs/.vuepress/public/ci/bootui-ci-examples.zip"
EXCLUDED = {"target", ".m2", ".git", "node_modules", "__pycache__"}
SOURCE_SUFFIXES = {".template", ".java", ".json", ".md", ".xml", ".yml", ".yaml", ".properties", ".sh", ".py"}


def release_record(root):
    record = json.loads((root / "docs/ci/release.json").read_text(encoding="utf-8"))
    if record.get("schemaVersion") != 1 or type(record.get("available")) is not bool:
        raise ValueError("Invalid CI documentation release schema")
    if not re.fullmatch(r"[0-9a-f]{40}", record.get("actionRef", "")):
        raise ValueError("CI Action reference must be a full immutable commit SHA")
    version = record.get("version")
    if record["available"]:
        if not isinstance(version, str) or not VERSION.fullmatch(version):
            raise ValueError("CI documentation requires a stable release version")
        if tuple(map(int, version.split("."))) <= (2, 3, 0):
            raise ValueError("Published 2.3.0 and earlier do not provide these CI commands")
    elif version is not None:
        raise ValueError("Unpublished CI documentation must not name a released CI version")
    return record


def substitutions(root, record):
    project = ET.parse(root / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    values = {
        "@BOOTUI_VERSION@": record["version"] if record["available"] else "UNRELEASED",
        "@BOOTUI_ACTION_REF@": record["actionRef"],
    }
    for token, property_name in (
        ("@SPRING_BOOT_VERSION@", "spring-boot.version"),
        ("@QUARKUS_VERSION@", "quarkus.platform.version"),
    ):
        value = project.findtext(f"m:properties/m:{property_name}", namespaces=ns)
        if not value:
            raise ValueError(f"Missing source property {property_name}")
        values[token] = value
    return values


def render_source(root, relative, values):
    source_root = (root / "examples/ci").resolve()
    source = source_root / relative
    if not source.resolve().is_relative_to(source_root) or source.is_symlink() or not source.is_file():
        raise ValueError(f"Invalid or missing CI example source: {relative}")
    content = source.read_text(encoding="utf-8")
    for token, value in values.items():
        content = content.replace(token, value)
    if re.search(r"@[A-Z][A-Z0-9_]*@", content):
        raise ValueError(f"Unknown version token in {relative}")
    return content


def release_notice(record):
    if not record["available"]:
        return (
            "> **CI support is not yet published.** Published BootUI CLI 2.3.0 does not contain these CI commands. "
            "The examples below are prepared for the first signed CI-capable release; `UNRELEASED` is not an "
            "installable version. Do not substitute the old published CLI.\n"
        )
    return (
        f"> **Example release: BootUI {record['version']}.** Use a published copy of this same release for the application and CLI. "
        "The Action is pinned independently to a reviewed immutable source revision. The documentation and starter "
        "kit are synchronized during release preparation; a repository can contain them before publication. "
        "The documentation website publishes the kit only after the exact CI release is verified as available.\n"
    )


def render_region(root, key, record, values):
    if key == "release":
        return release_notice(record)
    if key == "kit":
        if not record["available"]:
            return (
                "The starter-kit download becomes available with the first CI-capable release. "
                "The complete example sources are in the repository's `examples/ci` directory; "
                "candidate testing is separate from a published-release installation.\n"
            )
        return (
            "[Download the complete starter kit]"
            "(https://www.julien-dubois.com/boot-ui/ci/bootui-ci-examples.zip). "
            "It includes the projects, Maven Wrapper, policies, scripts, and selectable workflow files. "
            "Extract it into a new repository to try the example, or copy only the diagnostic configuration "
            "and workflow you need into an existing project.\n"
        )
    relative = key.removeprefix("source:")
    extension = Path(relative.removesuffix(".template")).suffix
    language = {
        ".java": "java", ".xml": "xml", ".json": "json", ".sh": "bash",
        ".yml": "yaml", ".yaml": "yaml", ".properties": "properties",
    }.get(extension, "text")
    return f"```{language}\n{render_source(root, relative, values).rstrip()}\n```\n"


def synchronize(root, *, check):
    record = release_record(root)
    values = substitutions(root, record)
    changes = {}
    for relative in DOCUMENTS:
        path = root / relative
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"Invalid CI documentation destination: {relative}")
        content = path.read_text(encoding="utf-8")
        matches = list(REGION.finditer(content))
        if content.count("<!-- bootui-ci:") != 2 * len(matches):
            raise ValueError(f"Malformed CI generated region in {relative}")
        keys = [match[1] for match in matches]
        if len(keys) != len(set(keys)):
            raise ValueError(f"Duplicate CI generated region in {relative}")
        rendered = REGION.sub(
            lambda match: f"<!-- bootui-ci:{match[1]}:start -->\n"
            f"{render_region(root, match[1], record, values)}"
            f"<!-- bootui-ci:{match[1]}:end -->",
            content,
        )
        if rendered != content:
            changes[path] = rendered
    if check and changes:
        raise ValueError(
            "CI documentation is stale; run python3 .github/scripts/ci-examples.py render: "
            + ", ".join(str(path.relative_to(root)) for path in changes)
        )
    if not check:
        for path, content in changes.items():
            path.write_text(content, encoding="utf-8")


def kit_entries(root, values):
    """Render source entries for the published archive or an internal candidate-only test."""
    sources = root / "examples/ci"
    entries = {}
    for source in sorted(sources.rglob("*")):
        relative = source.relative_to(sources)
        if any(part in EXCLUDED for part in relative.parts) or not source.is_file():
            continue
        if any(part.startswith(".") and part not in {".github", ".gitignore"} for part in relative.parts):
            continue
        if source.name != ".gitignore" and source.suffix not in SOURCE_SUFFIXES:
            continue
        if source.is_symlink() or source.suffix in {".jar", ".class", ".log", ".pyc"}:
            raise ValueError(f"Unexpected file in CI starter sources: {relative}")
        if source.with_name(source.name + ".template").is_file():
            continue
        target = str(relative).removesuffix(".template")
        if target in entries:
            raise ValueError(f"Duplicate starter-kit destination: {target}")
        entries[target] = render_source(root, str(relative), values).encode("utf-8")
    if not entries:
        raise ValueError("CI starter-kit sources are missing")
    for name in ("mvnw", ".mvn/wrapper/maven-wrapper.properties"):
        entries[name] = (root / name).read_bytes()
    return entries


def kit_bytes(root):
    record = release_record(root)
    if not record["available"]:
        raise ValueError("CI is not published: refusing to generate a released starter kit")
    entries = kit_entries(root, substitutions(root, record))
    entries["ci-release.json"] = (json.dumps(record, indent=2) + "\n").encode("utf-8")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo("bootui-ci-examples/" + name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = ((0o100755 if name == "mvnw" or name.endswith(".sh") else 0o100644) << 16)
            archive.writestr(info, content)
    return output.getvalue()


def write_asset(root):
    destination = root / ASSET
    if destination.is_symlink() or not destination.resolve().is_relative_to(root.resolve()):
        raise ValueError("The generated CI download must not be a symlink")
    if not release_record(root)["available"]:
        destination.unlink(missing_ok=True)
        return
    data = kit_bytes(root)
    destination.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
            temporary.write(data)
            name = temporary.name
        os.replace(name, destination)
    finally:
        if name is not None:
            Path(name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("render", "check", "assets", "kit"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        if args.mode in ("render", "check"):
            synchronize(root, check=args.mode == "check")
        elif args.mode == "assets":
            write_asset(root)
        else:
            if args.output is None:
                raise ValueError("kit requires --output PATH")
            data = kit_bytes(root)
            with args.output.open("xb") as output:
                output.write(data)
    except (OSError, ValueError, json.JSONDecodeError, ET.ParseError) as error:
        print(f"CI examples: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
