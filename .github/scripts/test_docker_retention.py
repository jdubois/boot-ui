import copy
import hashlib
import io
import json
import os
import subprocess
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock
from urllib.parse import urlsplit

from docker_retention import (
    DockerHub,
    NoRedirect,
    REPOSITORIES,
    Response,
    RetentionError,
    apply_plan,
    iso,
    plan_retention,
    read_inventory,
    request,
    write_json,
)

REPO = "bootui-sample-app-quarkus"
NOW = datetime(2026, 9, 9, 12, tzinfo=timezone.utc)
OLD = iso(NOW - timedelta(days=8))
NEW = iso(NOW - timedelta(days=1))


def digest(name):
    return "sha256:" + hashlib.sha256(name.encode()).hexdigest()


def tag(name, image, pushed=OLD):
    return {"name": name, "digest": digest(image), "pushed_at": pushed}


class FakeHub:
    namespace = "jdubois"
    token = "test-token"

    def __init__(self):
        self.tag_data = {
            "latest": tag("latest", "new-index", NEW),
            "20260901": tag("20260901", "old-index"),
            "sha-old": tag("sha-old", "old-index"),
        }
        self.images = {
            digest("new-index"): [digest("shared"), digest("new-arm")],
            digest("old-index"): [digest("shared"), digest("old-arm")],
            digest("shared"): [],
            digest("new-arm"): [],
            digest("old-arm"): [],
        }
        self.deleted = []
        self.fail_digest = None

    def tags(self, repo):
        return copy.deepcopy(self.tag_data)

    def manifest(self, repo, image):
        return self.images.get(image)

    def delete_tag(self, repo, name):
        self.deleted.append(("tag", name))
        self.tag_data.pop(name, None)

    def delete_manifest(self, repo, image):
        if image == self.fail_digest:
            raise RetentionError("Deletion still pending")
        if any(t["digest"] == image for t in self.tag_data.values()):
            raise AssertionError("Attempted to delete a tagged image")
        if any(image in children for children in self.images.values()):
            raise AssertionError("Attempted to delete a referenced child")
        self.deleted.append(("manifest", image))
        self.images.pop(image, None)


