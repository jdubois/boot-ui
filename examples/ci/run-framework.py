#!/usr/bin/env python3
"""Prepare fixed windows, run framework-owned test lifecycles, then gate after Maven exits."""

import argparse
import hashlib
import re
import subprocess
from xml.etree.ElementTree import ParseError

from ci_support import Recipe, require, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", choices=("spring", "quarkus"))
    parser.add_argument("--stack", choices=("mvc", "webflux"), default="mvc")
    args = parser.parse_args()
    require(args.project == "spring" or args.stack == "mvc", "--stack applies only to Spring")
    recipe = Recipe(args.project)
    try:
        recipe.bootstrap()
        evidence = recipe.root / "contexts"
        code, output = recipe.cli_run(
            ["prepare-contexts", "--root", str(evidence), "--assessment-id", recipe.assessment_id,
             "--policy", str(recipe.policy), "--contexts", "orders,catalog"], timeout=30
        )
        require(code == 0 and re.fullmatch(r"[0-9a-f]{64}\n?", output),
                "Cannot prepare a fixed context plan; secure filesystem access is required")
        plan_digest = output.strip()
        (recipe.root / "plan.sha256").write_text(plan_digest + "\n", encoding="utf-8")
        recipe.env.update(
            BOOTUI_CI_EVIDENCE_ROOT=str(evidence),
            BOOTUI_CI_ASSESSMENT_ID=recipe.assessment_id,
            BOOTUI_CI_PLAN_SHA256=plan_digest,
        )
        with (recipe.root / "maven.log").open("w", encoding="utf-8") as log:
            code, _ = run(recipe.maven(args.stack, "clean", "test"), cwd=recipe.project,
                          env=recipe.env, timeout=600, log=log)
        recipe.result["workloadExit"] = code
        # No producer JVM is alive now. Missing, failed, filtered or refused windows remain in the plan.
        code, output = recipe.cli_run(
            ["check-contexts", "--root", str(evidence), "--assessment-id", recipe.assessment_id,
             "--plan-sha256", plan_digest], timeout=60
        )
        print(output, end="")
        recipe.result["assessmentExit"] = code
        recipe.persist()
        if code in (0, 3):
            for context in ("orders", "catalog"):
                slot = evidence / "slots" / hashlib.sha256(context.encode()).hexdigest()
                recipe.stage(slot, context)
        else:
            recipe.failure_reports("COLLECTION_INCOMPLETE")
    except (OSError, ValueError, subprocess.SubprocessError, ParseError):
        recipe.result["assessmentExit"] = 1
        if not (recipe.artifacts / "not-assessed").exists():
            recipe.failure_reports("COLLECTION_INCOMPLETE")
        raise
    finally:
        recipe.persist()
        recipe.cleanup()
    return 0 if recipe.result["workloadExit"] == recipe.result["assessmentExit"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
