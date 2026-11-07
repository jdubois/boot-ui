#!/usr/bin/env python3
"""Final non-optional workflow gate; an absent result can never be a pass."""

import json
import os
from pathlib import Path


def main():
    root = Path(os.environ["BOOTUI_CI_ROOT"])
    path = root / "result.json"
    if not root.is_absolute() or path.is_symlink() or not path.is_file() or path.stat().st_size > 1024:
        raise ValueError("Missing or invalid recipe result")
    result = json.loads(path.read_text(encoding="utf-8"))
    if type(result.get("schemaVersion")) is not int or result["schemaVersion"] != 1:
        raise ValueError("Invalid recipe result schema")
    if any(type(result.get(key)) is not int for key in ("workloadExit", "assessmentExit")):
        raise ValueError("Invalid recipe exit codes")
    passed = result.get("workloadExit") == 0 and result.get("assessmentExit") == 0
    print("BootUI and ordinary workload: " + ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
