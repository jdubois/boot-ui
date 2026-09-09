import copy
import io
import json
import unittest
import zipfile
from contextlib import redirect_stderr
from datetime import datetime, timedelta, timezone
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

import docker_retention_history as history

NOW = datetime(2026, 9, 9, 12, tzinfo=timezone.utc)
REPO = "bootui-sample-app-quarkus"
IMAGE = "docker.io/***/" + REPO
D1, D2, D3, CONFIG, BASE = ("sha256:" + char * 64 for char in "abcde")
OLD = "2026-09-01T08:04:00Z"
NEW = "2026-09-08T08:04:00Z"


def inventory(namespace="jdubois"):
    return {
        "version": 1,
        "history_version": history.HISTORY_VERSION,
        "namespace": namespace,
        "repositories": {
            repo: ({D1: OLD} if repo == REPO else {})
            for repo in history.REPOSITORIES
        },
    }


def archive(value, plan=None):
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w") as zipped:
        zipped.writestr("inventory.json", json.dumps(value))
        if plan is not None:
            zipped.writestr("plan.json", json.dumps(plan))
    return content.getvalue()


def artifact(identity=1, run_id=10, **overrides):
    return {
        "id": identity,
        "name": history.ARTIFACT,
        "created_at": NEW,
        "expires_at": "2026-12-01T00:00:00Z",
        "expired": False,
        "workflow_run": {"id": run_id, "head_branch": "main"},
        **overrides,
    }


def run(identity=10, **overrides):
    return {
        "id": identity,
        "workflow_id": 42,
        "head_branch": "main",
        "created_at": OLD,
        "updated_at": NEW,
        "status": "completed",
        "conclusion": "success",
        **overrides,
    }


def merge_job(identity=20, repository=REPO, **overrides):
    return {
        "id": identity,
        "name": f"Merge {repository} manifests",
        "conclusion": "success",
        "steps": [],
        **overrides,
    }


def build_job(identity=21, repository=REPO, **overrides):
    return {
        "id": identity,
        "name": f"Build, smoke-test and publish {repository} (linux/amd64)",
        "conclusion": "failure",
        "steps": [{
            "name": "Push the smoke-tested image by digest",
            "conclusion": "success",
            "started_at": "2026-09-01T08:03:00Z",
            "completed_at": "2026-09-01T08:03:10Z",
        }],
        **overrides,
    }


def log(*lines, at="2026-09-01T08:03:05.1234567Z"):
    return "".join(f"{at} {line}\n" for line in lines).encode()


def merge_log(repository=REPO):
    image = "docker.io/***/" + repository
    return log(
        f"#1 0.000 copying {D1} from {image}@{D1} to {image}",
        f"#1 0.000 copying {D2} from {image}@{D2} to {image}",
        f"#1 0.603 pushing {D3} to {image}:20260901",
        f"#1 2.093 pushing {D3} to {image}:latest",
    )


def build_log():
    return log(
        f"#8 FROM docker.io/library/eclipse-temurin:21@{BASE}",
        f"#17 exporting manifest {D1} done",
        f"#17 exporting config {CONFIG} done",
        "#17 pushing layers 14.3s done",
        f"#17 pushing manifest for {IMAGE}",
        f"#17 pushing manifest for {IMAGE} 0.5s done",
        "##[group]ImageID",
        CONFIG,
        "##[group]Digest",
        D1,
    )


