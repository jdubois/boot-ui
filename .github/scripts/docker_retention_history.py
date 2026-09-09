#!/usr/bin/env python3
"""Restore the retention journal, or bootstrap it from bounded Actions job logs.

Usage: python3 .github/scripts/docker_retention_history.py --output PATH
       --namespace NAME
Requires GH_TOKEN (actions:read), GITHUB_REPOSITORY and GITHUB_RUN_ID. All remote
requests are read-only. Reads docker-retention-inventory, also accepting its
-RUN_ID-ATTEMPT suffix for immutable rerun uploads, including the current run's
earlier journals. Restores inventory.json and ignores an optional plan.json.
Bootstrap excludes the current run and covers at most 90 days; expired/deleted
logs and older untagged manifests cannot be reconstructed and are reported.
"""

import argparse
import io
import json
import os
import re
import sys
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

API = "https://api.github.com"
WORKFLOW = ".github/workflows/docker-publish.yml"
ARTIFACT = "docker-retention-inventory"
REPOSITORIES = tuple(
    "bootui-sample-app" + suffix
    for suffix in ("", "-aot", "-crac", "-native", "-webflux", "-quarkus")
)
DIGEST = r"sha256:[0-9a-f]{64}"
TIMED_LINE = re.compile(r"^(\d{4}-\d\d-\d\dT[\d:.]+Z) (.*)$")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
MAX_BYTES = 32 * 1024 * 1024


class HistoryError(RuntimeError):
    pass


class APIError(HistoryError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"GitHub Actions request failed (HTTP {status})")


def timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError()
        return parsed.astimezone(timezone.utc)
    except (AttributeError, TypeError, ValueError):
        raise HistoryError("Missing or invalid UTC timestamp") from None


def iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def warn(message):
    print(f"::warning::{message}", file=sys.stderr)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def storage_url(url):
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return False
    return (
        parts.scheme == "https"
        and not parts.username
        and not parts.password
        and port in (None, 443)
        and not parts.fragment
        and (
            parts.hostname == "results-receiver.actions.githubusercontent.com"
            or re.fullmatch(
                r"productionresultssa[0-9]+\.blob\.core\.windows\.net",
                parts.hostname or "",
            )
            is not None
        )
    )


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise HistoryError("GITHUB_REPOSITORY must be owner/repository")
        if not token or not token.isascii() or re.search(r"[\x00-\x20\x7f]", token):
            raise HistoryError("GH_TOKEN with actions:read is required")
        self.prefix = f"/repos/{repository}"
        self.token = token
        self.opener = build_opener(NoRedirect)

    def get(self, path, download=False):
        # API paths are constructed locally, never from artifact or Link URLs.
        if not path.startswith(self.prefix + "/"):
            raise HistoryError("Unapproved GitHub API path")
        url = API + path
        headers = {
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "bootui-retention-history",
        }
        for attempt in range(4):
            try:
                with self.opener.open(
                    Request(url, headers=headers, method="GET"), timeout=45
                ) as response:
                    content = response.read(MAX_BYTES + 1)
                    if len(content) > MAX_BYTES:
                        raise HistoryError("GitHub response exceeds the size limit")
                    return content
            except HTTPError as error:
                with error:
                    if download and error.code in (301, 302, 303, 307, 308):
                        target = error.headers.get("Location", "")
                        if not storage_url(target):
                            raise HistoryError("Unapproved Actions download host") from None
                        url = target
                        headers = {"User-Agent": "bootui-retention-history"}
                    else:
                        raise APIError(error.code) from None
            except (URLError, OSError, ValueError):
                # Exception bodies/URLs may contain signed credentials.
                raise HistoryError("GitHub Actions download or connection failed") from None
        raise HistoryError("Too many Actions download redirects")

    def json(self, path):
        try:
            value = json.loads(self.get(self.prefix + path))
        except (ValueError, UnicodeError):
            raise HistoryError("GitHub returned invalid JSON") from None
        if not isinstance(value, dict):
            raise HistoryError("GitHub returned an invalid response object")
        return value

    def pages(self, path, key, params=None, first=None):
        params = dict(params or {})
        page = 1
        seen = set()
        while True:
            data = first if page == 1 and first is not None else self.json(
                path + "?" + urlencode({**params, "per_page": 100, "page": page})
            )
            entries = data.get(key)
            total = data.get("total_count")
            if not isinstance(entries, list) or type(total) is not int or total < 0:
                raise HistoryError("Invalid GitHub pagination response")
            for entry in entries:
                if not isinstance(entry, dict) or type(entry.get("id")) is not int:
                    raise HistoryError("Invalid GitHub list entry")
                if entry["id"] in seen:
                    raise HistoryError("GitHub pagination repeated an entry; retry")
                seen.add(entry["id"])
                yield entry
            if len(seen) >= total:
                return
            if not entries:
                raise HistoryError("GitHub pagination ended before all entries were read")
            page += 1

    def runs(self, since, until):
        params = {"branch": "main", "created": f"{iso(since)}..{iso(until)}"}
        path = "/actions/workflows/docker-publish.yml/runs"
        first = self.json(
            path + "?" + urlencode({**params, "per_page": 100, "page": 1})
        )
        # Actions search exposes only 1,000 results per query. Split the time
        # range instead of silently losing runs on unusually busy repositories.
        if first.get("total_count", 0) > 1000:
            if until - since <= timedelta(seconds=1):
                raise HistoryError("Too many Docker runs in a one-second history window")
            midpoint = since + (until - since) / 2
            seen = set()
            for start, end in ((since, midpoint), (midpoint, until)):
                for run in self.runs(start, end):
                    if run["id"] not in seen:
                        seen.add(run["id"])
                        yield run
        else:
            yield from self.pages(path, "workflow_runs", params, first)

    def download(self, path):
        return self.get(self.prefix + path, download=True)