class RetentionTests(unittest.TestCase):
    def setUp(self):
        self.hub = FakeHub()
        self.output = io.StringIO()
        self.redirect = redirect_stdout(self.output)
        self.redirect.__enter__()
        self.addCleanup(self.redirect.__exit__, None, None, None)

    def plan(self, history=None):
        return plan_retention(
            self.hub, history or {}, NOW, repositories=(REPO,)
        )

    def test_deletes_expired_indexes_before_children_and_keeps_shared_platform(self):
        plan, journal = self.plan()
        operations = plan["repositories"][REPO]
        self.assertEqual(
            operations["manifests"], [digest("old-index"), digest("old-arm")]
        )
        self.assertIn(digest("old-arm"), journal["repositories"][REPO])
        apply_plan(self.hub, plan, NOW)
        self.assertEqual(
            self.hub.deleted,
            [
                ("tag", "20260901"),
                ("tag", "sha-old"),
                ("manifest", digest("old-index")),
                ("manifest", digest("old-arm")),
            ],
        )
        self.assertIn(digest("shared"), self.hub.images)
        self.assertEqual(set(self.hub.tag_data), {"latest"})

    def test_latest_is_kept_even_when_older_than_seven_days(self):
        self.hub.tag_data["latest"]["pushed_at"] = OLD
        plan, _ = self.plan()
        self.assertNotIn(digest("new-index"), plan["repositories"][REPO]["manifests"])

    def test_exact_cutoff_and_recent_pushes_are_kept(self):
        for name, pushed in (("boundary", iso(NOW - timedelta(days=7))), ("fresh", NEW)):
            self.hub.images[digest(name)] = []
            self.hub.tag_data[name] = tag(name, name, pushed)
        plan, _ = self.plan()
        self.assertEqual(
            {t["name"] for t in plan["repositories"][REPO]["tags"]},
            {"20260901", "sha-old"},
        )

    def test_removes_old_alias_without_deleting_recently_tagged_image(self):
        self.hub.tag_data["recent-alias"] = tag("recent-alias", "old-index", NEW)
        plan, _ = self.plan()
        self.assertEqual(plan["repositories"][REPO]["manifests"], [])
        apply_plan(self.hub, plan, NOW)
        self.assertIn(digest("old-index"), self.hub.images)

    def test_old_failed_build_is_discoverable_through_retention_tag(self):
        self.hub.tag_data["build-123-1-linux-amd64"] = tag(
            "build-123-1-linux-amd64", "failed-build"
        )
        self.hub.images[digest("failed-build")] = []
        plan, _ = self.plan()
        self.assertIn(digest("failed-build"), plan["repositories"][REPO]["manifests"])

    def test_retention_index_survives_overwritten_release_tags_without_a_journal(self):
        self.hub.tag_data.pop("20260901")
        self.hub.tag_data.pop("sha-old")
        self.hub.tag_data["build-123-1-index"] = tag("build-123-1-index", "old-index")
        plan, _ = self.plan()
        self.assertEqual(
            plan["repositories"][REPO]["manifests"],
            [digest("old-index"), digest("old-arm")],
        )
        apply_plan(self.hub, plan, NOW)

    def test_legacy_orphan_is_recovered_and_recent_orphan_is_kept(self):
        self.hub.images[digest("legacy")] = [digest("legacy-child")]
        self.hub.images[digest("legacy-child")] = []
        self.hub.images[digest("recent-orphan")] = []
        plan, journal = self.plan(
            {REPO: {digest("legacy"): OLD, digest("recent-orphan"): NEW}}
        )
        candidates = plan["repositories"][REPO]["manifests"]
        self.assertIn(digest("legacy"), candidates)
        self.assertIn(digest("legacy-child"), candidates)
        self.assertNotIn(digest("recent-orphan"), candidates)
        self.assertIn(digest("legacy-child"), journal["repositories"][REPO])

    def test_retry_recovers_child_after_tags_and_parent_were_deleted(self):
        plan, journal = self.plan()
        self.hub.fail_digest = digest("old-arm")
        with self.assertRaisesRegex(RetentionError, "pending"):
            apply_plan(self.hub, plan, NOW)
        self.hub.fail_digest = None
        next_plan, next_journal = self.plan(journal["repositories"])
        self.assertNotIn(digest("old-index"), next_journal["repositories"][REPO])
        self.assertEqual(
            next_plan["repositories"][REPO]["manifests"], [digest("old-arm")]
        )
        apply_plan(self.hub, next_plan, NOW)
        self.assertNotIn(digest("old-arm"), self.hub.images)

    def test_missing_historical_manifest_is_idempotently_removed_from_inventory(self):
        _, journal = self.plan({REPO: {digest("already-deleted"): OLD}})
        self.assertNotIn(digest("already-deleted"), journal["repositories"][REPO])

    def test_orphan_index_with_already_deleted_child_can_be_cleaned(self):
        self.hub.images[digest("legacy")] = [digest("already-deleted")]
        plan, journal = self.plan({REPO: {digest("legacy"): OLD}})
        self.assertIn(digest("legacy"), plan["repositories"][REPO]["manifests"])
        self.assertNotIn(digest("already-deleted"), journal["repositories"][REPO])
        apply_plan(self.hub, plan, NOW)

    def test_missing_latest_or_referenced_child_fails_before_mutations(self):
        for missing in ("latest", "child"):
            with self.subTest(missing=missing):
                self.hub = FakeHub()
                if missing == "latest":
                    self.hub.tag_data.pop("latest")
                else:
                    self.hub.images.pop(digest("shared"))
                with self.assertRaises(RetentionError):
                    self.plan()
                self.assertEqual(self.hub.deleted, [])

    def test_nested_indexes_are_deleted_in_topological_order(self):
        self.hub.images[digest("nested")] = [digest("old-arm")]
        self.hub.images[digest("old-index")] = [digest("nested")]
        plan, _ = self.plan()
        self.assertEqual(
            plan["repositories"][REPO]["manifests"],
            [digest("old-index"), digest("nested"), digest("old-arm")],
        )

    def test_multiple_expired_parents_are_deleted_before_shared_child(self):
        self.hub.images[digest("other-index")] = [digest("old-arm")]
        plan, _ = self.plan({REPO: {digest("other-index"): OLD}})
        candidates = plan["repositories"][REPO]["manifests"]
        self.assertLess(
            candidates.index(digest("other-index")), candidates.index(digest("old-arm"))
        )
        self.assertLess(
            candidates.index(digest("old-index")), candidates.index(digest("old-arm"))
        )
        apply_plan(self.hub, plan, NOW)

    def test_cycle_is_rejected(self):
        self.hub.images[digest("shared")] = [digest("new-index")]
        with self.assertRaisesRegex(RetentionError, "Cyclic"):
            self.plan()

    def test_dry_run_does_not_delete_tags_manifests_or_require_credentials(self):
        self.hub.token = ""
        plan, _ = self.plan()
        apply_plan(self.hub, plan, NOW, dry_run=True)
        self.assertEqual(self.hub.deleted, [])
        self.assertIn("Would delete tag", self.output.getvalue())
        self.assertIn("Would delete manifest", self.output.getvalue())
        with self.assertRaisesRegex(RetentionError, "DOCKERHUB_TOKEN"):
            apply_plan(self.hub, plan, NOW)

    def test_changed_expired_tag_fails_before_any_deletion(self):
        plan, _ = self.plan()
        self.hub.tag_data["20260901"]["pushed_at"] = NEW
        with self.assertRaisesRegex(RetentionError, "changed after planning"):
            apply_plan(self.hub, plan, NOW)
        self.assertEqual(self.hub.deleted, [])

    def test_new_tag_protects_candidate_and_all_its_children(self):
        plan, _ = self.plan()
        self.hub.tag_data["new-reference"] = tag("new-reference", "old-index", NEW)
        apply_plan(self.hub, plan, NOW)
        self.assertFalse(any(kind == "manifest" for kind, _ in self.hub.deleted))

    def test_stale_plan_invalid_namespace_and_latest_deletion_fail(self):
        plan, _ = self.plan()
        invalid = [
            {**plan, "namespace": "different"},
            {**plan, "generated_at": iso(NOW - timedelta(hours=7))},
            {**plan, "generated_at": iso(NOW + timedelta(minutes=1))},
            {**plan, "cutoff": iso(NOW)},
        ]
        bad_latest = copy.deepcopy(plan)
        bad_latest["repositories"][REPO]["tags"].append(tag("latest", "new-index"))
        invalid.append(bad_latest)
        for bad in invalid:
            with self.subTest(plan=bad), self.assertRaises(RetentionError):
                apply_plan(self.hub, bad, NOW)
        self.assertEqual(self.hub.deleted, [])

    def test_inventory_round_trip_and_validation(self):
        _, journal = self.plan()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.json"
            write_json(path, journal)
            self.assertEqual(read_inventory(path, "jdubois"), journal["repositories"])
            with self.assertRaises(RetentionError):
                read_inventory(path, "someone-else")
            journal["repositories"]["unexpected"] = {}
            write_json(path, journal)
            with self.assertRaises(RetentionError):
                read_inventory(path, "jdubois")