class FakeGitHub:
    def __init__(self, artifacts=None, runs=None, jobs=None, downloads=None):
        self.artifacts = artifacts or []
        self.history = runs or []
        self.jobs = jobs or {}
        self.downloads = downloads or {}
        self.requested = []
        self.run_reads = []
        self.bounds = None

    def json(self, path):
        if path == "/actions/workflows/docker-publish.yml":
            return {"id": 42, "path": history.WORKFLOW}
        identity = int(path.rsplit("/", 1)[1])
        self.run_reads.append(identity)
        return next(item for item in self.history if item["id"] == identity)

    def pages(self, path, key, params=None):
        if key == "artifacts":
            return iter(self.artifacts)
        identity = int(path.split("/")[-2])
        return iter(self.jobs.get(identity, []))

    def runs(self, since, until):
        self.bounds = since, until
        return iter(self.history)

    def download(self, path):
        self.requested.append(path)
        value = self.downloads[path]
        if isinstance(value, Exception):
            raise value
        return value


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.stderr = io.StringIO()
        redirect = redirect_stderr(self.stderr)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)

    def recover(self, client):
        return history.recover(client, "jdubois", "999", NOW)

    def test_restores_failed_run_artifact_without_reading_job_logs(self):
        client = FakeGitHub(
            [artifact()], [run(conclusion="failure")],
            downloads={"/actions/artifacts/1/zip": archive(inventory())},
        )
        self.assertEqual(inventory(), self.recover(client))
        self.assertEqual(["/actions/artifacts/1/zip"], client.requested)
        self.assertIsNone(client.bounds)

    def test_refreshes_legacy_inventory_without_losing_retry_records_or_newer_pushes(self):
        prior = inventory()
        prior.pop("history_version")
        prior["repositories"][REPO] = {D1: history.iso(NOW), BASE: OLD}
        source_copy = log(f"#1 0.000 copying {D1} from {IMAGE}@{CONFIG} to {IMAGE}")
        client = FakeGitHub(
            [artifact()], [run()], jobs={10: [merge_job()]},
            downloads={
                "/actions/artifacts/1/zip": archive(prior),
                "/actions/jobs/20/logs": merge_log() + source_copy,
            },
        )
        result = self.recover(client)
        self.assertEqual(history.HISTORY_VERSION, result["history_version"])
        self.assertEqual(
            {D1: history.iso(NOW), D2: NEW, D3: NEW, CONFIG: NEW, BASE: OLD},
            result["repositories"][REPO],
        )
        self.assertIn("Refreshing legacy inventory", self.stderr.getvalue())
        self.assertIsNotNone(client.bounds)

    def test_failed_legacy_refresh_does_not_return_a_successful_partial_inventory(self):
        prior = inventory()
        prior.pop("history_version")
        client = FakeGitHub(
            [artifact()], [run()], jobs={10: [merge_job()]},
            downloads={
                "/actions/artifacts/1/zip": archive(prior),
                "/actions/jobs/20/logs": history.APIError(403),
            },
        )
        with self.assertRaises(history.APIError):
            self.recover(client)

    def test_selects_freshest_by_creation_and_id_not_run_status(self):
        client = FakeGitHub(
            [artifact(9, created_at=OLD), artifact(1), artifact(2)],
            [run(conclusion="cancelled")],
            downloads={"/actions/artifacts/2/zip": archive(inventory())},
        )
        self.recover(client)
        self.assertEqual(["/actions/artifacts/2/zip"], client.requested)

    def test_accepts_workflow_run_attempt_suffix_without_unrelated_artifacts(self):
        client = FakeGitHub(
            [
                artifact(5, name=f"{history.ARTIFACT}-11-1"),
                artifact(4, name=f"{history.ARTIFACT}-10-0"),
                artifact(3, name=f"{history.ARTIFACT}-10-unrelated"),
                artifact(2, name=f"{history.ARTIFACT}-10-2"),
                artifact(1),
            ],
            [run(conclusion="failure")],
            downloads={"/actions/artifacts/2/zip": archive(inventory())},
        )
        self.assertEqual(inventory(), self.recover(client))
        self.assertEqual(["/actions/artifacts/2/zip"], client.requested)

    def test_restores_current_run_earlier_attempt_with_optional_plan(self):
        for status, conclusion in (("completed", "failure"), ("in_progress", None)):
            with self.subTest(status=status):
                client = FakeGitHub(
                    [
                        artifact(1),
                        artifact(3, 999, name=f"{history.ARTIFACT}-999-2"),
                        artifact(2, 999, name=f"{history.ARTIFACT}-999-1"),
                    ],
                    [run(), run(999, status=status, conclusion=conclusion, run_attempt=3)],
                    downloads={
                        "/actions/artifacts/3/zip": archive(inventory(), {"operations": []})
                    },
                )
                self.assertEqual(inventory(), self.recover(client))
                self.assertEqual(["/actions/artifacts/3/zip"], client.requested)
                self.assertIsNone(client.bounds)

    def test_restores_exact_legacy_name_from_current_run(self):
        client = FakeGitHub(
            [artifact(run_id=999)], [run(999, conclusion="failure")],
            downloads={"/actions/artifacts/1/zip": archive(inventory())},
        )
        self.assertEqual(inventory(), self.recover(client))
        self.assertIsNone(client.bounds)

    def test_ignores_other_workflows_branches_and_expired_artifacts(self):
        client = FakeGitHub(
            [
                artifact(10, 15),
                artifact(9, 14, workflow_run={"id": 14, "head_branch": "feature"}),
                artifact(7, expired=True),
                artifact(6, expires_at=OLD),
                artifact(5, name="unrelated-inventory"),
                artifact(4),
            ],
            [run(15, workflow_id=100), run()],
            downloads={"/actions/artifacts/4/zip": archive(inventory())},
        )
        self.assertEqual(inventory(), self.recover(client))
        self.assertEqual([15, 10], client.run_reads)
        self.assertEqual(["/actions/artifacts/4/zip"], client.requested)

    def test_verifies_actual_run_branch_not_only_artifact_branch(self):
        client = FakeGitHub([artifact()], [run(head_branch="feature")])
        result = self.recover(client)
        self.assertEqual({}, result["repositories"][REPO])
        self.assertFalse(client.requested)

    def test_wrong_namespace_is_fatal_without_bootstrap_or_stale_fallback(self):
        client = FakeGitHub(
            [artifact(), artifact(2, created_at=OLD)], [run()],
            downloads={"/actions/artifacts/1/zip": archive(inventory("other"))},
        )
        with self.assertRaisesRegex(history.HistoryError, "namespace"):
            self.recover(client)
        self.assertIsNone(client.bounds)

    def test_unavailable_artifact_is_fatal_instead_of_losing_journal(self):
        for status in (401, 403, 404, 410, 500):
            with self.subTest(status=status):
                client = FakeGitHub(
                    [artifact()], [run()],
                    downloads={"/actions/artifacts/1/zip": history.APIError(status)},
                )
                with self.assertRaises(history.APIError):
                    self.recover(client)
                self.assertIsNone(client.bounds)

    def test_invalid_zip_and_inventory_are_fatal(self):
        bad_digest = inventory()
        bad_digest["repositories"][REPO] = {"sha256:short": OLD}
        bad_repo = inventory()
        bad_repo["repositories"]["unrelated-image"] = {}
        bad_timestamp = inventory()
        bad_timestamp["repositories"][REPO] = {D1: "2026-09-01"}
        for data in (b"not a zip", archive(bad_digest), archive(bad_repo), archive(bad_timestamp)):
            with self.subTest(data=data[:10]):
                client = FakeGitHub(
                    [artifact()], [run()],
                    downloads={"/actions/artifacts/1/zip": data},
                )
                with self.assertRaises(history.HistoryError):
                    self.recover(client)

    def test_zip_entries_never_extracted_or_path_traversed(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, "w") as zipped:
            zipped.writestr("../inventory.json", json.dumps(inventory()))
        client = FakeGitHub(
            [artifact()], [run()],
            downloads={"/actions/artifacts/1/zip": content.getvalue()},
        )
        with self.assertRaisesRegex(history.HistoryError, "contain inventory.json"):
            self.recover(client)

    def test_bootstrap_reads_only_six_merge_logs_for_a_successful_run(self):
        jobs = [merge_job(i + 20, repo) for i, repo in enumerate(history.REPOSITORIES)]
        jobs.extend(build_job(i + 30, repo) for i, repo in enumerate(history.REPOSITORIES))
        client = FakeGitHub(
            runs=[run()], jobs={10: jobs},
            downloads={
                f"/actions/jobs/{i + 20}/logs": merge_log(repo)
                for i, repo in enumerate(history.REPOSITORIES)
            },
        )
        result = self.recover(client)
        self.assertEqual(set(history.REPOSITORIES), set(result["repositories"]))
        for records in result["repositories"].values():
            self.assertEqual({D1: NEW, D2: NEW, D3: NEW}, records)
        self.assertEqual(6, len(client.requested))
        self.assertEqual((NOW - timedelta(days=90), NOW), client.bounds)
        self.assertIn("90-day", self.stderr.getvalue())
        self.assertIn("::warning::", self.stderr.getvalue())
        self.assertIn("not proof", self.stderr.getvalue())

    def test_build_fallback_after_failed_merge_and_partial_run(self):
        for merge_conclusion in ("failure", "cancelled", "skipped"):
            with self.subTest(conclusion=merge_conclusion):
                client = FakeGitHub(
                    runs=[run(conclusion="failure")],
                    jobs={10: [merge_job(conclusion=merge_conclusion), build_job()]},
                    downloads={
                        "/actions/jobs/20/logs": log("##[error]merge failed"),
                        "/actions/jobs/21/logs": build_log(),
                    },
                )
                result = self.recover(client)
                self.assertEqual({D1: NEW}, result["repositories"][REPO])

    def test_failed_merge_can_recover_partially_pushed_index_too(self):
        client = FakeGitHub(
            runs=[run(conclusion="failure")],
            jobs={10: [merge_job(conclusion="failure"), build_job()]},
            downloads={
                "/actions/jobs/20/logs": log(f"#1 0.1 pushing {D3} to {IMAGE}:20260901"),
                "/actions/jobs/21/logs": build_log(),
            },
        )
        self.assertEqual({D1: NEW, D3: NEW}, self.recover(client)["repositories"][REPO])

    def test_successful_merge_does_not_hide_orphan_pushes_from_another_attempt(self):
        client = FakeGitHub(
            runs=[run(conclusion="failure")],
            jobs={10: [
                merge_job(run_attempt=1),
                build_job(run_attempt=2),
            ]},
            downloads={
                "/actions/jobs/20/logs": merge_log(),
                "/actions/jobs/21/logs": build_log().replace(D1.encode(), BASE.encode()),
            },
        )
        self.assertEqual(
            {D1: NEW, D2: NEW, D3: NEW, BASE: NEW},
            self.recover(client)["repositories"][REPO],
        )

    def test_expired_logs_are_reported_and_successful_builds_still_recovered(self):
        for status in (404, 410):
            client = FakeGitHub(
                runs=[run()], jobs={10: [merge_job(), build_job()]},
                downloads={
                    "/actions/jobs/20/logs": history.APIError(status),
                    "/actions/jobs/21/logs": build_log(),
                },
            )
            self.assertEqual({D1: NEW}, self.recover(client)["repositories"][REPO])
            self.assertIn(f"HTTP {status}", self.stderr.getvalue())
            self.assertIn("1 job logs unavailable", self.stderr.getvalue())

    def test_log_auth_rate_limit_and_server_errors_are_not_swallowed(self):
        for status in (401, 403, 429, 500):
            client = FakeGitHub(
                runs=[run()], jobs={10: [merge_job()]},
                downloads={"/actions/jobs/20/logs": history.APIError(status)},
            )
            with self.assertRaises(history.APIError):
                self.recover(client)

    def test_bootstrap_filters_run_identity_status_and_temporal_window(self):
        excluded = [
            run(11, head_branch="feature"),
            run(12, workflow_id=43),
            run(13, status="in_progress"),
            run(999),
            run(14, created_at=history.iso(NOW - timedelta(days=91))),
            run(15, created_at=history.iso(NOW + timedelta(hours=1))),
        ]
        client = FakeGitHub(
            runs=excluded + [run()], jobs={10: [merge_job()]},
            downloads={"/actions/jobs/20/logs": merge_log()},
        )
        self.assertEqual({D1: NEW, D2: NEW, D3: NEW}, self.recover(client)["repositories"][REPO])
        self.assertEqual(["/actions/jobs/20/logs"], client.requested)

    def test_uses_maximum_run_updated_timestamp_across_repushes(self):
        client = FakeGitHub(
            runs=[run(), run(11, updated_at=OLD)],
            jobs={10: [merge_job()], 11: [merge_job(21)]},
            downloads={
                "/actions/jobs/20/logs": merge_log(),
                "/actions/jobs/21/logs": merge_log(),
            },
        )
        self.assertEqual({D1: NEW, D2: NEW, D3: NEW}, self.recover(client)["repositories"][REPO])

    def test_wrong_job_identity_is_not_parsed(self):
        client = FakeGitHub(
            runs=[run()], jobs={10: [
                merge_job(name=f"Post Merge {REPO} manifests"),
                build_job(name=f"Build, smoke-test and publish {REPO}-evil (linux/amd64)"),
            ]},
        )
        self.assertEqual({}, self.recover(client)["repositories"][REPO])
        self.assertFalse(client.requested)

    def test_workflow_endpoint_identity_is_checked(self):
        client = Mock()
        client.json.return_value = {"id": 42, "path": ".github/workflows/other.yml"}
        with self.assertRaisesRegex(history.HistoryError, "identity"):
            self.recover(client)
        client.pages.assert_not_called()