def validate_inventory(value, namespace):
    if (
        not isinstance(value, dict)
        or type(value.get("version")) is not int
        or value["version"] != 1
        or value.get("namespace") != namespace
    ):
        raise HistoryError("Inventory version or namespace does not match")
    repositories = value.get("repositories")
    if not isinstance(repositories, dict) or set(repositories) - set(REPOSITORIES):
        raise HistoryError("Inventory contains unexpected repositories")
    for records in repositories.values():
        if not isinstance(records, dict):
            raise HistoryError("Inventory manifest records must be objects")
        for digest, pushed in records.items():
            if not re.fullmatch(DIGEST, digest):
                raise HistoryError("Inventory contains an invalid manifest digest")
            timestamp(pushed)
    return {
        "version": 1,
        "namespace": namespace,
        "repositories": {repo: repositories.get(repo, {}) for repo in REPOSITORIES},
    }


def restore_inventory(github, namespace, workflow_id, now):
    artifacts = github.pages("/actions/artifacts", "artifacts")
    candidates = []
    for artifact in artifacts:
        name = artifact.get("name", "")
        suffixed = re.fullmatch(rf"{ARTIFACT}-([0-9]+)-([0-9]+)", name)
        if (name != ARTIFACT and not suffixed) or artifact.get("expired") is True:
            continue
        if artifact.get("expired") is not False:
            raise HistoryError("Artifact expiration metadata is missing")
        if timestamp(artifact.get("expires_at")) <= now:
            continue
        run = artifact.get("workflow_run") or {}
        if run.get("head_branch") != "main":
            continue
        # Run/attempt suffixes let immutable artifact uploads survive reruns.
        if suffixed and (
            int(suffixed[1]) != run.get("id") or int(suffixed[2]) < 1
        ):
            continue
        candidates.append(artifact)
    candidates.sort(
        key=lambda item: (timestamp(item.get("created_at")), item["id"]), reverse=True
    )
    for artifact in candidates:
        run_id = artifact["workflow_run"]["id"]
        if type(run_id) is not int:
            raise HistoryError("Invalid artifact workflow run identity")
        run = github.json(f"/actions/runs/{run_id}")
        if run.get("workflow_id") != workflow_id or run.get("head_branch") != "main":
            continue
        content = github.download(f"/actions/artifacts/{artifact['id']}/zip")
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                entries = archive.infolist()
                names = [entry.filename for entry in entries]
                if (
                    names.count("inventory.json") != 1
                    or names.count("plan.json") > 1
                    or set(names) - {"inventory.json", "plan.json"}
                ):
                    raise HistoryError(
                        "Inventory ZIP must contain inventory.json and optionally plan.json"
                    )
                entry = archive.getinfo("inventory.json")
                if entry.file_size > MAX_BYTES:
                    raise HistoryError("Inventory ZIP exceeds the size limit")
                inventory = validate_inventory(
                    json.loads(archive.read(entry)), namespace
                )
        except (zipfile.BadZipFile, ValueError, UnicodeError, RuntimeError) as error:
            if isinstance(error, HistoryError):
                raise
            raise HistoryError("Cannot read inventory.json from the artifact ZIP") from None
        print(
            f"Restored inventory artifact {artifact['id']} from run {run_id}.",
            file=sys.stderr,
        )
        return inventory
    return None