class ApiTests(unittest.TestCase):
    def json_response(self, value):
        return Response(200, json.dumps(value).encode())

    def test_paginates_all_tags_and_uses_push_not_pull_or_metadata_update(self):
        responses = []
        for page in range(3):
            results = [
                {
                    "name": f"build-{i}",
                    "digest": digest(str(i)),
                    "tag_last_pushed": OLD,
                    "tag_last_pulled": NEW,
                    "last_updated": NEW,
                }
                for i in range(page * 100, min((page + 1) * 100, 205))
            ]
            next_url = (
                f"https://hub.docker.com/v2/repositories/jdubois/{REPO}/tags/?page={page + 2}"
                if page < 2
                else None
            )
            responses.append(self.json_response({"results": results, "next": next_url}))
        transport = Mock(side_effect=responses)
        hub = DockerHub("jdubois", transport=transport)
        tags = hub.tags(REPO)
        self.assertEqual(len(tags), 205)
        self.assertEqual(tags["build-204"]["pushed_at"], OLD)
        self.assertTrue(all(call.args[0] == "GET" for call in transport.call_args_list))

    def test_unsafe_and_cyclic_pagination_fail_closed(self):
        base = f"https://hub.docker.com/v2/repositories/jdubois/{REPO}/tags/"
        for next_url in (
            "https://evil.example/tags",
            base + "?page_size=100",
            base.replace("https:", "http:"),
        ):
            transport = Mock(
                return_value=self.json_response({"results": [], "next": next_url})
            )
            with self.subTest(next=next_url), self.assertRaisesRegex(
                RetentionError, "pagination"
            ):
                DockerHub("jdubois", transport=transport).tags(REPO)
            self.assertEqual(transport.call_count, 1)

    def test_unauthorized_inventory_is_not_treated_as_empty(self):
        for code in (401, 403, 404, 429, 500):
            transport = Mock(return_value=Response(code, b"error"))
            with self.subTest(code=code), self.assertRaisesRegex(
                RetentionError, str(code)
            ):
                DockerHub("jdubois", transport=transport).tags(REPO)

    def test_missing_timestamp_and_unsupported_manifest_fail_closed(self):
        transport = Mock(
            side_effect=[
                self.json_response({"token": "test"}),
                Response(200, media_type="unsupported"),
            ]
        )
        with self.assertRaisesRegex(RetentionError, "Unsupported"):
            DockerHub("jdubois", transport=transport).manifest(REPO, digest("image"))
        transport = Mock(
            return_value=self.json_response(
                {"results": [{"name": "old", "digest": digest("old")}], "next": None}
            )
        )
        with self.assertRaises(KeyError):
            DockerHub("jdubois", transport=transport).tags(REPO)

    def test_deletion_requests_delete_scope_and_confirms_absence_without_deleting_blobs(self):
        transport = Mock(
            side_effect=[
                self.json_response({"token": "write"}),
                Response(202),
                self.json_response({"token": "read"}),
                Response(200),
                Response(404),
            ]
        )
        hub = DockerHub("jdubois", token="test", transport=transport, sleep=Mock())
        hub.delete_manifest(REPO, digest("old"))
        self.assertIn("pull%2Cpush%2Cdelete", transport.call_args_list[0].args[1])
        deletes = [c.args for c in transport.call_args_list if c.args[0] == "DELETE"]
        self.assertEqual(len(deletes), 1)
        self.assertIn("/manifests/", deletes[0][1])
        self.assertNotIn("/blobs/", deletes[0][1])
        self.assertEqual(transport.call_args_list[-1].args[0], "HEAD")

    def test_queued_500_is_confirmed_without_repeating_delete(self):
        transport = Mock(
            side_effect=[
                self.json_response({"token": "write"}),
                Response(500),
                self.json_response({"token": "read"}),
                Response(404),
            ]
        )
        hub = DockerHub("jdubois", token="test", transport=transport, sleep=Mock())
        hub.delete_manifest(REPO, digest("old"))
        self.assertEqual(sum(c.args[0] == "DELETE" for c in transport.call_args_list), 1)

    def test_pending_deletion_or_permission_denial_is_a_failure(self):
        for code in (401, 403, 405, 429, 202):
            transport = Mock(
                side_effect=[
                    self.json_response({"token": "write"}),
                    Response(code),
                    self.json_response({"token": "read"}),
                    *[Response(200)] * 6,
                ]
            )
            with self.subTest(code=code), self.assertRaises(RetentionError):
                hub = DockerHub("jdubois", token="test", transport=transport, sleep=Mock())
                hub.delete_manifest(REPO, digest("old"))

    def test_missing_manifest_delete_is_idempotent(self):
        transport = Mock(
            side_effect=[self.json_response({"token": "write"}), Response(404)]
        )
        DockerHub("jdubois", token="test", transport=transport).delete_manifest(
            REPO, digest("old")
        )
        self.assertEqual(transport.call_count, 2)

    def test_tag_delete_errors_are_not_ignored(self):
        transport = Mock(
            side_effect=[self.json_response({"token": "hub"}), Response(403)]
        )
        with self.assertRaisesRegex(RetentionError, "403"):
            DockerHub("jdubois", token="test", transport=transport).delete_tag(REPO, "old")

    def test_redirects_cannot_forward_authorization(self):
        self.assertIsNone(
            NoRedirect().redirect_request(None, None, 302, "", {}, "https://evil.example")
        )

    def test_namespace_is_validated_before_requests(self):
        transport = Mock()
        with self.assertRaisesRegex(RetentionError, "namespace"):
            DockerHub("../different-owner", transport=transport)
        transport.assert_not_called()

    def test_leaf_discovery_uses_head_instead_of_consuming_image_pull_quota(self):
        transport = Mock(
            side_effect=[
                self.json_response({"token": "test"}),
                Response(200, media_type="application/vnd.oci.image.manifest.v1+json"),
            ]
        )
        hub = DockerHub("jdubois", transport=transport)
        self.assertEqual(hub.manifest(REPO, digest("leaf")), [])
        self.assertEqual(transport.call_args_list[-1].args[0], "HEAD")

    def test_index_discovery_fetches_children_after_head(self):
        index_type = "application/vnd.oci.image.index.v1+json"
        transport = Mock(
            side_effect=[
                self.json_response({"token": "test"}),
                Response(200, media_type=index_type),
                self.json_response(
                    {
                        "schemaVersion": 2,
                        "mediaType": index_type,
                        "manifests": [{"digest": digest("child")}],
                    }
                ),
            ]
        )
        hub = DockerHub("jdubois", transport=transport)
        self.assertEqual(hub.manifest(REPO, digest("index")), [digest("child")])
        self.assertEqual(
            [c.args[0] for c in transport.call_args_list[-2:]], ["HEAD", "GET"]
        )


