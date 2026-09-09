#!/usr/bin/env python3
"""Plan and apply Docker Hub retention without deleting shared image layers.

The inventory is a write-ahead journal: upload it before applying the plan so a
failed deletion can be retried even after its last discoverable tag is gone.
Only the Python standard library is required.
"""

import argparse
import base64
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

REPOSITORIES = tuple(
    "bootui-sample-app" + suffix
    for suffix in ("", "-aot", "-crac", "-native", "-webflux", "-quarkus")
)
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
TAG = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
INDEX_TYPES = {
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
}
IMAGE_TYPES = {
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
}
ACCEPT = ", ".join(sorted(INDEX_TYPES | IMAGE_TYPES))


class RetentionError(RuntimeError):
    pass


class ManifestDeleteBlocked(RetentionError):
    pass


@dataclass(frozen=True)
class Response:
    status: int
    content: bytes = b""
    media_type: str = ""


def timestamp(value):
    if not isinstance(value, str):
        raise RetentionError("Missing or invalid push timestamp")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise RetentionError("Push timestamp must include a timezone")
    return result.astimezone(timezone.utc)


def iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def check_digest(value):
    if not isinstance(value, str) or not DIGEST.fullmatch(value):
        raise RetentionError("Invalid manifest digest")
    return value


def check_namespace(value):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", value):
        raise RetentionError("Invalid Docker Hub namespace")
    return value


def read_inventory(path, namespace):
    data = json.loads(Path(path).read_text())
    if data.get("version") != 1 or data.get("namespace") != namespace:
        raise RetentionError("Inventory version or namespace does not match")
    history_version = data.get("history_version", 0)
    if type(history_version) is not int or history_version not in (0, 1):
        raise RetentionError("Unsupported inventory history version")
    repositories = data["repositories"]
    if not isinstance(repositories, dict) or set(repositories) - set(REPOSITORIES):
        raise RetentionError("Inventory contains unexpected repositories")
    for records in repositories.values():
        if not isinstance(records, dict):
            raise RetentionError("Inventory manifest records must be an object")
        for digest, pushed in records.items():
            check_digest(digest)
            timestamp(pushed)
    return data


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # None of these endpoints needs a redirect. Never forward credentials
        # to an origin supplied by a Location or pagination header.
        return None