def log_lines(content):
    for line in content.decode("utf-8", errors="replace").splitlines():
        matched = TIMED_LINE.fullmatch(ANSI.sub("", line))
        if matched:
            yield timestamp(matched[1]), matched[2]


def image_pattern(namespace, repository):
    # DOCKERHUB_USERNAME is a secret in historical workflows, so GitHub masks
    # precisely the namespace as "***"; the workflow/job/repository stay visible.
    return rf"docker\.io/(?:{re.escape(namespace)}|\*\*\*)/{re.escape(repository)}"


def merge_digests(content, namespace, repository):
    image = image_pattern(namespace, repository)
    copy = re.compile(
        rf"#\d+ [\d.]+ copying ({DIGEST}) from {image}@({DIGEST}) to {image}"
    )
    push = re.compile(rf"#\d+ [\d.]+ pushing ({DIGEST}) to {image}:[\w.-]+")
    found = set()
    for _, line in log_lines(content):
        copied = copy.fullmatch(line)
        pushed = push.fullmatch(line)
        if copied and copied[1] == copied[2]:
            found.add(copied[1])
        if pushed:
            found.add(pushed[1])
    return found


def build_digests(content, namespace, repository, steps):
    image = image_pattern(namespace, repository)
    export = re.compile(
        rf"(#\d+) exporting (?:manifest(?: list)?|attestation manifest) "
        rf"({DIGEST})(?: [\d.]+s)? done"
    )
    push = re.compile(
        rf"(#\d+) pushing manifest for {image}(?:@({DIGEST})|:[\w.-]+)?"
        rf"(?: [\d.]+s)? done"
    )
    found = set()
    lines = list(log_lines(content))
    for step in steps:
        if step.get("name") not in (
            "Push the smoke-tested image by digest",
            "Push the smoke-tested image with a retention tag",
        ) or step.get("conclusion") == "skipped":
            continue
        if not step.get("started_at") or not step.get("completed_at"):
            continue
        start = timestamp(step["started_at"])
        # Actions step timestamps have second precision, log records do not.
        end = timestamp(step["completed_at"]) + timedelta(seconds=1)
        exported = {}
        for at, line in lines:
            if not start <= at < end:
                continue
            matched = export.fullmatch(line)
            if matched:
                exported.setdefault(matched[1], set()).add(matched[2])
            pushed = push.fullmatch(line)
            if pushed:
                found.update(exported.get(pushed[1], ()))
                if pushed[2]:
                    found.add(pushed[2])
    return found


