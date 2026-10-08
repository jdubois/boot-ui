#!/usr/bin/env python3
"""Checks the [Unreleased] section of CHANGELOG.md.

Concurrent pull requests merging into the same [Unreleased] section have twice left it with repeated subsections and
repeated entries. This fails the build when a subsection heading or an entry's bold title appears more than once,
when an entry's title repeats one a released version already lists, or when an entry names a plan identifier
(docs/PLAN-v2.md's M4-20, D36, or a plan section such as "(§5.14"), which stay in the plan.

Usage: check-changelog.py [CHANGELOG.md]
"""

import re
import sys
from collections import Counter
from pathlib import Path

# A plan section is cited as "PLAN-v2 §5.14" or "(§5.14"; a specification's section, such as JLS §17.4, is not.
PLAN_JARGON = re.compile(r"PLAN(-v2)?\.md|PLAN-v2|\bPLAN\s+§|\bM[0-9]+-[0-9]+|\bD[0-9]{2}\b|\(\s*§\s?[0-9]")
# Link targets may carry what reads as plan jargon in text.
NOT_TEXT = re.compile(r"\]\([^)]*\)")
ENTRY_TITLE = re.compile(r"^- \*\*(.+?)\*\*")


def sections(text):
    """The lines of the [Unreleased] section and of the released ones, with their 1-based line numbers."""
    unreleased = []
    released = []
    current = None
    for number, line in enumerate(text.splitlines(), 1):
        if line.startswith("## "):
            current = unreleased if line.startswith("## [Unreleased]") else released
            continue
        if current is not None:
            current.append((number, line))
    return unreleased, released


def check(text):
    """The problems of the [Unreleased] section, one message each; empty when it is clean."""
    section, released = sections(text)
    released_titles = {match.group(1) for _, line in released if (match := ENTRY_TITLE.match(line))}
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
        if title in released_titles:
            problems.append(
                f"[Unreleased] lists '{title}' at line {numbers[0]}, which a released version already lists; drop it"
            )
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
