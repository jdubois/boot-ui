#!/usr/bin/env python3
"""Require genuine, successful MySQL test execution, not a Docker-unavailable skip."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check(reports: Path, expected: list[str]) -> list[str]:
    failures = []
    suites = {}
    for report in sorted(reports.glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            failures.append(f"Cannot read test report {report.name}: {type(error).__name__}")
            continue
        nodes = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in nodes:
            name = suite.get("name", "").rsplit(".", 1)[-1]
            if name in expected:
                suites.setdefault(name, []).append(suite)
    for name in expected:
        matches = suites.get(name, [])
        if len(matches) != 1:
            failures.append(f"{name}: expected one report, found {len(matches)}")
            continue
        suite = matches[0]
        try:
            counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        except ValueError:
            failures.append(f"{name}: invalid test counters")
            continue
        cases = list(suite.iter("testcase"))
        if counts["tests"] <= 0 or not cases:
            failures.append(f"{name}: no executed test cases")
        elif counts["tests"] != len(cases):
            failures.append(f"{name}: inconsistent test counters")
        elif any(counts[key] != 0 for key in ("failures", "errors", "skipped")) or any(
            case.find(state) is not None for case in cases for state in ("failure", "error", "skipped")
        ):
            failures.append(f"{name}: every required live test must pass without skips")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path)
    parser.add_argument("suites", nargs="+", help="Required test class simple names")
    arguments = parser.parse_args()
    failures = check(arguments.reports, arguments.suites)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"MySQL live evidence: {len(arguments.suites)} required suites passed without skips.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