def bootstrap(github, namespace, workflow_id, current_run, now):
    since = now - timedelta(days=90)
    warn(
        f"No prior inventory: recovering available Docker publish logs since {iso(since)}. "
        "History is incomplete outside the 90-day Actions retention window; "
        "expired/deleted logs and older untagged manifests cannot be recovered."
    )
    inventory = validate_inventory(
        {"version": 1, "namespace": namespace, "repositories": {}}, namespace
    )
    recovered_runs = 0
    unavailable = 0

    def logs(job):
        nonlocal unavailable
        try:
            return github.download(f"/actions/jobs/{job['id']}/logs")
        except APIError as error:
            if error.status not in (404, 410):
                raise
            unavailable += 1
            warn(
                f"Job {job['id']} logs are unavailable (HTTP {error.status}); "
                "expired/deleted logs leave this bootstrap incomplete."
            )
            return None

    for run in github.runs(since, now):
        if (
            str(run["id"]) == current_run
            or run.get("workflow_id") != workflow_id
            or run.get("head_branch") != "main"
            or run.get("status") != "completed"
            or not since <= timestamp(run["created_at"]) <= now
        ):
            continue
        pushed = iso(timestamp(run["updated_at"]))
        jobs = list(github.pages(
            f"/actions/runs/{run['id']}/jobs", "jobs", {"filter": "all"}
        ))
        recovered_runs += 1
        for repo in REPOSITORIES:
            found = set()
            merged_attempts = set()
            for job in jobs:
                if job.get("name") != f"Merge {repo} manifests":
                    continue
                if job.get("conclusion") in ("skipped", None):
                    continue
                content = logs(job)
                if content is None:
                    continue
                digests = merge_digests(content, namespace, repo)
                found.update(digests)
                if job.get("conclusion") == "success" and digests:
                    merged_attempts.add(job.get("run_attempt", 1))
                elif not digests:
                    warn(f"Merge job {job['id']} yielded no known push records.")
            for job in jobs:
                if job.get("run_attempt", 1) in merged_attempts or job.get("name") not in (
                    f"Build, smoke-test and publish {repo} (linux/amd64)",
                    f"Build, smoke-test and publish {repo} (linux/arm64)",
                ):
                    continue
                steps = [
                    step for step in job.get("steps", [])
                    if step.get("name") in (
                        "Push the smoke-tested image by digest",
                        "Push the smoke-tested image with a retention tag",
                    ) and step.get("conclusion") != "skipped"
                ]
                if not steps:
                    continue
                content = logs(job)
                if content is None:
                    continue
                digests = build_digests(content, namespace, repo, steps)
                if not digests:
                    warn(
                        f"Build job {job['id']} yielded no confirmed manifest pushes; "
                        "partial publication history may be incomplete."
                    )
                found.update(digests)
            records = inventory["repositories"][repo]
            for digest in found:
                if digest not in records or timestamp(records[digest]) < timestamp(pushed):
                    records[digest] = pushed
    count = sum(len(records) for records in inventory["repositories"].values())
    print(
        f"Bootstrap recovered {count} known manifests from {recovered_runs} completed "
        f"runs; {unavailable} job logs unavailable. This is bounded, best-effort history, "
        "not proof that all historical manifests were recovered.",
        file=sys.stderr,
    )
    return inventory


def recover(github, namespace, current_run, now=None):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", namespace):
        raise HistoryError("Invalid Docker Hub namespace")
    if not re.fullmatch(r"[0-9]+", current_run):
        raise HistoryError("GITHUB_RUN_ID is required and must be numeric")
    now = now or datetime.now(timezone.utc)
    workflow = github.json("/actions/workflows/docker-publish.yml")
    if workflow.get("path") != WORKFLOW or type(workflow.get("id")) is not int:
        raise HistoryError("Docker publish workflow identity does not match")
    restored = restore_inventory(github, namespace, workflow["id"], now)
    if restored is not None:
        return restored
    return bootstrap(github, namespace, workflow["id"], current_run, now)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--namespace", required=True)
    args = parser.parse_args(argv)
    try:
        github = GitHub(
            os.environ.get("GITHUB_REPOSITORY", ""),
            os.environ.get("GH_TOKEN", ""),
        )
        inventory = recover(
            github, args.namespace, os.environ.get("GITHUB_RUN_ID", "")
        )
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n")
    except (HistoryError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
