#!/usr/bin/env python3
"""Rehearses merging `v2` into `main` on a candidate that is never published (PLAN-v2 M4-23).

The candidate merge commit is built with `git merge-tree --write-tree` and `git commit-tree`, so no
branch, working tree, or remote moves. The rehearsal then checks, against the tags on origin and
Maven Central:

* every workflow a push to `main` or a schedule runs, and that can publish, is gated by the
  release line (.github/scripts/release-line-gate.sh), and the Release workflow has no branch trigger;
* on the candidate, the gate decides not to publish the documentation site or the Docker images, the
  integrity guard passes, and the Release workflow's policy rejects a 1.x version and accepts only
  2.0.0, which only a deliberate, signed release can publish;
* simulated futures, with synthetic tags and a local stand-in for Maven Central: a v2.0.0 tag
  without its artifacts publishes nothing, its artifacts publish the 2.x site, and a 1.x maintenance
  branch then publishes no site, releases 1.x patches, and never opens 2.0.0;
* whether `main`, the future `1.x` branch, is ready to release 1.x patches after 2.0.0, and whether
  the GitHub environments allow it (read only, with `gh`).

With --live, the candidate is pushed to a temporary `rehearsal/v2-merge-*` branch, which no push
trigger and no deployment environment accepts, `pages.yml` and `docker-publish.yml` are dispatched
from it, the rehearsal asserts that their gates skipped every publishing job, cancelling a run as soon
as one is queued, and the branch is deleted.

Release-day prerequisites that are not done yet are reported as PENDING; --release-day turns them
into failures. Exit status: 0 when nothing failed, 1 otherwise.
"""

import argparse
import functools
import http.server
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

GROUP = "com/julien-dubois/bootui"
PUBLISHING_MARKERS = ("actions/deploy-pages", "docker/login-action", "clean deploy", "gh release create")
GATE = ".github/scripts/release-line-gate.sh"
POLICY = ".github/scripts/release-version-policy.sh"
SHARED_FILES = (
    POLICY,
    GATE,
    ".github/workflows/pages.yml",
    ".github/workflows/docker-publish.yml",
)

results = []


def record(status, check, detail=""):
    results.append((status, check, detail))
    print(f"[{status}] {check}" + (f"\n         {detail}" if detail else ""), flush=True)


def run(arguments, cwd=None, env=None, check=True, input_text=None):
    result = subprocess.run(
        arguments, cwd=cwd, env=env, input=input_text, capture_output=True, text=True, check=False
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(arguments)} failed ({result.returncode}): {result.stderr.strip()}")
    return result


def git(*arguments, cwd=None, check=True):
    return run(["git", *arguments], cwd=cwd, check=check)


def show(ref, path, repository):
    result = git("show", f"{ref}:{path}", cwd=repository, check=False)
    return result.stdout if result.returncode == 0 else None


def on_block(workflow):
    lines = workflow.splitlines()
    block, inside = [], False
    for line in lines:
        if re.match(r"^on:\s*$", line):
            inside = True
            continue
        if inside and re.match(r"^\S", line):
            break
        if inside:
            block.append(line)
    return "\n".join(block)


def runs_on_main(workflow):
    trigger = on_block(workflow)
    push = re.search(r"^  push:\s*\n((?:    .*\n?)*)", trigger + "\n", re.MULTILINE)
    pushes_main = bool(push and re.search(r"branches:\s*\[[^\]]*\bmain\b", push.group(1)))
    return pushes_main or bool(re.search(r"^  schedule:", trigger, re.MULTILINE))


class FakeCentral:
    def __init__(self):
        self.directory = tempfile.TemporaryDirectory()
        handler = functools.partial(_Quiet, directory=self.directory.name)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def publish(self, version):
        root = Path(self.directory.name) / GROUP
        for artifact, name in (
            ("bootui-parent", f"bootui-parent-{version}.pom"),
            ("bootui-spring-boot-starter", f"bootui-spring-boot-starter-{version}.jar"),
        ):
            path = root / artifact / version / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"published")

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.directory.cleanup()


class _Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *arguments):
        pass


def gate(checkout, gate_script, tags=None, central=None):
    env = {key: value for key, value in os.environ.items() if not key.startswith("GITHUB_")}
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / "output"
        output.touch()
        env["GITHUB_OUTPUT"] = str(output)
        if tags is not None:
            tags_file = Path(directory) / "tags"
            tags_file.write_text("".join(f"{tag}\n" for tag in tags), encoding="utf-8")
            env["BOOTUI_RELEASE_TAGS_FILE"] = str(tags_file)
        if central is not None:
            env["BOOTUI_CENTRAL_URL"] = central.url
        result = run(["bash", str(gate_script)], cwd=checkout, env=env, check=False)
        values = dict(line.split("=", 1) for line in output.read_text().splitlines() if "=" in line)
    return result, values