def request(method, url, headers=None, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(url, data=data, headers=headers or {}, method=method)
    try:
        with build_opener(NoRedirect).open(req, timeout=45) as response:
            return Response(
                response.status,
                response.read(),
                response.headers.get("Content-Type", "").split(";")[0],
            )
    except HTTPError as error:
        with error:
            return Response(error.code, error.read())


class DockerHub:
    def __init__(self, namespace, token="", transport=request, sleep=time.sleep):
        self.namespace = check_namespace(namespace)
        self.token = token
        self.transport = transport
        self.sleep = sleep
        self.hub_token = None
        self.registry_tokens = {}

    def json_request(self, method, url, headers=None, body=None):
        response = self.transport(method, url, headers, body)
        if response.status != 200:
            raise RetentionError(
                f"{method} {urlsplit(url).path}: HTTP {response.status}"
            )
        return json.loads(response.content)

    def hub_headers(self):
        if not self.token:
            return {}
        if self.hub_token is None:
            response = self.json_request(
                "POST",
                "https://hub.docker.com/v2/users/login",
                {"Content-Type": "application/json"},
                {"username": self.namespace, "password": self.token},
            )
            self.hub_token = response.get("token")
            if not self.hub_token:
                raise RetentionError("Docker Hub authentication returned no token")
        return {"Authorization": "JWT " + self.hub_token}

    def registry_headers(self, repo, write=False):
        key = (repo, write)
        cached = self.registry_tokens.get(key)
        if cached is None or cached[1] <= time.monotonic():
            if write and not self.token:
                raise RetentionError("DOCKERHUB_TOKEN is required for deletion")
            scope = "pull,push,delete" if write else "pull"
            query = urlencode(
                {
                    "service": "registry.docker.io",
                    "scope": f"repository:{self.namespace}/{repo}:{scope}",
                }
            )
            headers = {}
            if self.token:
                credentials = base64.b64encode(
                    f"{self.namespace}:{self.token}".encode()
                ).decode()
                headers["Authorization"] = "Basic " + credentials
            result = self.json_request(
                "GET", "https://auth.docker.io/token?" + query, headers
            )
            token = result.get("token")
            if not token:
                raise RetentionError("Registry authentication returned no token")
            self.registry_tokens[key] = (
                token,
                time.monotonic() + min(int(result.get("expires_in", 60)), 240),
            )
        return {
            "Authorization": "Bearer " + self.registry_tokens[key][0],
            "Accept": ACCEPT,
        }

    def tags(self, repo):
        path = f"/v2/repositories/{self.namespace}/{repo}/tags/"
        url = "https://hub.docker.com" + path + "?page_size=100"
        result = {}
        pages = set()
        while url:
            parsed = urlsplit(url)
            if (
                parsed.scheme != "https"
                or parsed.netloc != "hub.docker.com"
                or parsed.path != path
                or url in pages
            ):
                raise RetentionError("Unsafe or repeated Docker Hub pagination URL")
            pages.add(url)
            page = self.json_request("GET", url, self.hub_headers())
            for tag in page["results"]:
                name = tag["name"]
                if not TAG.fullmatch(name) or name in result:
                    raise RetentionError("Invalid or duplicate tag in inventory")
                pushed = timestamp(tag["tag_last_pushed"])
                result[name] = {
                    "name": name,
                    "digest": check_digest(tag["digest"]),
                    "pushed_at": iso(pushed),
                }
            url = page["next"]
        return result

    def manifest_url(self, repo, digest):
        check_digest(digest)
        return (
            f"https://registry-1.docker.io/v2/{self.namespace}/{repo}"
            f"/manifests/{digest}"
        )

    def manifest(self, repo, digest):
        url = self.manifest_url(repo, digest)
        # HEAD does not consume Docker Hub's image-pull quota. Only indexes
        # need a GET to discover children; leaf configurations/layers are unused.
        response = self.transport("HEAD", url, self.registry_headers(repo), None)
        if response.status == 404:
            return None
        if response.status != 200:
            raise RetentionError(f"Reading {repo}@{digest}: HTTP {response.status}")
        if response.media_type in IMAGE_TYPES:
            return []
        if response.media_type not in INDEX_TYPES:
            raise RetentionError(f"Unsupported manifest format: {repo}@{digest}")
        data = self.json_request("GET", url, self.registry_headers(repo))
        if data.get("schemaVersion") != 2 or data.get("mediaType") not in INDEX_TYPES:
            raise RetentionError(f"Unsupported image index: {repo}@{digest}")
        return [check_digest(child["digest"]) for child in data["manifests"]]

    def delete_tag(self, repo, name):
        url = (
            f"https://hub.docker.com/v2/repositories/{self.namespace}/{repo}"
            f"/tags/{name}/"
        )
        response = self.transport("DELETE", url, self.hub_headers(), None)
        if response.status not in (202, 204, 404):
            raise RetentionError(f"Deleting {repo}:{name}: HTTP {response.status}")

    def delete_manifest(self, repo, digest):
        url = self.manifest_url(repo, digest)
        headers = self.registry_headers(repo, write=True)
        response = self.transport("DELETE", url, headers, None)
        if response.status == 404:
            return
        if response.status == 403:
            raise ManifestDeleteBlocked(
                f"Deleting {repo}@{digest}: HTTP 403; "
                "check delete permission and remaining references"
            )
        # Docker documents that 500 can mean deletion was queued. Do not submit
        # it again; confirm absence before deleting any dependent manifests.
        if response.status not in (202, 500):
            raise RetentionError(
                f"Deleting {repo}@{digest}: HTTP {response.status}; "
                "check delete permission and remaining references"
            )
        for delay in (0, 1, 2, 4, 8, 15):
            self.sleep(delay)
            response = self.transport("HEAD", url, self.registry_headers(repo), None)
            if response.status == 404:
                return
            if response.status != 200:
                raise RetentionError(
                    f"Confirming deletion of {repo}@{digest}: HTTP {response.status}"
                )
        raise RetentionError(
            f"Deletion still pending for {repo}@{digest}; inventory preserved for retry"
        )


class Graph:
    def __init__(self, client, repo):
        self.client = client
        self.repo = repo
        self.children = {}
        self.pushed = {}

    def visit(self, digest, pushed, required=False, ancestors=()):
        if digest in ancestors:
            raise RetentionError(f"Cyclic image index in {self.repo}")
        if digest not in self.children:
            self.children[digest] = self.client.manifest(self.repo, digest)
        children = self.children[digest]
        if children is None:
            if required:
                raise RetentionError(f"Referenced manifest is missing: {self.repo}@{digest}")
            return
        self.pushed[digest] = max(pushed, self.pushed.get(digest, pushed))
        for child in children:
            self.visit(child, pushed, required=required, ancestors=ancestors + (digest,))

    def reachable(self, roots):
        result = set()

        def walk(digest):
            if digest not in result:
                result.add(digest)
                for child in self.children[digest]:
                    walk(child)

        for root in roots:
            walk(root)
        return result

    def deletion_order(self, candidates):
        visited = set()
        ordered = []

        def walk(digest):
            if digest in visited or self.children[digest] is None:
                return
            visited.add(digest)
            for child in self.children[digest]:
                walk(child)
            if digest in candidates:
                ordered.append(digest)

        for digest in sorted(candidates):
            walk(digest)
        return list(reversed(ordered))


def plan_retention(
    client, inventory, now, days=7, repositories=REPOSITORIES, history_version=0
):
    if days < 1:
        raise RetentionError("Retention must be at least one day")
    cutoff = now - timedelta(days=days)
    plan = {
        "version": 1,
        "namespace": client.namespace,
        "generated_at": iso(now),
        "cutoff": iso(cutoff),
        "repositories": {},
    }
    journal = {
        "version": 1,
        "history_version": history_version,
        "namespace": client.namespace,
        "repositories": {},
    }
    for repo in repositories:
        tags = client.tags(repo)
        if "latest" not in tags:
            raise RetentionError(f"{repo}: latest is missing; refusing to prune")
        graph = Graph(client, repo)
        for tag in tags.values():
            graph.visit(tag["digest"], timestamp(tag["pushed_at"]), required=True)
        for digest, pushed in inventory.get(repo, {}).items():
            graph.visit(digest, timestamp(pushed))
        expired = [
            tag
            for tag in tags.values()
            if tag["name"] != "latest" and timestamp(tag["pushed_at"]) < cutoff
        ]
        expired_names = {tag["name"] for tag in expired}
        protected = graph.reachable(
            tag["digest"] for tag in tags.values() if tag["name"] not in expired_names
        )
        candidates = {
            digest
            for digest, pushed in graph.pushed.items()
            if digest not in protected and pushed < cutoff
        }
        plan["repositories"][repo] = {
            "tags": expired,
            "manifests": graph.deletion_order(candidates),
        }
        journal["repositories"][repo] = {
            digest: iso(pushed) for digest, pushed in graph.pushed.items()
        }
        print(
            f"{repo}: {len(expired)} expired tags, {len(candidates)} expired manifests, "
            f"{len(protected)} protected manifests",
            flush=True,
        )
    return plan, journal


def apply_plan(client, plan, now, dry_run=False):
    if plan.get("version") != 1 or plan.get("namespace") != client.namespace:
        raise RetentionError("Plan version or namespace does not match")
    generated = timestamp(plan["generated_at"])
    if not timedelta(0) <= now - generated <= timedelta(hours=6):
        raise RetentionError("Plan is stale or from the future; generate a new plan")
    cutoff = timestamp(plan["cutoff"])
    if cutoff > generated - timedelta(days=1):
        raise RetentionError("Unsafe retention cutoff")
    if not dry_run and not client.token:
        raise RetentionError("DOCKERHUB_TOKEN is required for deletion")
    blocked = []
    confirmed = 0
    for repo, operations in plan["repositories"].items():
        if repo not in REPOSITORIES:
            raise RetentionError("Plan contains unexpected repository")
        planned_tags = {}
        for tag in operations["tags"]:
            name = tag["name"]
            if (
                not TAG.fullmatch(name)
                or name == "latest"
                or name in planned_tags
                or timestamp(tag["pushed_at"]) >= cutoff
            ):
                raise RetentionError("Unsafe tag deletion in plan")
            check_digest(tag["digest"])
            planned_tags[name] = tag
        for digest in operations["manifests"]:
            check_digest(digest)
        current = client.tags(repo)
        if "latest" not in current:
            raise RetentionError(f"{repo}: latest disappeared; refusing to prune")
        for name, tag in planned_tags.items():
            if name in current and current[name] != tag:
                raise RetentionError(
                    f"{repo}:{name} changed after planning; refusing to prune"
                )
        graph = Graph(client, repo)
        kept = [tag for name, tag in current.items() if name not in planned_tags]
        for tag in kept:
            graph.visit(tag["digest"], timestamp(tag["pushed_at"]), required=True)
        protected = graph.reachable(tag["digest"] for tag in kept)
        # A new tag can protect a previously expired index or child. Registry
        # reference checks are the final guard against changes after this read.
        manifests = [d for d in operations["manifests"] if d not in protected]
        for name in planned_tags:
            print(
                f"{'Would delete' if dry_run else 'Deleting'} tag {repo}:{name}",
                flush=True,
            )
            if not dry_run:
                client.delete_tag(repo, name)
        for digest in manifests:
            print(
                f"{'Would delete' if dry_run else 'Deleting'} manifest {repo}@{digest}",
                flush=True,
            )
            if not dry_run:
                try:
                    client.delete_manifest(repo, digest)
                except ManifestDeleteBlocked as error:
                    # A legacy reference must not prevent independent images or
                    # repositories from being reclaimed. Still fail the job and
                    # leave the saved journal intact for every blocked digest.
                    blocked.append(f"{repo}@{digest}")
                    print(f"::error::{error}; retained in the retry inventory", flush=True)
                else:
                    confirmed += 1
                    print(f"Confirmed absent: {repo}@{digest}", flush=True)
    if blocked:
        raise RetentionError(
            f"Cleanup incomplete: {confirmed} manifest deletions confirmed, "
            f"{len(blocked)} blocked by HTTP 403. See the errors above; "
            "the saved inventory preserves every blocked digest for retry."
        )
    print(
        "Dry run: no tags, manifests, or blobs deleted."
        if dry_run
        else "Manifest deletion confirmed. Docker Hub reclaims unreferenced layers asynchronously.",
        flush=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", required=True)
    subparsers = parser.add_subparsers(dest="operation", required=True)
    planner = subparsers.add_parser("plan")
    planner.add_argument("--inventory", required=True)
    planner.add_argument("--inventory-out", required=True)
    planner.add_argument("--output", required=True)
    planner.add_argument("--days", type=int, default=7)
    executor = subparsers.add_parser("apply")
    executor.add_argument("--plan", required=True)
    executor.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    client = DockerHub(args.namespace, os.environ.get("DOCKERHUB_TOKEN", ""))
    now = datetime.now(timezone.utc)
    if args.operation == "plan":
        inventory = read_inventory(args.inventory, client.namespace)
        plan, journal = plan_retention(
            client,
            inventory["repositories"],
            now,
            args.days,
            history_version=inventory.get("history_version", 0),
        )
        write_json(args.inventory_out, journal)
        write_json(args.output, plan)
    else:
        apply_plan(client, json.loads(Path(args.plan).read_text()), now, args.dry_run)


if __name__ == "__main__":
    try:
        main()
    except (RetentionError, ValueError, KeyError, TypeError, OSError) as error:
        print(f"::error::Docker retention failed: {error}", file=sys.stderr)
        sys.exit(1)
