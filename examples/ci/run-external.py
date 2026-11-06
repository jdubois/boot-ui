#!/usr/bin/env python3
"""Start, exercise, assess and stop one owned Spring MVC or WebFlux application."""

import argparse
from http.client import HTTPException
import subprocess
from xml.etree.ElementTree import ParseError

from ci_support import Recipe, require, run, terminate, wait_ready, workload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stack", choices=("mvc", "webflux"), default="mvc")
    args = parser.parse_args()
    recipe = Recipe("spring")
    process = None
    failure_code = "SETUP_FAILED"
    try:
        recipe.bootstrap()
        with (recipe.root / "maven.log").open("w", encoding="utf-8") as log:
            code, _ = run(recipe.maven(args.stack, "-DskipTests", "clean", "package"),
                          cwd=recipe.project, env=recipe.env, timeout=600, log=log)
        require(code == 0, "Application build failed; inspect the private Maven log")
        jar = recipe.project / "target/orders-spring-1.0.0.jar"
        application_log = recipe.root / "application.log"
        with application_log.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(
                [recipe.cli[0], "-jar", str(jar)], cwd=recipe.project, env=recipe.env,
                stdout=log, stderr=subprocess.STDOUT, text=True, start_new_session=True,
            )
            url = wait_ready(process, application_log)
            failure_code = "ASSESSMENT_INCOMPLETE"
            target = ["--url", url, "--assessment-id", recipe.assessment_id,
                      "--policy", str(recipe.policy), "--timeout", "30"]
            witness = recipe.root / "witness.json"
            attach_exit, output = recipe.cli_run(["attach"] + target + ["--output", str(witness)])
            print(output, end="")
            recipe.result["attachExit"] = attach_exit
            # An attach refusal does not skip, soften, or replace the ordinary workload.
            try:
                workload(url)
                recipe.result["workloadExit"] = 0
            except (ValueError, OSError, HTTPException):
                print("The ordinary integration workload failed.")
                recipe.result["workloadExit"] = 1
            reports = recipe.root / "reports"
            if attach_exit == 0:
                code, output = recipe.cli_run(
                    ["assess"] + target + ["--witness", str(witness), "--output-dir", str(reports)]
                )
                print(output, end="")
                recipe.result["assessmentExit"] = code
                recipe.stage(reports, "assessment")
            else:
                # No assessment call and no scans after any unsuccessful attach.
                recipe.result["assessmentExit"] = 1
                recipe.failure_reports("ATTACH_FAILED")
            recipe.persist()
    except (OSError, ValueError, subprocess.SubprocessError, ParseError):
        recipe.result["assessmentExit"] = 1
        if not (recipe.artifacts / "not-assessed").exists():
            recipe.failure_reports(failure_code)
        raise
    finally:
        if process is not None:
            terminate(process)
        recipe.persist()
        recipe.cleanup()
    return 0 if recipe.result["workloadExit"] == recipe.result["assessmentExit"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