def policy(policy_script, *arguments, tags=()):
    return run(
        ["bash", str(policy_script), *arguments],
        check=False,
        input_text="".join(f"{tag}\n" for tag in tags),
    )


def project_version(checkout):
    pom = (Path(checkout) / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<artifactId>bootui-parent</artifactId>\s*<version>([^<]+)</version>", pom)
    return match.group(1)


def next_patch(tags, major):
    versions = sorted(
        tuple(int(part) for part in tag[1:].split("."))
        for tag in tags
        if re.fullmatch(rf"v{major}\.\d+\.\d+", tag)
    )
    newest = versions[-1]
    return f"{newest[0]}.{newest[1]}.{newest[2] + 1}"


def expect_gate(check, checkout, gate_script, expected, tags=None, central=None):
    result, values = gate(checkout, gate_script, tags, central)
    reason = values.get("reason", (result.stdout + result.stderr).strip())
    if result.returncode == 0 and values.get("publish") == expected:
        record("PASS", check, f"publish={expected}: {reason}")
    else:
        record("FAIL", check, f"expected publish={expected}, got {values.get('publish')!r}: {reason}")


def check_triggers(candidate, repository):
    workflows = git("ls-tree", "--name-only", f"{candidate}:.github/workflows", cwd=repository).stdout.split()
    for name in sorted(workflows):
        if not name.endswith(".yml"):
            continue
        content = show(candidate, f".github/workflows/{name}", repository)
        if name == "release.yml":
            trigger = on_block(content)
            branch_push = re.search(r"^  push:\s*\n(?:    .*\n)*?    branches:", trigger + "\n", re.MULTILINE)
            if branch_push:
                record("FAIL", "release.yml has no branch push trigger", "a push would start a release")
            else:
                record("PASS", "release.yml runs only on a tag push or a manual dispatch", "the merge pushes no tag")
            continue
        publishes = [marker for marker in PUBLISHING_MARKERS if marker in content]
        if not runs_on_main(content):
            continue
        if not publishes:
            record("PASS", f"{name} runs on a push to main or a schedule and publishes nothing")
        elif "run: bash .github/scripts/release-line-gate.sh" in content:
            record("PASS", f"{name} publishes ({', '.join(publishes)}) only through the release-line gate")
        else:
            record("FAIL", f"{name} publishes ({', '.join(publishes)}) on a push to main without the gate")


def environment_policies():
    found = {}
    for environment in ("maven-central", "github-pages", "docker-hub"):
        result = run(
            [
                "gh",
                "api",
                f"repos/jdubois/boot-ui/environments/{environment}/deployment-branch-policies",
                "--jq",
                "[.branch_policies[] | \"\\(.type):\\(.name)\"]",
            ],
            check=False,
        )
        found[environment] = json.loads(result.stdout) if result.returncode == 0 else None
    return found


def check_sign_off(candidate_tree, release_day):
    pending = "FAIL" if release_day else "PENDING"
    report = (candidate_tree / "docs/V2-VALIDATION-REPORT.md").read_text(encoding="utf-8")
    section = re.search(r"^## Release sign-off\n(.*?)(?=^## |\Z)", report, re.MULTILINE | re.DOTALL)
    if not section:
        record("FAIL", "the validation report has a release sign-off section")
        return
    todos = section.group(1).count("TODO")
    if todos:
        record(pending, "the release sign-off is complete", f"{todos} TODO left in V2-VALIDATION-REPORT.md")
    else:
        record("PASS", "the release sign-off is complete")


def check_environments(release_day):
    pending = "FAIL" if release_day else "PENDING"
    try:
        policies = environment_policies()
    except (FileNotFoundError, json.JSONDecodeError) as error:
        record(pending, "GitHub environment policies could be read", str(error))
        return
    central = policies["maven-central"] or []
    if "branch:1.x" in central:
        record("PASS", "maven-central accepts Release runs from 1.x")
    else:
        record(pending, "maven-central accepts Release runs from 1.x", f"allowed today: {central}; add branch 1.x")
    if "branch:v2" in central or "branch:v*" in central:
        record("FAIL", "maven-central refuses Release runs from v2", f"allowed today: {central}")
    else:
        record("PASS", "maven-central refuses Release runs from v2 and its feature branches", f"allowed: {central}")
    docker = policies["docker-hub"]
    if docker == ["branch:main"]:
        record("PASS", "docker-hub accepts only main")
    else:
        record(
            pending,
            "docker-hub accepts only main",
            "no deployment policy today, so a docker-publish.yml dispatch from a ref without the gate "
            "(an older v2 feature branch, or a 1.x tag after 2.0.0) could push `latest`",
        )
    pages = policies["github-pages"] or []
    record(
        "INFO",
        "github-pages deployment policy",
        f"allowed today: {pages}; right after the 2.0 site deploys, narrow the tag rule from v* to v2.* so "
        "an old tag's ungated pages.yml cannot replace it",
    )


def check_maintenance_branch(main_ref, main_tree, candidate_ref, repository, release_day):
    pending = "FAIL" if release_day else "PENDING"
    policy_script = show(main_ref, POLICY, repository)
    release = show(main_ref, ".github/workflows/release.yml", repository) or ""
    if policy_script and "release-version-policy.sh next-version" in release and "LATEST_TAG=" not in release:
        record("PASS", f"{main_ref} computes release versions per major")
    else:
        record(
            pending,
            f"{main_ref} computes release versions per major",
            "main's release.yml still takes the newest tag overall, so a 1.x branch cut from it would reject "
            "every 1.x patch after v2.0.0 and redeploy 1.x documentation over 2.0: backport M4-16's policy first",
        )
    if show(main_ref, GATE, repository) is not None and show(main_ref, ".github/release-line", repository):
        for name, command in (
            ("integrity guard", ["bash", ".github/scripts/check-release-integrity.sh"]),
            (
                "release tests",
                ["python3", "-B", "-m", "unittest", "discover", "-s", ".github/scripts", "-p", "test_release_*.py"],
            ),
        ):
            result = run(command, cwd=main_tree, check=False)
            output = (result.stdout + result.stderr).strip().splitlines()
            record("PASS" if result.returncode == 0 else "FAIL", f"{main_ref}'s own {name} pass",
                   output[-1] if output else "")
        ported = all(
            literal in release
            for literal in ('"$CURRENT_VERSION" "$RELEASE_LINE"', "TAGGED_RELEASE_LINE=", "newest-major")
        )
        record("PASS" if ported else pending, f"{main_ref}'s release.yml carries the release-line checks")
    else:
        record(pending, f"{main_ref}'s own integrity guard and release tests cover the release line",
               "main has no release-line gate yet")
    line = show(main_ref, ".github/release-line", repository)
    if line and re.search(r"^\s*1\s*(#.*)?$", line, re.MULTILINE):
        record("PASS", f"{main_ref} declares release line 1")
    else:
        record(pending, f"{main_ref} declares release line 1", ".github/release-line is missing on main")
    for path in SHARED_FILES:
        if show(main_ref, path, repository) == show(candidate_ref, path, repository):
            record("PASS", f"{path} is identical on {main_ref} and the candidate")
        else:
            record(pending, f"{path} is identical on {main_ref} and the candidate", "backport it byte for byte")
    build = show(main_ref, ".github/workflows/build.yml", repository) or ""
    if re.search(r"branches:\s*\[[^\]]*\b1\.x\b", build):
        record("PASS", f"{main_ref}'s build.yml runs on 1.x")
    else:
        record(
            pending,
            f"{main_ref}'s build.yml runs on 1.x",
            "add 1.x to build.yml's push and pull_request branches so 1.x patches get a green build",
        )


# Jobs that publish, or lead to publishing, per dispatched workflow. A live run is cancelled as soon as
# one of them is queued or running, long before docker-publish.yml's first push, which follows a full
# Maven build and a smoke test.
PUBLISHING_JOBS = {
    "pages.yml": lambda name: name.startswith("Deploy"),
    "docker-publish.yml": lambda name: not name.startswith("Decide"),
}


def live(candidate, repository):
    branch = f"rehearsal/v2-merge-{candidate[:12]}"
    git("push", "origin", f"{candidate}:refs/heads/{branch}", cwd=repository)
    record("INFO", f"pushed the candidate to {branch}", "no push trigger and no deployment environment accepts it")
    try:
        runs = {}
        for workflow in ("pages.yml", "docker-publish.yml"):
            before = set(list_runs(workflow, branch))
            run(["gh", "workflow", "run", workflow, "--ref", branch])
            runs[workflow] = wait_for_new_run(workflow, branch, before)
        for workflow, run_id in runs.items():
            jobs = wait_for_completion(run_id, PUBLISHING_JOBS[workflow])
            check_live_jobs(workflow, run_id, jobs)
    finally:
        git("push", "origin", "--delete", branch, cwd=repository, check=False)
        record("INFO", f"deleted {branch}")


def list_runs(workflow, branch):
    result = run(
        ["gh", "run", "list", "--workflow", workflow, "--branch", branch, "--limit", "20", "--json", "databaseId"]
    )
    return [entry["databaseId"] for entry in json.loads(result.stdout)]


def wait_for_new_run(workflow, branch, before):
    for _ in range(60):
        time.sleep(5)
        new = [run_id for run_id in list_runs(workflow, branch) if run_id not in before]
        if new:
            return new[0]
    raise RuntimeError(f"the dispatched {workflow} run never appeared")


def wait_for_completion(run_id, publishing):
    for _ in range(720):
        view = json.loads(run(["gh", "run", "view", str(run_id), "--json", "status,jobs"]).stdout)
        started = [
            job["name"]
            for job in view["jobs"]
            if publishing(job["name"]) and job["status"] in ("queued", "in_progress", "waiting", "pending")
        ]
        if started:
            run(["gh", "run", "cancel", str(run_id)], check=False)
            record("FAIL", f"run {run_id} started a publishing job; cancelled it", ", ".join(started))
            return {job["name"]: job["status"] for job in view["jobs"]}
        if view["status"] == "completed":
            return {job["name"]: job["conclusion"] for job in view["jobs"]}
        time.sleep(5)
    raise RuntimeError(f"run {run_id} did not complete")


def check_live_jobs(workflow, run_id, jobs):
    url = f"https://github.com/jdubois/boot-ui/actions/runs/{run_id}"
    gate_job = "Build documentation site" if workflow == "pages.yml" else "Decide whether this branch may publish images"
    gate_ok = jobs.get(gate_job) == "success"
    publishing = {name: conclusion for name, conclusion in jobs.items() if PUBLISHING_JOBS[workflow](name)}
    skipped = all(conclusion == "skipped" for conclusion in publishing.values())
    status = "PASS" if gate_ok and skipped else "FAIL"
    record(status, f"live {workflow} dispatch skipped every publishing job", f"{url} jobs: {jobs}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--v2", default="HEAD", help="the v2 side of the merge (default: HEAD)")
    parser.add_argument("--main", default="origin/main", help="the main side, the future 1.x (default: origin/main)")
    parser.add_argument("--release-day", action="store_true", help="treat pending prerequisites as failures")
    parser.add_argument("--live", action="store_true", help="also dispatch pages.yml and docker-publish.yml")
    arguments = parser.parse_args()

    repository = Path(git("rev-parse", "--show-toplevel").stdout.strip())
    if tuple(int(part) for part in re.findall(r"\d+", git("--version").stdout)[:2]) < (2, 38):
        sys.exit("git 2.38 or newer is required for `git merge-tree --write-tree`")
    git("fetch", "--quiet", "origin", "main", "v2", cwd=repository)
    main_sha = git("rev-parse", arguments.main, cwd=repository).stdout.strip()
    v2_sha = git("rev-parse", arguments.v2, cwd=repository).stdout.strip()
    print(f"main side: {arguments.main} = {main_sha}\nv2 side:   {arguments.v2} = {v2_sha}\n")

    merge = git("merge-tree", "--write-tree", "--name-only", main_sha, v2_sha, cwd=repository, check=False)
    if merge.returncode != 0:
        record("FAIL", "v2 merges into main without conflicts", "merge main into v2 first:\n" + merge.stdout)
        return summarize()
    tree = merge.stdout.splitlines()[0]
    candidate = git(
        "commit-tree", tree, "-p", main_sha, "-p", v2_sha, "-m", "Rehearsal: merge v2 into main (never published)",
        cwd=repository,
    ).stdout.strip()
    record("PASS", "v2 merges into main without conflicts", f"candidate merge commit {candidate} (local, unreferenced)")

    tags = [
        line.split("refs/tags/", 1)[1]
        for line in git("ls-remote", "--tags", "--refs", "origin", "refs/tags/v*", cwd=repository).stdout.splitlines()
    ]
    with tempfile.TemporaryDirectory() as directory:
        candidate_tree = Path(directory) / "candidate"
        main_tree = Path(directory) / "main"
        git("worktree", "add", "--detach", "--quiet", str(candidate_tree), candidate, cwd=repository)
        git("worktree", "add", "--detach", "--quiet", str(main_tree), main_sha, cwd=repository)
        central = FakeCentral()
        try:
            rehearse(repository, candidate, candidate_tree, main_tree, tags, central, arguments)
        finally:
            central.close()
            git("worktree", "remove", "--force", str(candidate_tree), cwd=repository, check=False)
            git("worktree", "remove", "--force", str(main_tree), cwd=repository, check=False)
    if arguments.live:
        live(candidate, repository)
    return summarize()


def rehearse(repository, candidate, candidate_tree, main_tree, tags, central, arguments):
    gate_script = candidate_tree / GATE
    policy_script = candidate_tree / POLICY

    print("\n== The merge candidate, against origin's tags and Maven Central ==")
    check_triggers(candidate, repository)
    guard = run(["bash", ".github/scripts/check-release-integrity.sh"], cwd=candidate_tree, check=False)
    record("PASS" if guard.returncode == 0 else "FAIL", "the release integrity guard passes on the candidate",
           (guard.stdout + guard.stderr).strip().splitlines()[-1])
    expect_gate("merging v2 deploys no documentation site and publishes no Docker image", candidate_tree,
                gate_script, "false")
    version = project_version(candidate_tree)
    line = policy(policy_script, "line-major", version, str(candidate_tree / ".github/release-line")).stdout.strip()
    patch = next_patch(tags, 1)
    rejected = policy(policy_script, "next-version", patch, version, line, tags=tags)
    record("PASS" if rejected.returncode == 2 else "FAIL",
           f"the Release workflow on the merged main refuses {patch}", rejected.stderr.strip())
    accepted = policy(policy_script, "next-version", "2.0.0", version, line, tags=tags)
    record("PASS" if accepted.returncode == 0 else "FAIL",
           "the Release workflow on the merged main accepts only 2.0.0, a deliberate signed release",
           (accepted.stdout + accepted.stderr).strip())

    print("\n== The 1.x line before the merge, then simulated futures with a local stand-in for Maven Central ==")
    for tag in tags:
        if re.fullmatch(r"v1\.\d+\.\d+", tag):
            central.publish(tag[1:])
    maintenance = simulated_maintenance_branch(main_tree)
    expect_gate("before the merge, main (release line 1) keeps publishing, against origin and Maven Central",
                maintenance, gate_script, "true")
    expect_gate("v2.0.0 tagged, artifacts not on Maven Central yet: nothing publishes", candidate_tree,
                gate_script, "false", tags=tags + ["v2.0.0"], central=central)
    central.publish("2.0.0")
    expect_gate("v2.0.0 on Maven Central: the 2.x site and images publish", candidate_tree, gate_script, "true",
                tags=tags + ["v2.0.0"], central=central)
    expect_gate("after 2.0.0, a 1.x maintenance branch publishes no site or image", maintenance, gate_script,
                "false", tags=tags + ["v2.0.0"], central=central)
    after = tags + ["v2.0.0"]
    main_version = project_version(main_tree)
    patch_ok = policy(policy_script, "next-version", patch, main_version, "1", tags=after)
    record("PASS" if patch_ok.returncode == 0 else "FAIL",
           f"after 2.0.0, the Release workflow on 1.x accepts {patch}", (patch_ok.stdout + patch_ok.stderr).strip())
    newest = policy(policy_script, "newest-major", patch, tags=after + [f"v{patch}"]).stdout.strip()
    record("PASS" if newest == "false" else "FAIL",
           f"after 2.0.0, releasing {patch} does not redeploy the documentation site", f"newest-major={newest}")
    opens = policy(policy_script, "next-version", "2.0.0", main_version, "1", tags=tags)
    record("PASS" if opens.returncode == 2 else "FAIL", "a 1.x branch can never release 2.0.0", opens.stderr.strip())

    simulated = maintenance / ".github/release-line"
    if git("ls-files", "--error-unmatch", ".github/release-line", cwd=maintenance, check=False).returncode != 0:
        simulated.unlink()

    print("\n== Readiness of main as the future 1.x branch, the sign-off, and the GitHub environments ==")
    check_maintenance_branch(arguments.main, main_tree, candidate, repository, arguments.release_day)
    check_sign_off(candidate_tree, arguments.release_day)
    check_environments(arguments.release_day)


def simulated_maintenance_branch(main_tree):
    # Until the backport lands, main does not declare its release line: rehearse with the 1 it adds.
    line_file = main_tree / ".github/release-line"
    if not line_file.exists():
        line_file.write_text("# simulated by the rehearsal\n1\n", encoding="utf-8")
        record("INFO", "main does not declare its release line yet; simulating release line 1 in its checkout")
    return main_tree


def summarize():
    counts = {}
    for status, _, _ in results:
        counts[status] = counts.get(status, 0) + 1
    print("\nSummary: " + ", ".join(f"{count} {status}" for status, count in sorted(counts.items())))
    return 1 if counts.get("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
