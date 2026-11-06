#!/usr/bin/env python3
"""Synchronize CI documentation metadata before sealing; check it without writes on recovery.

check without a version is the shallow-checkout baseline (schema and project identity).
update VERSION pins the complete Action from the committed release source; check VERSION verifies
that pin against the immutable release contents.
available describes support in the release contents, not a live Central publication probe.
The release workflow deploys those docs only after publication and consumer verification.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


MANIFEST = "docs/ci/release.json"
ACTION = ".github/actions/bootui"
# Published 2.3.0 predates the ci commands. Never advertise it as a CI-capable release.
LAST_WITHOUT_CI = (2, 3, 0)
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def version_tuple(value):
    if not isinstance(value, str) or not re.fullmatch(VERSION, value):
        raise ValueError(f"Invalid CI documentation release version: {value!r}")
    return tuple(map(int, value.split(".")))


def project_version(root):
    pom = ET.parse(root / "pom.xml").getroot()
    return pom.findtext("{http://maven.apache.org/POM/4.0.0}version")


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate manifest field: {key}")
        result[key] = value
    return result


def read_manifest(root):
    data = json.loads((root / MANIFEST).read_text(encoding="utf-8"), object_pairs_hook=unique_keys)
    if not isinstance(data, dict) or set(data) != {"schemaVersion", "available", "version", "actionRef"}:
        raise ValueError("CI manifest must contain exactly schemaVersion, available, version and actionRef")
    if type(data["schemaVersion"]) is not int or data["schemaVersion"] != 1:
        raise ValueError("CI manifest schemaVersion must be 1")
    if type(data["available"]) is not bool:
        raise ValueError("CI manifest available must be a boolean")
    if not isinstance(data["actionRef"], str) or not re.fullmatch(r"[0-9a-f]{40}", data["actionRef"]):
        raise ValueError("CI manifest actionRef must be a full lowercase immutable commit SHA")
    if data["available"]:
        if version_tuple(data["version"]) <= LAST_WITHOUT_CI:
            raise ValueError("Published 2.3.0 and earlier do not support the CI commands")
    elif data["version"] is not None:
        raise ValueError("Unavailable CI manifest must have version null")
    return data


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError(f"Cannot verify Action provenance (git {args[0]}); full source history is required")
    return result.stdout.strip()


def verify_action(root, ref):
    if git(root, "rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Action provenance requires full source history, not a shallow checkout")
    if git(root, "cat-file", "-t", ref) != "commit":
        raise ValueError("actionRef must identify a commit, not a tag or tree")
    git(root, "merge-base", "--is-ancestor", ref, "HEAD")
    expected = {}
    for entry in git(root, "ls-tree", "-r", ref, "--", ACTION).splitlines():
        metadata, path = entry.split("\t", 1)
        mode, kind, oid = metadata.split()
        if kind != "blob" or mode not in ("100644", "100755"):
            raise ValueError("Reviewed Action must contain only regular files")
        expected[path] = (mode, oid)
    if f"{ACTION}/action.yml" not in expected:
        raise ValueError("Reviewed actionRef contains no BootUI Action")
    actual = {}
    for path in (root / ACTION).rglob("*"):
        if path.is_symlink():
            raise ValueError("Action symlinks are not supported")
        if path.is_dir():
            continue
        if not path.is_file():
            raise ValueError("Action must contain only regular files")
        mode = "100755" if path.stat().st_mode & stat.S_IXUSR else "100644"
        relative = path.relative_to(root).as_posix()
        actual[relative] = (mode, git(root, "hash-object", "--no-filters", "--", relative))
    if actual != expected:
        raise ValueError("Action differs from reviewed actionRef; review and pin a new source commit before release")


def synchronize(root, version=None, *, check):
    data = read_manifest(root)
    source = project_version(root)
    source_tuple = version_tuple(source)
    if version is None:
        if not check:
            raise ValueError("update requires a release version")
        if data["available"] and data["version"] != source:
            raise ValueError(f"Stale CI manifest: expected project version {source}")
        if not data["available"] and source_tuple > LAST_WITHOUT_CI:
            raise ValueError("A CI-capable release must not retain the unavailable manifest")
        return data
    target = version_tuple(version)
    if target <= LAST_WITHOUT_CI:
        raise ValueError("Published 2.3.0 and earlier do not support the CI commands")
    if version != source:
        raise ValueError(f"CI release version {version} does not match project version {source}")
    if check:
        verify_action(root, data["actionRef"])
        if not data["available"] or data["version"] != version:
            raise ValueError(f"Signed CI manifest must be available and pin exactly {version}")
    else:
        if data["available"] and target < version_tuple(data["version"]):
            raise ValueError("CI manifest release version must not regress")
        # HEAD is the committed release source before the version bump is sealed. Pinning it
        # avoids a circular reference to the release commit and also works after squash merges.
        source_ref = git(root, "rev-parse", "--verify", "HEAD^{commit}")
        if not re.fullmatch(r"[0-9a-f]{40}", source_ref):
            raise ValueError("The release source must have a full SHA-1 commit ID")
        verify_action(root, source_ref)
        data["available"] = True
        data["version"] = version
        data["actionRef"] = source_ref
        content = json.dumps(data, indent=2) + "\n"
        path = root / MANIFEST
        if path.read_text(encoding="utf-8") != content:
            path.write_text(content, encoding="utf-8")
    return data


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def publication_ready(root, central_url="https://repo.maven.apache.org/maven2"):
    """Pages-only bounded network gate. Never called by offline docs builds or baseline checks."""
    data = synchronize(root, check=True)
    if not data["available"]:
        # Coming-soon docs are honest; the independent release-line gate still applies.
        return True
    spec = importlib.util.spec_from_file_location(
        "ci_docs_published_coordinates", Path(__file__).with_name("check-central-bundle.py")
    )
    bundle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bundle)
    version = data["version"]
    paths = [
        f"{module}/{version}/{module}-{version}.{extension}"
        for module in bundle.PUBLISHED
        for extension in ("pom", "jar")
    ]
    paths.extend(
        f"bootui-cli/{version}/bootui-cli-{version}-all.jar{suffix}"
        for suffix in ("", ".asc")
    )
    opener = urllib.request.build_opener(NoRedirect)
    for path in paths:
        url = f"{central_url}/com/julien-dubois/bootui/{path}"
        for attempt in range(3):
            try:
                with opener.open(urllib.request.Request(url, method="HEAD"), timeout=10) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
            except (OSError, urllib.error.URLError) as error:
                if attempt == 2:
                    raise ValueError(f"Cannot determine exact CI release availability: {url}") from error
                time.sleep(attempt + 1)
                continue
            if status in (429, 500, 502, 503, 504) and attempt < 2:
                time.sleep(attempt + 1)
                continue
            break
        if status == 404:
            return False
        if status != 200:
            raise ValueError(f"Cannot determine exact CI release availability: HTTP {status} for {url}")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("update", "check", "publication-gate"))
    parser.add_argument("version", nargs="?")
    args = parser.parse_args()
    try:
        root = Path(__file__).resolve().parents[2]
        if args.mode == "publication-gate":
            if args.version is not None:
                raise ValueError("publication-gate reads the checked-out manifest; do not pass a version")
            publish = publication_ready(root)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                    output.write(f"publish={str(publish).lower()}\n")
            print(f"CI documentation exact-release gate: publish={str(publish).lower()}")
        else:
            synchronize(root, args.version, check=args.mode == "check")
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"CI documentation version: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