class ParserTests(unittest.TestCase):
    def test_merge_accepts_masked_and_real_namespace_and_only_actual_manifest_records(self):
        content = merge_log() + log(
            f"Run actions/checkout@{BASE}",
            f"#1 FROM docker.io/library/ubuntu@{CONFIG}",
            f"#1 0.0 copying {BASE} from docker.io/other/{REPO}@{CONFIG} to {IMAGE}",
            f"#1 0.0 pushing {CONFIG} to {IMAGE}-evil:latest",
            f"#1 0.0 pushing {CONFIG} to docker.io/other/{REPO}:latest",
            f"#1 0.0 pushing {CONFIG} to ghcr.io/jdubois/{REPO}:latest",
        )
        self.assertEqual({D1, D2, D3}, history.merge_digests(content, "jdubois", REPO))
        self.assertEqual(
            {D1, D2, D3},
            history.merge_digests(content.replace(b"***", b"jdubois"), "jdubois", REPO),
        )

    def test_merge_recovers_attested_source_wrapper_and_flattened_children(self):
        wrapper = CONFIG
        image = D1
        attestation = D2
        content = log(
            f"#1 0.000 copying {image} from {IMAGE}@{wrapper} to {IMAGE}",
            f"#1 0.000 copying {attestation} from {IMAGE}@{wrapper} to {IMAGE}",
            f"#1 0.344 pushing {D3} to {IMAGE}:latest",
        )
        self.assertEqual(
            {wrapper, image, attestation, D3},
            history.merge_digests(content, "jdubois", REPO),
        )

    def test_build_requires_export_and_successful_push_within_exact_step(self):
        content = build_log() + log(
            f"#18 exporting manifest {D2} done",
            f"#18 pushing manifest for {IMAGE}-evil 0.5s done",
            f"#19 exporting manifest {D3} done",
            f"#19 pushing manifest for {IMAGE}",
        ) + log(
            f"#17 exporting manifest {BASE} done",
            f"#17 pushing manifest for {IMAGE} 0.5s done",
            at="2026-09-01T08:02:00Z",
        )
        self.assertEqual(
            {D1}, history.build_digests(content, "jdubois", REPO, build_job()["steps"])
        )
        wrong_step = copy.deepcopy(build_job()["steps"])
        wrong_step[0]["name"] = "Build the image for smoke testing"
        self.assertEqual(set(), history.build_digests(content, "jdubois", REPO, wrong_step))

    def test_push_step_end_timestamp_includes_fractional_log_records(self):
        content = build_log().replace(b"08:03:05.1234567", b"08:03:10.9999999")
        self.assertEqual(
            {D1}, history.build_digests(content, "jdubois", REPO, build_job()["steps"])
        )

    def test_failed_push_step_can_still_have_a_completed_push(self):
        steps = build_job()["steps"]
        steps[0]["conclusion"] = "failure"
        self.assertEqual(
            {D1}, history.build_digests(build_log(), "jdubois", REPO, steps)
        )

    def test_ansi_color_codes_are_removed(self):
        content = merge_log().replace(b"#1", b"\x1b[32m#1").replace(b"\n", b"\x1b[0m\n")
        self.assertEqual({D1, D2, D3}, history.merge_digests(content, "jdubois", REPO))


