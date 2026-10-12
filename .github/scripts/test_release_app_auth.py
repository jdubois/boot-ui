import base64
import http.server
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/release.yml"
APP_SLUG = "bootui-release-jdubois"
FAKE_TOKEN = "offline-test-token"


def step_block(name):
    content = WORKFLOW.read_text(encoding="utf-8")
    start = content.index(f"      - name: {name}\n")
    boundary = re.search(r"^      - name:|^  [A-Za-z0-9_-]+:", content[start + 1:], re.MULTILINE)
    end = start + 1 + boundary.start() if boundary else len(content)
    return content[start:end]


def step_script(name):
    lines = step_block(name).split("        run: |\n", 1)[1].splitlines()
    return "\n".join(line[10:] if line.startswith("          ") else line for line in lines) + "\n"


class ReleaseAppAuthorizationTests(unittest.TestCase):
    def run_script(self, script, directory, **env):
        clean_env = {
            key: value for key, value in os.environ.items()
            if not key.startswith(("GIT_", "GH_", "GITHUB_", "RELEASE_APP_", "TOKEN_APP_"))
        }
        return subprocess.run(
            ["bash", "-c", script],
            cwd=directory,
            env={**clean_env, **env},
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def test_credential_check_reuses_the_existing_dispatch_schema(self):
        inputs = WORKFLOW.read_text(encoding="utf-8").split("    inputs:\n", 1)[1].split("\nconcurrency:", 1)[0]
        self.assertEqual(
            re.findall(r"^      ([a-z_]+):$", inputs, re.MULTILINE),
            ["version", "skip_build", "auto_publish", "resume_after_publish"],
        )

    def test_all_inline_release_shell_blocks_parse(self):
        content = WORKFLOW.read_text(encoding="utf-8")
        for name in re.findall(r"^      - name: (.+)$", content, re.MULTILINE):
            if "        run: |\n" not in step_block(name):
                continue
            with self.subTest(step=name):
                result = subprocess.run(
                    ["bash", "-n"], input=step_script(name),
                    capture_output=True, text=True, timeout=20,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_actual_job_conditions_and_concurrency_for_all_entry_points(self):
        content = WORKFLOW.read_text(encoding="utf-8")
        conditions = re.findall(r"^    if: (.+)$", content, re.MULTILINE)
        self.assertEqual(len(conditions), 2)
        group = re.search(r"^  group: \$\{\{ github.workflow \}\}-\$\{\{ (.+) \}\}$", content, re.MULTILINE).group(1)
        cases = []
        for event, actor, version, check, publish, lock in (
            ("workflow_dispatch", "jdubois", "check-release-app", True, False, "release"),
            ("workflow_dispatch", "jdubois", "1.20.1", False, True, "release"),
            ("workflow_dispatch", "jdubois", "", False, True, "release"),
            ("workflow_dispatch", f"{APP_SLUG}[bot]", "", False, True, "release"),
            ("push", f"{APP_SLUG}[bot]", "", False, False, "app-tag-noop"),
            ("push", "jdubois", "", False, True, "release"),
            ("push", "other[bot]", "", False, True, "release"),
        ):
            for resume in (False, True):
                for auto_publish in (False, True):
                    cases.append({
                        "github": {"event_name": event, "actor": actor},
                        "inputs": {"version": version, "resume_after_publish": resume, "auto_publish": auto_publish},
                        "expected": [check, publish, lock],
                    })
        result = subprocess.run(
            ["node", "-e", """
              const vm = require("node:vm");
              const {expressions, cases} = JSON.parse(process.argv[1]);
              for (const test of cases) {
                const actual = expressions.map(expression => vm.runInNewContext(expression, test));
                if (JSON.stringify(actual) !== JSON.stringify(test.expected)) {
                  throw new Error(JSON.stringify({test, actual}));
                }
              }
            """, json.dumps({"expressions": [*conditions, group], "cases": cases})],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_reserved_version_fails_closed_on_the_older_workflow(self):
        prepare = step_script("Prepare release version")
        validation = prepare[:prepare.index('CURRENT_VERSION=')]
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_script(
                validation, directory,
                RELEASE_VERSION="check-release-app", GITHUB_REF_NAME="1.x", GITHUB_SHA="offline-source",
            )
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("expected a stable major.minor.patch", result.stdout)

    def test_credential_check_source_branch_allowlist(self):
        with tempfile.TemporaryDirectory() as directory:
            for ref_type, name, accepted in (
                ("branch", "main", True), ("branch", "1.x", True), ("branch", "12.x", True),
                ("branch", "v2", False), ("branch", "feature", False), ("branch", "01.x", False),
                ("branch", "0.x", False), ("tag", "v1.20.0", False), ("tag", "1.x", False),
            ):
                with self.subTest(ref_type=ref_type, name=name):
                    result = self.run_script(
                        step_script("Check App credential source branch"), directory,
                        GITHUB_REF_TYPE=ref_type, GITHUB_REF_NAME=name,
                    )
                    self.assertEqual(result.returncode, 0 if accepted else 1, result.stdout + result.stderr)

    def test_credential_check_reads_only_own_repository_and_fails_explicitly(self):
        with tempfile.TemporaryDirectory() as directory:
            gh = Path(directory) / "gh"
            gh.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$*" >> "$GH_CALLS"\n'
                '[ "$GH_FAIL" != true ] || exit 1\nprintf "%s\\n" "$TEST_REPOSITORY"\n',
                encoding="utf-8",
            )
            gh.chmod(0o700)
            for token, slug, repository, failed, accepted, called in (
                (FAKE_TOKEN, APP_SLUG, "jdubois/boot-ui", "false", True, True),
                ("", APP_SLUG, "jdubois/boot-ui", "false", False, False),
                (FAKE_TOKEN, "other", "jdubois/boot-ui", "false", False, False),
                (FAKE_TOKEN, APP_SLUG, "jdubois/other", "false", False, True),
                (FAKE_TOKEN, APP_SLUG, "jdubois/boot-ui", "true", False, True),
            ):
                with self.subTest(slug=slug, repository=repository, failed=failed, token_present=bool(token)):
                    calls = Path(directory) / "calls"
                    calls.unlink(missing_ok=True)
                    result = self.run_script(
                        step_script("Verify release App access"), directory,
                        PATH=directory + os.pathsep + os.environ["PATH"], GH_CALLS=str(calls),
                        GH_TOKEN=token, TOKEN_APP_SLUG=slug, RELEASE_APP_SLUG=APP_SLUG,
                        GITHUB_REPOSITORY="jdubois/boot-ui", TEST_REPOSITORY=repository, GH_FAIL=failed,
                    )
                    self.assertEqual(result.returncode, 0 if accepted else 1, result.stdout + result.stderr)
                    self.assertEqual(calls.exists(), called)
                    if called:
                        self.assertEqual(calls.read_text(), "api repos/jdubois/boot-ui --jq .full_name\n")
                    self.assertNotIn(FAKE_TOKEN, result.stdout + result.stderr)

    def test_sealing_rejects_missing_token_or_wrong_app_before_any_git_call(self):
        script = step_script("Seal release commit and tag")
        with tempfile.TemporaryDirectory() as directory:
            git = Path(directory) / "git"
            git.write_text('#!/bin/sh\ntouch "$GIT_CALLED"\nexit 99\n', encoding="utf-8")
            git.chmod(0o700)
            called = Path(directory) / "called"
            for token, slug in (("", APP_SLUG), (FAKE_TOKEN, "other")):
                with self.subTest(token_present=bool(token), slug=slug):
                    result = self.run_script(
                        script, directory, PATH=directory + os.pathsep + os.environ["PATH"],
                        GIT_CALLED=str(called), RELEASE_APP_TOKEN=token, TOKEN_APP_SLUG=slug,
                        RELEASE_APP_SLUG=APP_SLUG, REQUESTED_VERSION="1.20.1",
                        GITHUB_REF_NAME="1.x", GITHUB_SHA="offline-source",
                    )
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertFalse(called.exists())
                    self.assertNotIn(FAKE_TOKEN, result.stdout + result.stderr)

    def test_seal_handoff_commits_verified_contents_and_stops_on_source_advancement(self):
        script = step_script("Seal release commit and tag").split('SIGNING_KEY=', 1)[0]
        git = shutil.which("git")
        git_env = {
            **{key: value for key, value in os.environ.items() if not key.startswith(("GIT_", "GH_", "GITHUB_"))},
            "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_EXEC_PATH": subprocess.check_output([git, "--exec-path"], text=True).strip(),
        }
        for raced in (False, True):
            with self.subTest(source_advanced=raced), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source, remote = root / "source", root / "remote.git"

                def run(*args, cwd=source):
                    result = subprocess.run(
                        [git, *args], cwd=cwd, env=git_env,
                        capture_output=True, text=True, timeout=20,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
                    return result.stdout.strip()

                run("init", "--bare", str(remote), cwd=root)
                run("init", "-b", "1.x", str(source), cwd=root)
                run("config", "user.name", "Offline test")
                run("config", "user.email", "offline@example.invalid")
                run("config", "commit.gpgsign", "false")
                verified = source / "verified.txt"
                verified.write_text("source\n", encoding="utf-8")
                run("add", "verified.txt")
                run("commit", "-m", "source")
                source_sha = run("rev-parse", "HEAD")
                run("remote", "add", "origin", str(remote))
                run("push", "origin", "HEAD:refs/heads/1.x")
                if raced:
                    run("commit", "--allow-empty", "-m", "racing commit")
                    remote_sha = run("rev-parse", "HEAD")
                    run("push", "origin", "HEAD:refs/heads/1.x")
                    run("switch", "--detach", source_sha)
                else:
                    remote_sha = source_sha
                verified.write_text("verified release contents\n", encoding="utf-8")
                result = self.run_script(
                    script, directory=str(source), **git_env,
                    REQUESTED_VERSION="1.20.1", GITHUB_REF_NAME="1.x", GITHUB_SHA=source_sha,
                    RELEASE_APP_TOKEN=FAKE_TOKEN, TOKEN_APP_SLUG=APP_SLUG, RELEASE_APP_SLUG=APP_SLUG,
                )
                self.assertEqual(result.returncode, 1 if raced else 0, result.stdout + result.stderr)
                self.assertEqual(run("log", "-1", "--format=%s"), "Release v1.20.1")
                self.assertEqual(run("show", "HEAD:verified.txt"), "verified release contents")
                self.assertEqual(run("rev-parse", "refs/heads/1.x", cwd=remote), remote_sha)
                self.assertEqual(run("tag", "--list"), "")
                self.assertEqual(run("tag", "--list", cwd=remote), "")
                if raced:
                    self.assertIn("advanced from", result.stdout)
                self.assertNotIn(FAKE_TOKEN, result.stdout + result.stderr)


class ReleaseAppTransportTests(unittest.TestCase):
    def test_real_atomic_push_overrides_checkout_header_without_persisting_app_auth(self):
        for raced in (False, True):
            with self.subTest(source_advanced=raced), tempfile.TemporaryDirectory() as directory:
                self.exercise_transport(Path(directory), raced)

    def exercise_transport(self, directory, raced):
        git = shutil.which("git")
        env = {key: value for key, value in os.environ.items() if not key.startswith(("GIT_", "GH_", "GITHUB_"))}
        env.update(
            GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0",
            GIT_EXEC_PATH=subprocess.check_output([git, "--exec-path"], text=True).strip(),
        )
        source, remote = directory / "source", directory / "remote.git"

        def run(*args, cwd=source):
            result = subprocess.run([git, *args], cwd=cwd, env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip()

        run("init", "--bare", str(remote), cwd=directory)
        run("config", "http.receivepack", "true", cwd=remote)
        run("config", "receive.denyNonFastForwards", "true", cwd=remote)
        run("init", "-b", "1.x", str(source), cwd=directory)
        run("config", "user.name", "Offline test")
        run("config", "user.email", "offline@example.invalid")
        run("config", "commit.gpgsign", "false")
        run("commit", "--allow-empty", "-m", "source")
        source_sha = run("rev-parse", "HEAD")
        run("push", str(remote), "HEAD:refs/heads/1.x")
        if raced:
            run("commit", "--allow-empty", "-m", "racing commit")
            remote_sha = run("rev-parse", "HEAD")
            run("push", str(remote), "HEAD:refs/heads/1.x")
            run("switch", "--detach", source_sha)
        else:
            remote_sha = source_sha
        run("commit", "--allow-empty", "-m", "release")
        release_sha = run("rev-parse", "HEAD")
        run("tag", "-a", "v1.20.1", "-m", "offline transport tag")
        headers_seen = []
        expected_auth = "AUTHORIZATION: basic " + base64.b64encode(f"x-access-token:{FAKE_TOKEN}".encode()).decode()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.handle_git()

            def do_POST(self):
                self.handle_git()

            def log_message(self, *_args):
                pass

            def handle_git(self):
                headers_seen.append(self.headers.get_all("Authorization", []))
                if headers_seen[-1] != [expected_auth.split(": ", 1)[1]]:
                    self.send_response(401)
                    self.end_headers()
                    return
                path, _, query = self.path.partition("?")
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                result = subprocess.run(
                    [git, "http-backend"], input=body, capture_output=True, timeout=20,
                    env={**env, "GIT_PROJECT_ROOT": str(directory), "GIT_HTTP_EXPORT_ALL": "1",
                         "REQUEST_METHOD": self.command, "PATH_INFO": path, "QUERY_STRING": query,
                         "CONTENT_TYPE": self.headers.get("Content-Type", ""), "CONTENT_LENGTH": str(len(body)),
                         "REMOTE_USER": APP_SLUG},
                )
                response_headers, _, payload = result.stdout.partition(b"\r\n\r\n")
                parsed = [line.decode().split(": ", 1) for line in response_headers.split(b"\r\n") if line]
                status = next((int(value.split()[0]) for key, value in parsed if key.lower() == "status"), 200)
                self.send_response(status)
                for key, value in parsed:
                    if key.lower() != "status":
                        self.send_header(key, value)
                self.end_headers()
                self.wfile.write(payload)

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}/"
            run("remote", "add", "origin", base + "remote.git")
            key = f"http.{base}.extraheader"
            checkout_config = directory / "checkout-credentials.config"
            run("config", "--file", str(checkout_config), key, "AUTHORIZATION: basic OLD-CHECKOUT-CREDENTIAL")
            run("config", f"includeIf.gitdir:{source / '.git'}.path", str(checkout_config))
            before = (source / ".git/config").read_bytes(), checkout_config.read_bytes()
            script = step_script("Seal release commit and tag")
            script = script[script.index('APP_GIT_AUTH='):script.index('echo "Sealed release')]
            script = "set -euo pipefail\n" + script.replace("https://github.com/", base)
            trace = directory / "git-trace.jsonl"
            result = subprocess.run(
                ["bash", "-c", script], cwd=source, capture_output=True, text=True, timeout=20,
                env={**env, "RELEASE_APP_TOKEN": FAKE_TOKEN, "SOURCE_BRANCH": "1.x",
                     "SOURCE_SHA": source_sha, "TAG": "v1.20.1", "GIT_TRACE2_EVENT": str(trace)},
            )
            self.assertEqual(result.returncode, 1 if raced else 0, result.stdout + result.stderr)
            self.assertGreaterEqual(len(headers_seen), 2)
            self.assertTrue(all(values == [expected_auth.split(": ", 1)[1]] for values in headers_seen))
            self.assertEqual(before, ((source / ".git/config").read_bytes(), checkout_config.read_bytes()))
            self.assertNotIn(FAKE_TOKEN, result.stdout + result.stderr)
            self.assertEqual(result.stdout.count("::add-mask::"), 1)
            invocations = [event["argv"] for line in trace.read_text().splitlines()
                           if (event := json.loads(line))["event"] == "start"]
            self.assertTrue(invocations)
            arguments = json.dumps(invocations)
            for credential in (FAKE_TOKEN, expected_auth.split("basic ", 1)[1], "OLD-CHECKOUT-CREDENTIAL"):
                self.assertNotIn(credential, arguments)
            self.assertEqual(run("rev-parse", "refs/heads/1.x", cwd=remote), remote_sha if raced else release_sha)
            tags = run("tag", "--list", cwd=remote)
            self.assertEqual(tags, "" if raced else "v1.20.1")
            if raced:
                self.assertIn("Atomic release push failed", result.stdout)
            else:
                self.assertEqual(run("rev-parse", "refs/tags/v1.20.1^{}", cwd=remote), release_sha)
        finally:
            server.shutdown()
            thread.join(timeout=20)
            server.server_close()
            self.assertFalse(thread.is_alive())


if __name__ == "__main__":
    unittest.main()
