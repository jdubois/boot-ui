#!/usr/bin/env python3
"""Checks the [Unreleased] section of CHANGELOG.md.

Concurrent pull requests merging into the same [Unreleased] section have twice left it with repeated subsections and
repeated entries. This fails the build when a subsection heading or an entry's bold title appears more than once, or
when an entry names a plan identifier (docs/PLAN-v2.md's M4-20, D36, or section numbers), which stay in the plan.

Usage: check-changelog.py [CHANGELOG.md]
"""

import re
import sys
from collections import Counter
from pathlib import Path

PLAN_JARGON = re.compile(r"PLAN(-v2)?\.md|PLAN-v2|\bM[0-9]+-[0-9]+|\bD[0-9]{2}\b|§\s?[0-9]")
# Link targets and RFC sections may carry what reads as plan jargon in text.
NOT_TEXT = re.compile(r"\]\([^)]*\)|RFC [0-9]{1,5} §\s?[0-9][0-9.]*")
ENTRY_TITLE = re.compile(r"^- \*\*(.+?)\*\*")


def unreleased(text):
    """The lines of the [Unreleased] section, with their 1-based line numbers."""
    lines = text.splitlines()
    section = []
    inside = False
    for number, line in enumerate(lines, 1):
        if line.startswith("## "):
            if inside:
                break
            inside = line.startswith("## [Unreleased]")
            continue
        if inside:
            section.append((number, line))
    return section


def check(text):
    """The problems of the [Unreleased] section, one message each; empty when it is clean."""
    section = unreleased(text)
    problems = []
    headings = Counter(line for _, line in section if line.startswith("### "))
    for heading, count in headings.items():
        if count > 1:
            problems.append(f"[Unreleased] repeats the '{heading}' subsection {count} times; merge them")
    titles = {}
    for number, line in section:
        match = ENTRY_TITLE.match(line)
        if match:
            titles.setdefault(match.group(1), []).append(number)
    for title, numbers in titles.items():
        if len(numbers) > 1:
            lines = ", ".join(str(number) for number in numbers)
            problems.append(f"[Unreleased] repeats the entry '{title}' at lines {lines}; keep one")
    for number, line in section:
        match = PLAN_JARGON.search(NOT_TEXT.sub("", line))
        if match:
            problems.append(f"line {number} names the plan identifier '{match.group(0)}'; describe the change instead")
    return problems


def main(arguments):
    path = Path(arguments[0] if arguments else "CHANGELOG.md")
    problems = check(path.read_text(encoding="utf-8"))
    for problem in problems:
        print(f"{path}: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