class GitHubTests(unittest.TestCase):
    def setUp(self):
        self.client = history.GitHub("jdubois/boot-ui", "test-secret")

    def test_pagination_uses_local_page_numbers_not_untrusted_links(self):
        self.client.json = Mock(side_effect=[
            {"total_count": 101, "artifacts": [{"id": number} for number in range(100)]},
            {"total_count": 101, "artifacts": [{"id": 100}]},
        ])
        result = list(self.client.pages(
            "/actions/artifacts", "artifacts", {"name": history.ARTIFACT}
        ))
        self.assertEqual(101, len(result))
        self.assertEqual(
            [
                f"/actions/artifacts?name={history.ARTIFACT}&per_page=100&page=1",
                f"/actions/artifacts?name={history.ARTIFACT}&per_page=100&page=2",
            ],
            [call.args[0] for call in self.client.json.call_args_list],
        )

    def test_repeated_or_truncated_pagination_fails_closed(self):
        for final in ([{"id": 0}], []):
            self.client.json = Mock(side_effect=[
                {"total_count": 201, "jobs": [{"id": number} for number in range(100)]},
                {"total_count": 201, "jobs": final},
            ])
            with self.assertRaises(history.HistoryError):
                list(self.client.pages("/actions/runs/10/jobs", "jobs"))

    def test_run_history_splits_queries_above_github_thousand_run_limit(self):
        self.client.json = Mock(side_effect=[
            {"total_count": 1001},
            {"total_count": 1, "workflow_runs": [run()]},
            {"total_count": 2, "workflow_runs": [run(), run(11)]},
        ])
        result = list(self.client.runs(NOW - timedelta(days=90), NOW))
        self.assertEqual([10, 11], [item["id"] for item in result])
        self.assertEqual(3, self.client.json.call_count)
        for call in self.client.json.call_args_list:
            params = parse_qs(urlsplit(call.args[0]).query)
            self.assertEqual(["main"], params["branch"])
            self.assertIn("..", params["created"][0])

    def test_workflow_runs_and_jobs_are_paginated(self):
        for key, path in (
            ("workflow_runs", "/actions/workflows/docker-publish.yml/runs"),
            ("jobs", "/actions/runs/10/jobs"),
        ):
            with self.subTest(key=key):
                self.client.json = Mock(side_effect=[
                    {"total_count": 101, key: [{"id": n} for n in range(100)]},
                    {"total_count": 101, key: [{"id": 100}]},
                ])
                self.assertEqual(101, len(list(self.client.pages(path, key))))
                self.assertEqual(2, self.client.json.call_count)

    def test_signed_storage_redirect_drops_authorization_and_keeps_timeout(self):
        signed = "https://productionresultssa7.blob.core.windows.net/logs/job?sig=signed-secret"
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b"job logs"
        self.client.opener.open = Mock(side_effect=[
            HTTPError(
                "https://api.github.com", 302, "redirect",
                {"Location": signed}, io.BytesIO(),
            ),
            response,
        ])
        self.assertEqual(b"job logs", self.client.download("/actions/jobs/20/logs"))
        calls = self.client.opener.open.call_args_list
        self.assertEqual("Bearer test-secret", calls[0].args[0].get_header("Authorization"))
        self.assertIsNone(calls[1].args[0].get_header("Authorization"))
        self.assertEqual(signed, calls[1].args[0].full_url)
        self.assertTrue(all(call.kwargs["timeout"] == 45 for call in calls))

    def test_redirect_to_unapproved_host_never_receives_request(self):
        for location in (
            "http://productionresultssa7.blob.core.windows.net/logs",
            "https://api.github.com.attacker.example/logs",
            "https://attacker.example/logs",
            "https://productionresultssa7.blob.core.windows.net@attacker.example/logs",
            "https://productionresultssa7.blob.core.windows.net:444/logs",
            "https://productionresultssa7.blob.core.windows.net:invalid/logs",
            "https://[malformed/logs",
        ):
            with self.subTest(location=location):
                self.client.opener.open = Mock(side_effect=HTTPError(
                    history.API, 302, "redirect", {"Location": location}, io.BytesIO()
                ))
                with self.assertRaisesRegex(history.HistoryError, "Unapproved"):
                    self.client.download("/actions/jobs/20/logs")
                self.assertEqual(1, self.client.opener.open.call_count)

    def test_api_json_requests_do_not_follow_redirects(self):
        self.client.opener.open = Mock(side_effect=HTTPError(
            history.API, 302, "redirect",
            {"Location": "https://productionresultssa7.blob.core.windows.net/logs"},
            io.BytesIO(),
        ))
        with self.assertRaises(history.APIError):
            self.client.json("/actions/workflows/docker-publish.yml")
        self.assertEqual(1, self.client.opener.open.call_count)

    def test_http_error_streams_close_on_failure_and_rejected_redirect(self):
        for status in (302, 403, 404):
            with self.subTest(status=status):
                stream = io.BytesIO(b"response")
                self.client.opener.open = Mock(side_effect=HTTPError(
                    history.API, status, "error",
                    {"Location": "https://unapproved.example/logs"}, stream,
                ))
                with self.assertRaises(history.HistoryError):
                    self.client.download("/actions/jobs/20/logs")
                self.assertTrue(stream.closed)

    def test_transport_errors_never_expose_tokens_signed_urls_or_response_bodies(self):
        for error in (
            URLError("test-secret signed-secret"),
            HTTPError(history.API, 403, "test-secret", {}, io.BytesIO(b"signed-secret")),
        ):
            self.client.opener.open = Mock(side_effect=error)
            with self.assertRaises(history.HistoryError) as caught:
                self.client.download("/actions/jobs/20/logs")
            self.assertNotIn("test-secret", str(caught.exception))
            self.assertNotIn("signed-secret", str(caught.exception))

    def test_auth_and_repository_inputs_are_required(self):
        with self.assertRaises(history.HistoryError):
            history.GitHub("jdubois/boot-ui", "")
        with self.assertRaises(history.HistoryError):
            history.GitHub("jdubois/boot-ui?token=bad", "token")
        with self.assertRaises(history.HistoryError):
            self.client.get("https://attacker.example")
        with self.assertRaises(history.HistoryError):
            history.GitHub("jdubois/boot-ui", "token\nmalformed")


class CommandTests(unittest.TestCase):
    def test_cli_writes_exact_inventory_schema(self):
        environment = {
            "GH_TOKEN": "test-secret", "GITHUB_REPOSITORY": "jdubois/boot-ui",
            "GITHUB_RUN_ID": "999",
        }
        with patch.dict(history.os.environ, environment, clear=True), \
                patch.object(history, "GitHub") as client, \
                patch.object(history, "recover", return_value=inventory()) as recover, \
                patch.object(history, "Path") as path:
            self.assertEqual(
                0, history.main(["--output", "inventory.json", "--namespace", "jdubois"])
            )
            client.assert_called_once_with("jdubois/boot-ui", "test-secret")
            recover.assert_called_once_with(client.return_value, "jdubois", "999")
            written = path.return_value.write_text.call_args.args[0]
            self.assertEqual(inventory(), json.loads(written))

    def test_failure_is_nonzero_and_never_overwrites_inventory(self):
        with patch.dict(history.os.environ, {}, clear=True), \
                patch.object(history, "Path") as path, \
                redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(
                1, history.main(["--output", "inventory.json", "--namespace", "jdubois"])
            )
            path.assert_not_called()
            self.assertIn("ERROR:", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