class WorkflowTests(unittest.TestCase):
    def test_retention_directory_is_initialized_at_step_scope(self):
        workflow = (
            Path(__file__).resolve().parents[1] / "workflows/docker-publish.yml"
        ).read_text()
        prune = workflow.split("\n  prune:\n")[1]
        job_env = prune.split("\n    env:\n")[1].split("\n    steps:\n")[0]
        self.assertNotIn("${{ runner.", job_env)
        self.assertLess(
            prune.index("name: Initialize retention directory"),
            prune.index("name: Restore inventory"),
        )
        step = prune.split("      - name: Initialize retention directory\n")[1]
        script = step.split("\n      - name:")[0].strip().removeprefix("run: ")
        with tempfile.TemporaryDirectory(prefix="retention test ") as directory:
            github_env = Path(directory) / "github-env"
            subprocess.run(
                ["bash", "-eu", "-c", script],
                env={
                    **os.environ,
                    "RUNNER_TEMP": directory,
                    "GITHUB_ENV": str(github_env),
                },
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                github_env.read_text(),
                f"RETENTION_DIR={directory}/docker-retention\n",
            )

    def test_cleanup_runs_after_failure_and_journal_upload_precedes_deletion(self):
        workflow = (
            Path(__file__).resolve().parents[1] / "workflows/docker-publish.yml"
        ).read_text()
        prune = workflow.split("\n  prune:\n")[1]
        self.assertIn("needs: [docker-config, build, merge]", prune)
        self.assertIn("always()", prune)
        self.assertIn("actions: read", prune)
        self.assertIn("group: docker-hub-publish", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("github.ref == 'refs/heads/main' || inputs.prune_dry_run", prune)
        self.assertIn(
            "docker-retention-inventory-${{ github.run_id }}-${{ github.run_attempt }}",
            prune,
        )
        self.assertLess(
            prune.index("uses: actions/upload-artifact@"),
            prune.index("name: Apply retention"),
        )
        self.assertNotIn("continue-on-error", prune)
        self.assertNotIn("DOCKERHUB_UNTAGGED_PRUNE", workflow)
        self.assertIn(":build-${{ github.run_id }}-${{ github.run_attempt }}-", workflow)
        self.assertIn(":build-${{ github.run_id }}-${{ github.run_attempt }}-index", workflow)
        self.assertLess(
            workflow.index("name: Create the retention-tagged manifest list"),
            workflow.index("name: Apply release tags to the retained manifest list"),
        )


class HttpIntegrationTests(unittest.TestCase):
    def test_all_six_repositories_through_real_http_transport(self):
        repositories = {repo: FakeHub() for repo in REPOSITORIES}
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def respond(self, status, value=None, media_type="application/json"):
                body = b"" if value is None else json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", media_type)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

            def route(self):
                path = urlsplit(self.path).path
                calls.append((self.command, path))
                if path == "/token" or path == "/v2/users/login":
                    self.respond(200, {"token": "fixture-token"})
                    return
                parts = path.split("/")
                if "/tags/" in path:
                    repo = parts[4]
                    hub = repositories[repo]
                    if self.command == "GET":
                        self.respond(
                            200,
                            {
                                "results": [
                                    {
                                        "name": tag["name"],
                                        "digest": tag["digest"],
                                        "tag_last_pushed": tag["pushed_at"],
                                    }
                                    for tag in hub.tag_data.values()
                                ],
                                "next": None,
                            },
                        )
                    elif self.headers.get("Authorization") != "JWT fixture-token":
                        self.respond(401)
                    else:
                        hub.delete_tag(repo, parts[6])
                        self.respond(204)
                    return
                if "/manifests/" in path:
                    repo, image = parts[3], parts[5]
                    hub = repositories[repo]
                    if image not in hub.images:
                        self.respond(404)
                        return
                    if self.command == "DELETE":
                        if self.headers.get("Authorization") != "Bearer fixture-token":
                            self.respond(401)
                            return
                        hub.delete_manifest(repo, image)
                        self.respond(202)
                        return
                    children = hub.images[image]
                    media_type = (
                        "application/vnd.oci.image.index.v1+json"
                        if children
                        else "application/vnd.oci.image.manifest.v1+json"
                    )
                    self.respond(
                        200,
                        {
                            "schemaVersion": 2,
                            "mediaType": media_type,
                            "manifests": [{"digest": child} for child in children],
                        },
                        media_type,
                    )
                    return
                self.respond(404)

            do_GET = route
            do_HEAD = route
            do_POST = route
            do_DELETE = route

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 5)
        self.addCleanup(server.shutdown)

        def transport(method, url, headers, body):
            parsed = urlsplit(url)
            local_url = (
                f"http://127.0.0.1:{server.server_port}{parsed.path}?{parsed.query}"
            )
            return request(method, local_url, headers, body)

        client = DockerHub("jdubois", token="fixture-only", transport=transport)
        with redirect_stdout(io.StringIO()), tempfile.TemporaryDirectory() as directory:
            plan, inventory = plan_retention(client, {}, NOW)
            journal = Path(directory) / "inventory.json"
            write_json(journal, inventory)
            self.assertEqual(set(read_inventory(journal, "jdubois")), set(REPOSITORIES))
            apply_plan(client, plan, NOW, dry_run=True)
            self.assertFalse(any(method == "DELETE" for method, _ in calls))
            apply_plan(client, plan, NOW)
        for hub in repositories.values():
            self.assertEqual(set(hub.tag_data), {"latest"})
            self.assertEqual(
                set(hub.images),
                {digest("new-index"), digest("new-arm"), digest("shared")},
            )
        self.assertEqual(sum(method == "DELETE" for method, _ in calls), 24)
        self.assertFalse(any("/blobs/" in path for _, path in calls))


if __name__ == "__main__":
    unittest.main()
