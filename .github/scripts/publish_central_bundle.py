#!/usr/bin/env python3
"""Upload a Maven Central bundle through the Central Portal Publisher API and wait for its outcome.

Usage: publish_central_bundle.py BUNDLE_ZIP DEPLOYMENT_NAME AUTO_PUBLISH

AUTO_PUBLISH is ``true`` (publishingType AUTOMATIC: wait until Central is publishing the deployment) or
``false`` (USER_MANAGED: wait until it is validated and left for a manual publish in the Portal).

The Portal token is read from MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD; it never appears in a
process argument or in the output. See https://central.sonatype.org/publish/publish-portal-api/.
"""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path

from assemble_central_bundle import _load_bundle_check

BUNDLE_CHECK = _load_bundle_check()
DEFAULT_PORTAL_URL = "https://central.sonatype.com"
# Validation of a release-sized bundle usually takes a few minutes; this is a patience limit only.
DEFAULT_TIMEOUT_SECONDS = 60 * 60
DEFAULT_POLL_SECONDS = 15
REQUEST_TIMEOUT_SECONDS = 300

IN_PROGRESS_STATES = {"PENDING", "VALIDATING"}


class PublishError(Exception):
    pass


def validate_bundle(bundle: Path, name: str) -> None:
    """Refuse partial/foreign distributions and over-budget ZIP payloads before any Portal mutation."""
    match = re.fullmatch(r"bootui-((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))", name)
    if match is None:
        raise PublishError("deployment name must be bootui-<stable version>")
    version = match[1]
    expected = set()
    for artifact in BUNDLE_CHECK.PUBLISHED:
        prefix = f"com/julien-dubois/bootui/{artifact}/{version}/{artifact}-{version}"
        payloads = [prefix + suffix for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")]
        payloads += [f"{prefix}-{classifier}.jar" for classifier in BUNDLE_CHECK.EXTRA_FILES.get(artifact, ())]
        expected.update(payload + suffix for payload in payloads for suffix in ("", ".md5", ".sha1", ".sha256", ".asc"))
    with zipfile.ZipFile(bundle) as archive:
        files = archive.infolist()
        if len(files) != len(expected) or {item.filename for item in files} != expected:
            raise PublishError(f"bundle must contain exactly all {len(BUNDLE_CHECK.PUBLISHED)} signed published coordinates")
        size = sum(item.file_size for item in files)
        if size >= BUNDLE_CHECK.MAX_RELEASE_BYTES:
            raise PublishError(f"release payload must be below {BUNDLE_CHECK.MAX_RELEASE_BYTES:,} bytes, regardless of ZIP compression")


def _authorization() -> str:
    username = os.environ.get("MAVEN_CENTRAL_USERNAME", "")
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD", "")
    if not username or not password:
        raise PublishError("MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be set")
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    return f"Bearer {token}"


def _request(url: str, authorization: str, data: bytes | None = None, content_type: str | None = None) -> bytes:
    request = urllib.request.Request(url, data=data or b"", method="POST")
    request.add_header("Authorization", authorization)
    if content_type:
        request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace").strip()
        raise PublishError(f"{url.split('?')[0]} answered HTTP {error.code}: {body[:2000]}") from None


def upload(portal_url: str, authorization: str, bundle: Path, name: str, auto_publish: bool) -> str:
    boundary = uuid.uuid4().hex
    body = b"".join(
        (
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="bundle"; filename="{bundle.name}"\r\n'.encode(),
            b"Content-Type: application/octet-stream\r\n\r\n",
            bundle.read_bytes(),
            f"\r\n--{boundary}--\r\n".encode(),
        )
    )
    query = urllib.parse.urlencode(
        {"name": name, "publishingType": "AUTOMATIC" if auto_publish else "USER_MANAGED"}
    )
    response = _request(
        f"{portal_url}/api/v1/publisher/upload?{query}",
        authorization,
        body,
        f"multipart/form-data; boundary={boundary}",
    )
    deployment_id = response.decode("utf-8").strip()
    if not deployment_id:
        raise PublishError("the Central Portal returned no deployment id")
    return deployment_id


def wait(
    portal_url: str,
    authorization: str,
    deployment_id: str,
    auto_publish: bool,
    timeout_seconds: float,
    poll_seconds: float,
) -> str:
    done = {"PUBLISHING", "PUBLISHED"} if auto_publish else {"VALIDATED"}
    url = f"{portal_url}/api/v1/publisher/status?{urllib.parse.urlencode({'id': deployment_id})}"
    deadline = time.monotonic() + timeout_seconds
    last_state = None
    while True:
        try:
            status = json.loads(_request(url, authorization))
        except (PublishError, OSError, ValueError) as error:
            # A status read is idempotent, so a transient failure is retried until the deadline.
            print(f"Status of deployment {deployment_id} unavailable, retrying: {error}", flush=True)
            status = {}
        state = status.get("deploymentState")
        if state and state != last_state:
            print(f"Deployment {deployment_id} is {state}", flush=True)
            last_state = state
        if state in done:
            return state
        if state == "FAILED":
            errors = json.dumps(status.get("errors", {}), indent=2, sort_keys=True)
            raise PublishError(f"deployment {deployment_id} failed validation:\n{errors}")
        if state and state not in IN_PROGRESS_STATES | {"VALIDATED", "PUBLISHING", "PUBLISHED"}:
            raise PublishError(f"deployment {deployment_id} is in unexpected state {state}")
        if time.monotonic() >= deadline:
            raise PublishError(
                f"deployment {deployment_id} did not reach {' or '.join(sorted(done))} in time "
                f"(last state: {last_state or 'unknown'}); check it in the Central Portal"
            )
        time.sleep(poll_seconds)


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[3] not in ("true", "false"):
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    bundle, name, auto_publish = Path(argv[1]), argv[2], argv[3] == "true"
    portal_url = os.environ.get("BOOTUI_CENTRAL_PORTAL_URL", DEFAULT_PORTAL_URL).rstrip("/")
    timeout_seconds = float(os.environ.get("BOOTUI_CENTRAL_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS))
    poll_seconds = float(os.environ.get("BOOTUI_CENTRAL_POLL_SECONDS", DEFAULT_POLL_SECONDS))
    try:
        if not bundle.is_file():
            raise PublishError(f"bundle {bundle} does not exist")
        validate_bundle(bundle, name)
        authorization = _authorization()
        deployment_id = upload(portal_url, authorization, bundle, name, auto_publish)
        print(f"Uploaded {bundle.name} as Central Portal deployment {deployment_id}", flush=True)
        state = wait(portal_url, authorization, deployment_id, auto_publish, timeout_seconds, poll_seconds)
    except (PublishError, OSError, zipfile.BadZipFile) as error:
        print(f"::error::Maven Central publication failed: {error}", file=sys.stderr)
        return 1
    if state == "VALIDATED":
        print(f"Deployment {deployment_id} is validated; publish it in the Central Portal.")
    else:
        print(f"Deployment {deployment_id} is {state} on Maven Central.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
