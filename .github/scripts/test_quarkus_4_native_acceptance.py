"""Offline regression tests. No Maven, native compiler, subprocess or HTTP server is run."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import sys
import unittest
from unittest.mock import Mock, patch
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("quarkus4_native_acceptance", Path(__file__).with_name(
    "quarkus-4-native-acceptance.py"))
HARNESS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = HARNESS
SPEC.loader.exec_module(HARNESS)


class NativeAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.workspace = ROOT / ".ci-script-tests" / ("native-acceptance-" + uuid.uuid4().hex)
        self.workspace.mkdir(parents=True)

    def tearDown(self):
        shutil.rmtree(self.workspace)

    def linux(self, arm=False):
        return {
            "system": "Linux", "machine": "aarch64" if arm else "x86_64", "bits": "64", "processBits": 64,
            "emulator": False,
            "cpuinfo": "CPU architecture : 8\nCPU implementer : 0x41\n" if arm else "vendor_id : GenuineIntel\n",
            "lscpu": {"Architecture": "aarch64" if arm else "x86_64", "Hypervisor vendor": "KVM"},
        }

    def mac(self):
        return {"system": "Darwin", "machine": "arm64", "bits": "64", "processBits": 64, "emulator": False,
                "hw_optional_arm64": "1", "hw_machine": "arm64", "proc_translated": "0",
                "cpu_brand": "Apple M4"}

    def test_three_real_native_host_architectures(self):
        for target, evidence in (("linux-x86_64", self.linux()), ("linux-arm64", self.linux(True)),
                                 ("macos-arm64", self.mac())):
            with self.subTest(target=target):
                HARNESS.verify_host(target, evidence)

    def test_uname_is_insufficient_for_an_emulated_x86_container_on_arm(self):
        evidence = self.linux()
        evidence["cpuinfo"] = self.linux(True)["cpuinfo"]
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "x86 CPU evidence"):
            HARNESS.verify_host("linux-x86_64", evidence)

    def test_unknown_mismatched_translated_and_emulated_cpu_evidence_fail_closed(self):
        scenarios = [
            ("linux-x86_64", {**self.linux(), "cpuinfo": ""}),
            ("linux-arm64", self.linux()),
            ("linux-x86_64", {**self.linux(), "emulator": True}),
            ("linux-x86_64", {**self.linux(), "lscpu": {"Architecture": "aarch64"}}),
            ("linux-x86_64", {**self.linux(), "lscpu": {"Architecture": "x86_64", "Hypervisor vendor": "QEMU"}}),
            ("linux-arm64", {**self.linux(True), "cpuinfo": "CPU architecture : 8\n"}),
            ("macos-arm64", {**self.mac(), "proc_translated": "1"}),
            ("macos-arm64", {**self.mac(), "proc_translated": ""}),
            ("macos-arm64", {**self.mac(), "hw_optional_arm64": "0"}),
            ("macos-arm64", {**self.mac(), "machine": "x86_64"}),
        ]
        for target, evidence in scenarios:
            with self.subTest(evidence=evidence):
                with self.assertRaises(HARNESS.AcceptanceFailure):
                    HARNESS.verify_host(target, evidence)

    def write_binary(self, name, target):
        data = bytearray(64)
        if target.startswith("linux"):
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", data, 18, 183 if target.endswith("arm64") else 62)
        else:
            data[:4] = b"\xcf\xfa\xed\xfe"
            struct.pack_into("<I", data, 4, 0x0100000C)
        path = self.workspace / name
        path.write_bytes(data)
        path.chmod(0o700)
        return path

    def test_native_binary_identity_is_verified_and_hashed(self):
        for target in HARNESS.TARGETS:
            with self.subTest(target=target):
                path = self.write_binary(target, target)
                result = HARNESS.verify_binary(path, target)
                self.assertEqual(HARNESS.sha256(path), result["sha256"])
                self.assertEqual(target, result["target"])
        path = self.write_binary("wrong-target", "linux-arm64")
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "architecture mismatches"):
            HARNESS.verify_binary(path, "linux-x86_64")
        path.write_bytes(b"not a native executable")
        with self.assertRaises(HARNESS.AcceptanceFailure):
            HARNESS.verify_binary(path, "linux-arm64")

    def test_missing_and_non_executable_native_outputs_are_not_acceptance(self):
        path = self.write_binary("not-executable", "linux-arm64")
        path.chmod(0o600)
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "not executable"):
            HARNESS.verify_binary(path, "linux-arm64")
        commands = Mock()
        commands.journal.output = self.workspace
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "exactly one binary"):
            HARNESS.compile_fixture(commands, self.workspace / "m2", "linux-arm64", "included", 60)

    def test_builds_have_distinct_directories_real_compilation_and_one_absolute_repo(self):
        journal = HARNESS.Journal(self.workspace, "linux-arm64", self.workspace / "m2")
        commands = Mock(journal=journal)

        def compile_result(_name, command, timeout):
            output = next(argument.split("=", 1)[1] for argument in command
                          if argument.startswith("-Dnative.acceptance.build-directory="))
            build = Path(output)
            build.mkdir()
            artifact = self.write_binary("compiled-" + build.name, "linux-arm64")
            shutil.move(artifact, build / "app-runner")

        commands.run.side_effect = compile_result
        outputs = [HARNESS.compile_fixture(commands, self.workspace / "m2", "linux-arm64", variant, 60)
                   for variant in ("included", "excluded")]
        self.assertNotEqual(outputs[0].parent, outputs[1].parent)
        for call in commands.run.call_args_list:
            argv = call.args[1]
            self.assertEqual(str(ROOT / "mvnw"), argv[0])
            self.assertIn(f"-Dmaven.repo.local={self.workspace / 'm2'}", argv)
            self.assertIn("-Dquarkus.native.enabled=true", argv)
            self.assertIn("-Dquarkus.native.container-build=false", argv)
            self.assertIn("-Dquarkus.native.sources-only=false", argv)
            self.assertNotIn("-Dquarkus.native.ignore-version-check=true", argv)
            self.assertEqual("package", argv[-1])
        self.assertIn("-Dbootui.native-console.enabled=true", commands.run.call_args_list[0].args[1])
        self.assertIn("-Dbootui.native-console.enabled=false", commands.run.call_args_list[1].args[1])

    def test_cli_contract_requires_absolute_repository_without_emulation_bypass(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                HARNESS.parse_arguments(["--target", "linux-arm64", "--maven-repo", "relative",
                                         "--output", str(self.workspace)])
            with self.assertRaises(SystemExit):
                HARNESS.parse_arguments(["--target", "linux-arm64", "--maven-repo", str(self.workspace / "m2"),
                                         "--output", str(self.workspace), "--allow-emulation"])
        argv = HARNESS.maven_command(self.workspace / "m2", "-N", "install")
        self.assertIn(f"-Dmaven.repo.local={self.workspace / 'm2'}", argv)

    def test_environment_does_not_inherit_activation_agents_or_outbound_exporters(self):
        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "-javaagent:unrelated.jar", "BOOTUI_ENABLED": "ON",
                                     "QUARKUS_PROFILE": "local", "MAVEN_OPTS": "-Dbootui.enabled=ON",
                                     "NPM_CONFIG_CACHE": "/unrelated/cache", "NPM_CONFIG_USERCONFIG": "/unrelated/npmrc",
                                     "PRIVATE_SECRET": "do-not-inherit"}, clear=True):
            environment = HARNESS.safe_environment(self.workspace / "home", self.workspace / "jdk")
        for name in ("BOOTUI_ENABLED", "QUARKUS_PROFILE", "MAVEN_OPTS", "PRIVATE_SECRET",
                     "NPM_CONFIG_CACHE", "NPM_CONFIG_USERCONFIG"):
            self.assertNotIn(name, environment)
        self.assertEqual("-XX:ActiveProcessorCount=2", environment["JAVA_TOOL_OPTIONS"])
        for signal_name in ("TRACES", "METRICS", "LOGS"):
            self.assertEqual("none", environment["OTEL_" + signal_name + "_EXPORTER"])
        self.assertEqual("1", environment["BOOTUI_NO_UPDATE_CHECK"])
        self.assertTrue(Path(environment["TMPDIR"]).is_relative_to(self.workspace))

    def test_optional_orm_profile_is_built_without_weakening_native_compilation(self):
        commands = Mock(journal=HARNESS.Journal(self.workspace, "linux-arm64", self.workspace / "m2"))
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "exactly one binary"):
            HARNESS.compile_fixture(commands, self.workspace / "m2", "linux-arm64", "included", 60, "hibernate")
        command = commands.run.call_args.args[1]
        self.assertIn("-Pnative-orm", command)
        self.assertIn("-Dquarkus.native.enabled=true", command)
        self.assertIn("-Dbootui.native-console.enabled=true", command)
        self.assertEqual("package", command[-1])
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "Unknown native acceptance fixture"):
            HARNESS.compile_fixture(commands, self.workspace / "m2", "linux-arm64", "included", 60, "unknown")

    def test_host_startup_name_is_not_a_console_banner_but_actual_banner_and_token_are_rejected(self):
        commands = Mock(journal=HARNESS.Journal(self.workspace, "macos-arm64", self.workspace / "m2"))
        folder = self.workspace / "commands"
        folder.mkdir()
        case = HARNESS.Case("denied-host")
        output = folder / (case.name + ".out")
        (folder / (case.name + ".err")).write_text("", encoding="utf-8")
        leases = self.workspace / "leases"
        leases.mkdir()
        output.write_text("bootui-native-acceptance 2.5.0 on JVM started. Listening on: http://127.0.0.1:8080\n",
                          encoding="utf-8")
        HARNESS.assert_no_startup_console(commands, case, leases)
        for text in ("BootUI 2.5.0 is available at http://localhost:8080/console",
                     "[BootUI] bearer token: hidden", HARNESS.TOKEN):
            with self.subTest(text=text):
                output.write_text(text, encoding="utf-8")
                with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "startup banner/token"):
                    HARNESS.assert_no_startup_console(commands, case, leases)

    def test_orm_host_witnesses_require_both_actual_units_and_the_preserved_listener(self):
        values = {
            "/witness": {"name": "native-acceptance",
                         "detail": {"kind": "immutable-record", "items": [{"id": 7, "label": "nested"}]}},
            "/jdbc": {"value": 42, "autoCommit": True},
            "/orm": {"unit": "default", "entities": 0},
            "/orm/users": {"unit": "users", "entities": 0, "hostListenerInvoked": True},
            "/health": {"status": "UP"},
        }
        client = Mock()
        client.json.side_effect = lambda path: values[path.removeprefix(HARNESS.HOST_PATH)]
        client.probe.return_value = {"hostSpans": 1}
        HARNESS.host_traffic(client, "hibernate")
        for path in ("/orm", "/orm/users"):
            client.json.assert_any_call(HARNESS.HOST_PATH + path)
        client.request.assert_any_call(HARNESS.HOST_PATH + "/validation", expected=500)
        values["/orm/users"]["hostListenerInvoked"] = False
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "BootUI replaced its host listener"):
            HARNESS.host_traffic(client, "hibernate")

    def test_orm_capture_is_attributed_without_exposing_unavailable_panel_evidence(self):
        client = Mock(folder=self.workspace)
        exchanges = {"exchanges": [
            {"path": HARNESS.HOST_PATH + "/orm", "status": 200, "requestId": "default-request"},
            {"path": HARNESS.HOST_PATH + "/orm/users", "status": 200, "requestId": "users-request"},
        ]}
        sql = {"entries": [
            {"sql": "select count(*) from native_orm_witness", "success": True, "requestId": request}
            for request in ("default-request", "users-request")
        ]}
        profiles = {
            "default-request": {"available": True, "orm": None},
            "users-request": {"available": True, "orm": None},
        }
        source = {"count": 3, "recording": True, "hostListenerInvoked": True, "namedRecording": False}
        feed = {"entries": []}

        def response(path):
            if path == "/api/panels":
                return {"panels": [{"id": "hibernate", "available": False, "enabled": True,
                                    "unavailableReason": "Not supported in native"}]}
            if path == "/api/activity/journal":
                return {"recorded": {"orm": source["count"]}}
            if path == "/api/activity?source=journal":
                return feed
            if path == HARNESS.HOST_PATH + "/orm":
                source["count"] += int(source["recording"])
                return {"unit": "default", "entities": 0}
            if path == HARNESS.HOST_PATH + "/orm/users":
                source["count"] += int(source["namedRecording"])
                return {"unit": "users", "entities": 0, "hostListenerInvoked": source["hostListenerInvoked"]}
            return profiles[path.split("/")[-2]]

        client.json.side_effect = response
        with patch.object(HARNESS, "wait_until",
                          side_effect=lambda name, predicate, **kwargs: HARNESS.require(predicate(), name)):
            HARNESS.orm_contract(client, "/api", exchanges, sql)
            evidence = json.loads((self.workspace / "orm-result.json").read_text())
            self.assertTrue(evidence["defaultSessionRecorded"])
            self.assertFalse(evidence["sessionProfileAvailable"])
            self.assertIn("named-unit SQL capture is not supported", evidence["sqlCapture"])
            sql["entries"][0]["requestId"] = "unrelated-request"
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "ORM-generated SQL did not correlate"):
                HARNESS.orm_contract(client, "/api", exchanges, sql)
            sql["entries"][0]["requestId"] = "default-request"
            sql["entries"].pop()
            HARNESS.orm_contract(client, "/api", exchanges, sql)
            profiles["default-request"]["orm"] = {"sessions": 1, "statements": 1}
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "unavailable or disabled Hibernate panel"):
                HARNESS.orm_contract(client, "/api", exchanges, sql)
            profiles["default-request"]["orm"] = None
            feed["entries"] = [{"type": "ORM"}]
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "exposed ORM feed evidence"):
                HARNESS.orm_contract(client, "/api", exchanges, sql)
            feed["entries"] = []
            source["recording"] = False
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "recorded a native session"):
                HARNESS.orm_contract(client, "/api", exchanges, sql)
            source["recording"] = True
            source["namedRecording"] = True
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "instead of preserving the named host listener"):
                HARNESS.orm_contract(client, "/api", exchanges, sql)
            source["namedRecording"] = False
            source["hostListenerInvoked"] = False
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "explicitly configured host listener"):
                HARNESS.orm_contract(client, "/api", exchanges, sql)

    def test_optional_validation_requires_actual_metadata_without_rejected_values(self):
        client = Mock()
        diagnostics = [{"targetType": "NativeValidationWitness", "fieldPath": "value", "reason": "Pattern"}]
        client.json.return_value = {"occurrences": [{"bindingDiagnostics": diagnostics}]}
        exceptions = {"groups": [{"id": "validation", "message": "native-validation-witness"}]}
        HARNESS.validation_contract(client, "/api", exceptions)
        client.json.assert_called_once_with("/api/exceptions/validation")
        diagnostics.clear()
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "metadata-only binding diagnostics"):
            HARNESS.validation_contract(client, "/api", exceptions)
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "failure was not captured"):
            HARNESS.validation_contract(client, "/api", {"groups": []})

    def test_osv_fixture_matches_the_actual_package_and_preserves_bounded_failure_evidence(self):
        queries = {"queries": [{"package": {"name": "org.example:native-witness", "ecosystem": "Maven"},
                                "version": "1.0"}]}
        with HARNESS.LoopbackOsvFixture(self.workspace) as fixture:
            self.assertEqual([], fixture.requests)
            connection = HARNESS.http.client.HTTPConnection("127.0.0.1", fixture.server.server_port, timeout=5)
            connection.request("POST", "/v1/querybatch", json.dumps(queries),
                               {"Content-Type": "application/json"})
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            self.assertEqual("NATIVE-OSV-WITNESS", json.loads(response.read())["results"][0]["vulns"][0]["id"])
            connection.close()
            connection = HARNESS.http.client.HTTPConnection("127.0.0.1", fixture.server.server_port, timeout=5)
            connection.request("GET", "/v1/vulns/NATIVE-OSV-WITNESS")
            response = connection.getresponse()
            advisory = json.loads(response.read())
            self.assertEqual(queries["queries"][0]["package"], advisory["affected"][0]["package"])
            self.assertEqual(["1.0"], advisory["affected"][0]["versions"])
            connection.close()
            fixture.fail_batch = True
            connection = HARNESS.http.client.HTTPConnection("127.0.0.1", fixture.server.server_port, timeout=5)
            connection.request("POST", "/v1/querybatch", json.dumps(queries))
            response = connection.getresponse()
            self.assertEqual(503, response.status)
            response.read()
            connection.close()
        self.assertFalse(fixture.thread.is_alive())
        self.assertEqual(3, len(json.loads((self.workspace / "osv-requests.json").read_text())))

    def test_held_osv_request_observes_actual_peer_cancellation_without_timing_out(self):
        with HARNESS.LoopbackOsvFixture(self.workspace) as fixture:
            fixture.hold_batch = True
            connection = HARNESS.http.client.HTTPConnection("127.0.0.1", fixture.server.server_port, timeout=5)
            connection.request("POST", "/v1/querybatch", json.dumps({"queries": []}))
            HARNESS.wait_until("owned OSV hold", lambda: fixture.requests and fixture.requests[0].get("held"), 5)
            connection.sock.shutdown(HARNESS.socket.SHUT_RDWR)
            connection.close()
            HARNESS.wait_until("actual OSV peer cancellation",
                               lambda: json.loads((self.workspace / "state.json").read_text())["batches"][0]
                                       ["disconnected"], 2)
            observed = json.loads((self.workspace / "state.json").read_text())["batches"][0]
            self.assertTrue(observed["disconnected"])
            self.assertFalse(observed["holdTimedOut"])
            self.assertFalse(observed["responded"])
            self.assertFalse(observed["released"])
        self.assertFalse(fixture.thread.is_alive())

    def test_explicit_dependency_cache_is_offline_without_inheriting_npm_configuration(self):
        cache = self.workspace / "npm-cache"
        cache.mkdir()
        arguments = ["--target", "macos-arm64", "--maven-repo", str(self.workspace / "m2"),
                     "--output", str(self.workspace), "--npm-cache"]
        args = HARNESS.parse_arguments([*arguments, str(cache)])
        environment = HARNESS.safe_environment(self.workspace / "home", self.workspace / "jdk", args.npm_cache)
        self.assertEqual(str(cache), environment["NPM_CONFIG_CACHE"])
        self.assertEqual("true", environment["NPM_CONFIG_OFFLINE"])
        self.assertNotIn("NPM_CONFIG_USERCONFIG", environment)
        for invalid in ("relative", str(self.workspace / "missing")):
            with self.subTest(invalid=invalid), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    HARNESS.parse_arguments([*arguments, invalid])

    def test_activation_matrix_covers_file_expression_parents_and_both_profile_orders(self):
        cases = HARNESS.activation_cases()
        names = {case.name for case in cases}
        self.assertEqual(len(cases), len(names))
        self.assertIn("no-live-flags-after-local-on-build", names)
        self.assertIn("system-on-local", names)
        self.assertIn("environment-on-local", names)
        self.assertIn("multi-level-production-parent", names)
        self.assertIn("multi-level-file-production-parent", names)
        self.assertIn("inherited-local-only", names)
        self.assertIn("file-only-on-local", names)
        self.assertIn("expression-only-mode", names)
        self.assertIn("expression-only-profile", names)
        denied_profiles = {dict(case.properties).get("quarkus.profile") for case in cases if not case.active}
        self.assertTrue({"prod", "production", "local,prod", "prod,local", "local,production",
                         "production,local", ""} <= denied_profiles)
        for mode in ("OFF", "AUTO", "", "invalid", "ON,OFF"):
            self.assertTrue(any(not case.active and dict(case.properties).get("bootui.enabled") == mode
                                for case in cases))
        self.assertTrue(all(not case.active for case in cases if case.file_properties))

    def dormant(self):
        return {"protocol": HARNESS.PROTOCOL, "ownedThreads": [], "installedHandlers": [],
                "counters": {name: {"state": "uninstantiated", "value": None}
                             for name in ("http", "logs", "sql", "exceptions", "telemetry", "journal")},
                "logSubscriptions": {"state": "uninstantiated", "value": None}}

    def test_dormancy_never_manufactures_an_unobserved_zero(self):
        HARNESS.assert_dormant(self.dormant())
        for name in ("http", "logs", "sql", "exceptions", "telemetry", "journal"):
            sample = self.dormant()
            sample["counters"][name] = {"state": "uninstantiated", "value": 0}
            with self.subTest(name=name):
                with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "must not be reported as zero"):
                    HARNESS.assert_dormant(sample)
        sample = self.dormant()
        sample["counters"]["sql"] = {"state": "instantiated", "value": 1}
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "captured sql"):
            HARNESS.assert_dormant(sample)
        sample["counters"]["sql"]["value"] = 0
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "Cannot prove dormant subscriptions"):
            HARNESS.assert_dormant(sample)

    def test_handlers_threads_and_live_subscriptions_are_failures(self):
        for key, value in (("installedHandlers", ["BootUiHandler"]), ("ownedThreads", ["bootui-worker"]),
                           ("logSubscriptions", {"state": "instantiated", "value": 1})):
            sample = self.dormant()
            sample[key] = value
            with self.subTest(key=key):
                with self.assertRaises(HARNESS.AcceptanceFailure):
                    HARNESS.assert_dormant(sample)

    def test_shutdown_must_record_a_witness_and_preserve_denied_store_dormancy(self):
        path = self.workspace / "shutdown-probe.json"
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "did not record"):
                HARNESS.shutdown_contract(path, False)
        snapshot = self.dormant()
        path.write_text(json.dumps(snapshot))
        HARNESS.shutdown_contract(path, False)
        snapshot["counters"]["http"] = {"state": "instantiated", "value": 0}
        path.write_text(json.dumps(snapshot))
        with self.assertRaises(HARNESS.AcceptanceFailure):
                HARNESS.shutdown_contract(path, False)
        HARNESS.shutdown_contract(path, True)

    def test_denials_cover_real_custom_fixed_assets_and_mcp_cli_streams(self):
        client = Mock()
        case = HARNESS.Case("denial")
        HARNESS.deny_surfaces(client, case, ["assets/actual.js", "assets/actual.css"])
        paths = {call.args[0] for call in client.request.call_args_list}
        required = {"/bootui/assets/actual.js", "/acceptance/bootui/assets/actual.js",
                    "/acceptance/console/assets/actual.js", "/acceptance/diagnostics/mcp",
                    "/acceptance/diagnostics/cli/tools/get_overview", "/acceptance/diagnostics/log-tail/stream",
                    "/acceptance/diagnostics/sql-trace/stream", "/acceptance/diagnostics/activity/stream",
                    "/acceptance/diagnostics/http-probe", "/bootui/api/http-probe",
                    "/acceptance/bootui/api/http-probe"}
        self.assertTrue(required <= paths)
        self.assertTrue(all(call.kwargs["expected"] == 404 for call in client.request.call_args_list))
        for call in client.request.call_args_list:
            if call.args[0].endswith("/http-probe"):
                self.assertEqual("POST", call.args[1])
                self.assertEqual(HARNESS.HOST_PATH + "/probe", call.args[2]["path"])

    def test_unknown_available_panels_do_not_silently_skip_a_positive_contract(self):
        manifest = {"platform": "quarkus", "panels": [
            {"id": name, "available": True, "enabled": True, "unavailableReason": None}
            for name in HARNESS.REQUIRED_PANELS]}
        HARNESS.check_manifest(manifest)
        manifest["panels"].append({"id": "new-unimplemented-native-feature", "available": True})
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "No positive native contract"):
            HARNESS.check_manifest(manifest)
        manifest["panels"][-1] = {"id": "threads", "available": False, "unavailableReason": ""}
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "honest unavailable reason"):
            HARNESS.check_manifest(manifest)

    def test_mcp_payload_errors_never_become_success(self):
        for reply in ({"error": {"message": "failure"}}, {"result": {"isError": True}},
                      {"result": {"content": []}}):
            with self.assertRaises(HARNESS.AcceptanceFailure):
                HARNESS.tool_payload(reply)
        payload = {"nonempty": [{"nested": True}]}
        self.assertEqual(payload, HARNESS.tool_payload({"result": {"structuredContent": payload}}))

    def test_modern_mcp_requests_use_the_real_meta_and_header_protocol(self):
        client = HARNESS.Client(12345, self.workspace)
        with patch.object(client, "json", return_value={"result": {}}) as request:
            client.rpc("/diagnostics", "tools/call", {"name": "get_overview", "arguments": {}})
        path, method, body, headers = request.call_args.args
        self.assertEqual("/acceptance/diagnostics/mcp", path)
        self.assertEqual("POST", method)
        self.assertEqual(HARNESS.MCP_VERSION, body["params"]["_meta"]["io.modelcontextprotocol/protocolVersion"])
        self.assertEqual("tools/call", headers["Mcp-Method"])
        self.assertEqual("get_overview", headers["Mcp-Name"])
        self.assertEqual("application/json", headers["Accept"])

    def test_http2_status_alone_cannot_replace_physical_connection_and_cancellation_evidence(self):
        result = {"schema": "native-http2-mcp-witness-v1", "status": "passed",
                  "physicalConnection": {"protocol": "HTTP_2", "explicitConnectionsOpened": 1,
                                         "clearTextUpgrade": False},
                  "cancellation": {"elapsedMillis": 1500}, "samePhysicalConnection": True,
                  "singleFlightPreserved": True, "goAwayBeforeOwnedClose": False,
                  "connectionClosedBeforeOwnedClose": False}
        HARNESS.check_http2_result(result)
        for field, invalid in (("samePhysicalConnection", False), ("singleFlightPreserved", False),
                               ("goAwayBeforeOwnedClose", True), ("connectionClosedBeforeOwnedClose", True)):
            with self.subTest(field=field), self.assertRaises(HARNESS.AcceptanceFailure):
                HARNESS.check_http2_result({**result, field: invalid})
        for elapsed in (1501, None, True):
            with self.subTest(elapsed=elapsed), self.assertRaises(HARNESS.AcceptanceFailure):
                HARNESS.check_http2_result({**result, "cancellation": {"elapsedMillis": elapsed}})
        with self.assertRaises(HARNESS.AcceptanceFailure):
            HARNESS.check_http2_result({**result, "physicalConnection": {"protocol": "HTTP_1_1"}})

    def test_denied_native_tools_are_actually_executed_and_retain_negative_results(self):
        client = Mock()
        client.rpc.return_value = {"result": {"isError": True}}
        for reason in ("disabled", "read-only"):
            with self.subTest(reason=reason):
                result = HARNESS.denied_tool_contract(client, "/diagnostics", "clear_sql_traces", {}, reason)
                self.assertEqual({"name": "clear_sql_traces", "expected": "denied",
                                  "reason": reason, "status": "passed"}, result)
                client.json.assert_called_with("/acceptance/diagnostics/cli/tools/clear_sql_traces",
                                               "POST", {}, expected=403)
                client.rpc.assert_called_with("/diagnostics", "tools/call",
                                              {"name": "clear_sql_traces", "arguments": {}})

    def test_policy_metadata_cannot_replace_a_real_mcp_denial(self):
        client = Mock()
        for reply in ({"result": {"isError": False}}, {"error": {"message": "wrong protocol"}}):
            client.rpc.return_value = reply
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "MCP ignored disabled policy"):
                HARNESS.denied_tool_contract(client, "/diagnostics", "get_sql_traces", {}, "disabled")

    def stream_client(self, interrupt=True):
        client = Mock(port=12345, folder=self.workspace)
        state = {"subscribers": 0, "calls": 1}
        osv = Mock(requests=[], fail_batch=False, hold_batch=False)

        def probe():
            return {"logSubscriptions": {"state": "instantiated", "value": state["subscribers"]},
                    "hostSpans": state["calls"]}

        def host_request(path, *_args):
            if path.endswith("/witness"):
                state["calls"] += 1
            return {}

        def rpc(_api, _method, _params, identifier=1):
            return ({"error": {"message": "MCP server is at capacity; try again shortly."}}
                    if identifier == 100 else {"result": {"structuredContent": {"applicationName": "fixture"}}})

        client.probe.side_effect = probe
        client.json.side_effect = host_request
        client.rpc.side_effect = rpc
        connections = []

        def connect(*_args, **_kwargs):
            connection = Mock()
            response = Mock(status=200)
            response.getheader.return_value = "text/event-stream"
            kind = {"value": None, "closed": False}

            def request(method, _path, **_kwargs):
                kind["value"] = "log" if method == "GET" else "mcp"
                if kind["value"] == "log":
                    state["subscribers"] += 1
                else:
                    osv.requests.append({"method": "POST", "path": "/v1/querybatch", "held": True})

            def close():
                if not kind["closed"]:
                    kind["closed"] = True
                    if kind["value"] == "log":
                        state["subscribers"] -= 1

            def shutdown(*_args):
                if kind["value"] == "mcp" and interrupt:
                    osv.requests[-1]["disconnected"] = True

            def frame(_limit):
                value = {"timestamp": 1, "level": "INFO", "logger": "fixture",
                         "message": f"native-acceptance-witness invocation={state['calls']} password=******"}
                return b"data:" + json.dumps(value).encode() + b"\n"

            connection.request.side_effect = request
            connection.close.side_effect = close
            connection.sock.shutdown.side_effect = shutdown
            response.readline.side_effect = frame
            connection.getresponse.return_value = response
            connections.append(connection)
            return connection

        return client, connect, connections, osv

    def test_native_stream_contract_checks_dto_delivery_sibling_cleanup_and_real_cancellation(self):
        client, connect, connections, osv = self.stream_client()
        with patch.object(HARNESS.http.client, "HTTPConnection", side_effect=connect):
            HARNESS.streams_contract(client, HARNESS.Case("native-streams"), osv)
        self.assertEqual(3, len(connections))
        logs = json.loads((self.workspace / "log-stream-result.json").read_text())
        mcp = json.loads((self.workspace / "mcp-stream-result.json").read_text())
        self.assertEqual(0, logs["subscriptionsAfterCancel"])
        self.assertIn("invocation=2", logs["siblingFrameAfterCancel"]["message"])
        self.assertTrue(mcp["osvRequest"]["disconnected"])
        self.assertLessEqual(mcp["cancellationMillis"], 1500)
        self.assertTrue(mcp["permitReused"])
        self.assertEqual("vulnerabilities_scan", mcp["request"]["params"]["name"])
        self.assertEqual("application/json, text/event-stream",
                         connections[-1].request.call_args.kwargs["headers"]["Accept"])
        self.assertEqual(HARNESS.MCP_VERSION,
                         mcp["request"]["params"]["_meta"]["io.modelcontextprotocol/protocolVersion"])

    def test_stream_disconnect_that_does_not_cancel_host_work_is_a_failure(self):
        client, connect, _connections, osv = self.stream_client(interrupt=False)
        real_wait = HARNESS.wait_until

        def wait(description, predicate, timeout=20):
            if description == "modern MCP cancellation of actual OSV transport":
                self.assertIsNone(predicate())
                raise HARNESS.AcceptanceFailure("disconnect did not cancel")
            return real_wait(description, predicate, timeout)

        with patch.object(HARNESS.http.client, "HTTPConnection", side_effect=connect), \
                patch.object(HARNESS, "wait_until", side_effect=wait):
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "did not cancel"):
                HARNESS.streams_contract(client, HARNESS.Case("native-streams"), osv)
        self.assertFalse((self.workspace / "mcp-stream-result.json").exists())

    def test_read_only_streams_keep_log_reads_without_admitting_an_active_scan(self):
        client, connect, connections, osv = self.stream_client()
        with patch.object(HARNESS.http.client, "HTTPConnection", side_effect=connect):
            HARNESS.streams_contract(client, HARNESS.Case("read-only", policy="read-only"), osv)
        self.assertEqual(2, len(connections))
        self.assertEqual([], osv.requests)
        self.assertEqual("not-applicable",
                         json.loads((self.workspace / "mcp-stream-result.json").read_text())["status"])

    def test_tree_cleanup_reserves_the_leader_until_its_group_has_been_signalled(self):
        process = Mock(pid=123456, args=["only-owned-child"])
        with patch.object(HARNESS.os, "getpgrp", return_value=20), \
                patch.object(HARNESS.os, "getpgid", return_value=123456), \
                patch.object(HARNESS.os, "killpg") as kill, \
                patch.object(HARNESS.Commands, "wait_owned", return_value=0):
            calls = []
            kill.side_effect = lambda *args: calls.append(("signal", args))
            process.wait.side_effect = lambda **_kwargs: calls.append(("reap",))
            HARNESS.Commands.stop(process, 123456)
        self.assertEqual([("signal", (123456, signal.SIGTERM)), ("signal", (123456, signal.SIGKILL)), ("reap",)],
                         calls)
        with patch.object(HARNESS.os, "getpgrp", return_value=123456), patch.object(HARNESS.os, "killpg") as kill:
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "unowned"):
                HARNESS.Commands.stop(process, 123456)
            kill.assert_not_called()

    def test_fast_exited_child_remains_owned_when_darwin_stops_reporting_its_group(self):
        journal = HARNESS.Journal(self.workspace, "macos-arm64", self.workspace / "m2")
        commands = HARNESS.Commands(journal, {})
        process = Mock(pid=123456, args=["fast-child"], returncode=0)
        with patch.object(HARNESS.subprocess, "Popen", return_value=process), \
                patch.object(HARNESS.os, "getpgrp", return_value=20), \
                patch.object(HARNESS.os, "getpgid", side_effect=ProcessLookupError), \
                patch.object(HARNESS.os, "killpg", side_effect=ProcessLookupError), \
                patch.object(HARNESS.Commands, "exited", return_value=True), \
                patch.object(HARNESS.Commands, "wait_owned", return_value=0):
            self.assertEqual("", commands.run("fast-child", ["fast-child"]))
        self.assertEqual(0, journal.data["commands"][0]["exitCode"])
        process.wait.assert_called_once_with(timeout=5)

    def test_missing_group_of_a_live_child_is_not_treated_as_owned(self):
        process = Mock(pid=123456)
        with patch.object(HARNESS.os, "getpgrp", return_value=20), \
                patch.object(HARNESS.os, "getpgid", side_effect=ProcessLookupError), \
                patch.object(HARNESS.Commands, "exited", return_value=False), \
                patch.object(HARNESS.os, "killpg") as kill:
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "still running"):
                HARNESS.Commands.stop(process, process.pid)
            kill.assert_not_called()

    def test_darwin_empty_group_permission_error_requires_no_live_members(self):
        process = Mock(pid=123456)
        for members, succeeds in (("", True), ("123456 Z\n", True), ("123457 S\n", False)):
            with self.subTest(members=members), \
                    patch.object(HARNESS.platform, "system", return_value="Darwin"), \
                    patch.object(HARNESS.os, "killpg", side_effect=PermissionError), \
                    patch.object(HARNESS.Commands, "exited", return_value=True), \
                    patch.object(HARNESS.subprocess, "run", return_value=Mock(
                        returncode=0, stdout=members, stderr="")):
                if succeeds:
                    HARNESS.Commands.signal_owned_group(process, process.pid, signal.SIGTERM)
                else:
                    with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "live members"):
                        HARNESS.Commands.signal_owned_group(process, process.pid, signal.SIGTERM)

    def test_live_or_uninspectable_group_permission_failure_is_not_swallowed(self):
        process = Mock(pid=123456)
        with patch.object(HARNESS.platform, "system", return_value="Darwin"), \
                patch.object(HARNESS.os, "killpg", side_effect=PermissionError), \
                patch.object(HARNESS.Commands, "exited", return_value=False), \
                patch.object(HARNESS.subprocess, "run") as inspect:
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "Permission denied"):
                HARNESS.Commands.signal_owned_group(process, process.pid, signal.SIGTERM)
            inspect.assert_not_called()
        with patch.object(HARNESS.platform, "system", return_value="Darwin"), \
                patch.object(HARNESS.os, "killpg", side_effect=PermissionError), \
                patch.object(HARNESS.Commands, "exited", return_value=True), \
                patch.object(HARNESS.subprocess, "run", return_value=Mock(
                    returncode=1, stdout="", stderr="inspection failed")):
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "Cannot verify"):
                HARNESS.Commands.signal_owned_group(process, process.pid, signal.SIGTERM)

    def test_timeout_records_failure_and_never_returns_success_shaped_output(self):
        journal = HARNESS.Journal(self.workspace, "linux-arm64", self.workspace / "m2")
        commands = HARNESS.Commands(journal, {})
        process = Mock(pid=123456, args=["fake-compiler"])
        process.returncode = -signal.SIGKILL
        with patch.object(HARNESS.subprocess, "Popen", return_value=process), \
                patch.object(HARNESS.os, "getpgid", return_value=123456), \
                patch.object(HARNESS.os, "getpgrp", return_value=20), \
                patch.object(HARNESS.os, "killpg"), \
                patch.object(HARNESS.Commands, "wait_owned",
                             side_effect=[subprocess.TimeoutExpired("fake-compiler", 60), 0]):
            with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "bounded 60s timeout"):
                commands.run("timeout", ["fake-compiler"], timeout=60)
        saved = json.loads((self.workspace / "acceptance-result.json").read_text())
        self.assertTrue(saved["commands"][0]["timedOut"])
        self.assertEqual(-signal.SIGKILL, saved["commands"][0]["exitCode"])

    def test_top_level_failure_is_durable_and_nonzero_without_a_native_success(self):
        output = self.workspace / "failed-output"
        argv = ["--target", "linux-arm64", "--maven-repo", str(self.workspace / "m2"), "--output", str(output)]
        with patch.object(HARNESS, "execute", side_effect=HARNESS.AcceptanceFailure("native compiler failed")), \
                contextlib.redirect_stdout(io.StringIO()):
            result = HARNESS.main(argv)
        saved = json.loads((output / "acceptance-result.json").read_text())
        self.assertEqual(1, result)
        self.assertEqual("failed", saved["status"])
        self.assertEqual("native compiler failed", saved["error"])
        self.assertFalse(saved["builds"])
        with patch.object(HARNESS, "execute") as execute, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, HARNESS.main(argv))
            execute.assert_not_called()

    def test_fixture_is_nonpublished_opt_in_and_has_no_native_reflection_escape_hatch(self):
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.parse(HARNESS.FIXTURE / "pom.xml")
        self.assertEqual("true", pom.findtext("m:properties/m:maven.deploy.skip", namespaces=namespace))
        dependencies = {dependency.findtext("m:artifactId", namespaces=namespace)
                        for dependency in pom.findall("m:dependencies/m:dependency", namespaces=namespace)}
        self.assertTrue({"bootui-quarkus-4", "bootui-quarkus-4-deployment", "quarkus-rest-jackson",
                         "quarkus-jdbc-h2", "quarkus-agroal", "quarkus-opentelemetry"} <= dependencies)
        self.assertNotIn("bootui-quarkus", dependencies)
        aggregator = ET.parse(HARNESS.FIXTURE.parent / "pom.xml")
        self.assertNotIn("native-console", [module.text for module in aggregator.findall("m:modules/m:module",
                                                                                       namespaces=namespace)])
        profile = next(profile for profile in aggregator.findall("m:profiles/m:profile", namespaces=namespace)
                       if profile.findtext("m:id", namespaces=namespace) == "quarkus4-native-acceptance")
        self.assertIsNone(profile.find("m:activation", namespaces=namespace))
        self.assertEqual("native-console", profile.findtext("m:modules/m:module", namespaces=namespace))
        for path in HARNESS.FIXTURE.rglob("*"):
            if path.is_file():
                self.assertNotIn(path.name, ("reflect-config.json", "proxy-config.json", "reachability-metadata.json"))
        settings = (HARNESS.FIXTURE / "src/main/resources/application.properties").read_text()
        for exporter in ("traces", "metrics", "logs"):
            self.assertIn(f"quarkus.otel.{exporter}.exporter=none", settings)
        self.assertIn("quarkus.http.host=127.0.0.1", settings)


class HttpProbeAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.workspace = ROOT / ".ci-script-tests" / ("native-http-probe-" + uuid.uuid4().hex)
        self.workspace.mkdir(parents=True)
        self.panel = {"id": "http-probe", "available": True, "enabled": True,
                      "readOnly": False, "readOnlyReason": None}

    def tearDown(self):
        shutil.rmtree(self.workspace)

    def client(self, changes=None, read_only=False):
        snapshot = {"protocol": HARNESS.PROTOCOL,
                    "counters": {name: {"state": "uninstantiated", "value": None}
                                 for name in ("http", "logs", "sql", "exceptions", "telemetry", "journal")},
                    "logSubscriptions": {"state": "uninstantiated", "value": None},
                    "ownedThreads": [], "installedHandlers": [], "hostSpans": 2,
                    "slowConfig": {"entered": 0, "interrupted": 0, "completed": 0}}
        original = json.dumps(snapshot)
        response = {"status": 200, "statusText": "OK", "headers": {"content-type": "application/json"},
                    "body": original, "durationMs": 3, "error": None, "truncated": False, **(changes or {})}
        client = Mock(port=32123, folder=self.workspace)
        client.request.return_value = (200, {"content-type": "application/json",
                                            "x-native-fixture-private": "fixture-only",
                                            "set-cookie": "native-acceptance-private=fixture-only; HttpOnly"},
                                       original.encode())

        def request(path, method="GET", payload=None, headers=None, expected=200):
            if path.endswith("/health"):
                return {"status": "UP"}
            self.assertTrue(path.endswith("/http-probe"), path)
            self.assertEqual("POST", method, "HTTP Probe is an action endpoint, never a root GET")
            self.assertEqual({"method", "path", "body", "headers"}, set(payload))
            if expected == 403:
                if headers.get("Host") == "attacker.invalid":
                    return {"error": "BootUI rejected an unrecognized Host header. "
                                     "Add it to bootui.allowed-hosts to allow this hostname."}
                if headers.get("Sec-Fetch-Site") != "same-origin":
                    return {"error": "BootUI rejected a cross-site request to a state-changing endpoint."}
                self.assertTrue(read_only)
                return {"error": "BootUI panel access denied", "panel": "http-probe",
                        "reason": self.panel["readOnlyReason"]}
            self.assertEqual({"Origin": "http://127.0.0.1:32123", "Sec-Fetch-Site": "same-origin"}, headers)
            if expected == 400:
                supplied = payload["headers"]
                if len(payload["method"].encode()) > 32:
                    message = "request method exceeds the maximum of 32 bytes"
                elif len(payload["path"].encode()) > 2048:
                    message = "request path exceeds the maximum of 2048 bytes"
                elif len((payload["body"] or "").encode()) > 65536:
                    message = "request body exceeds the maximum of 65536 bytes"
                elif len(supplied) > 50:
                    message = "request exceeds the maximum of 50 request headers"
                elif any(len(name.encode()) > 256 for name in supplied):
                    message = "request header name exceeds the maximum of 256 bytes"
                elif any(len(value.encode()) > 8192 for value in supplied.values()):
                    message = "request header value exceeds the maximum of 8192 bytes"
                else:
                    self.assertGreater(sum(len(name.encode()) + len(value.encode())
                                           for name, value in supplied.items()), 32768)
                    message = "request headers exceed the maximum total of 32768 bytes"
                return {"error": "HTTP Probe " + message}
            self.assertEqual(HARNESS.HOST_PATH + "/probe", payload["path"])
            self.assertEqual("GET", payload["method"])
            self.assertIsNone(payload["body"])
            self.assertEqual("attacker.invalid", payload["headers"]["Host"])
            return json.loads(json.dumps(response))

        client.json.side_effect = request
        return client

    def report(self):
        return json.loads((self.workspace / "http-probe-result.json").read_text())

    def test_post_only_probe_exercises_real_dto_custom_and_default_mounts_and_all_input_budgets(self):
        for api in ("/diagnostics", "/bootui/api"):
            with self.subTest(api=api):
                client = self.client()
                HARNESS.panel_contract(client, HARNESS.Case("http-probe", api=api), "http-probe", self.panel)
                result = self.report()
                self.assertEqual("passed", result["status"])
                self.assertEqual("/acceptance" + api + "/http-probe", result["endpoint"])
                client.request.assert_called_once_with("/acceptance/native-fixture/probe", expected=200)
                checks = {check["name"]: check for check in result["checks"]}
                self.assertEqual(13, len(checks))
                self.assertEqual("active", checks["loopback-and-sanitization"]["expected"])
                self.assertEqual(8, sum(check["expected"] == "invalid" for check in checks.values()))
                calls = [call for call in client.json.call_args_list if call.kwargs.get("expected") == 400]
                unicode_body = next(call.args[2]["body"] for call in calls
                                    if call.args[2]["body"] and "é" in call.args[2]["body"])
                self.assertLess(len(unicode_body), 65536)
                self.assertGreater(len(unicode_body.encode()), 65536)
                self.assertTrue(all(call.args[1] == "POST" for call in calls))

    def test_global_read_only_is_a_real_denial_without_dispatching_the_probe(self):
        self.panel.update(readOnly=True, readOnlyReason="BootUI is read-only via bootui.read-only=true")
        client = self.client(read_only=True)
        HARNESS.panel_contract(client, HARNESS.Case("read-only-policy", policy="read-only"),
                               "http-probe", self.panel)
        client.request.assert_not_called()
        result = self.report()
        self.assertEqual("passed", result["status"])
        self.assertNotIn("response", result)
        self.assertEqual("read-only", result["checks"][-1]["name"])
        self.assertTrue(all(check["expected"] == "denied" for check in result["checks"]))

    def test_private_header_leaks_bad_outcomes_and_surrogate_nested_dtos_are_durable_failures(self):
        scenarios = [
            {"headers": {"content-type": "application/json", "set-cookie": "private=value"}},
            {"headers": {"content-type": "application/json", "x-native-fixture-private": "fixture-only"}},
            {"headers": {"content-type": 123}},
            {"status": 0, "statusText": "Error", "error": "connection refused"},
            {"truncated": True},
            {"durationMs": -1},
            {"durationMs": HARNESS.HTTP_TIMEOUT * 1000 + 1},
            {"body": HARNESS.SECRET},
            {"body": HARNESS.TOKEN},
            {"body": '{"protocol":"native-acceptance-v1","counters":{}}'},
        ]
        for changes in scenarios:
            with self.subTest(changes=changes):
                client = self.client(changes)
                with self.assertRaises(HARNESS.AcceptanceFailure):
                    HARNESS.http_probe_contract(client, HARNESS.Case("bad-http-probe"), self.panel)
                self.assertEqual("failed", self.report()["status"])
                self.assertIn("error", self.report())

    def test_sanitization_cannot_pass_without_actual_private_header_witnesses(self):
        client = self.client()
        client.request.return_value = (200, {"content-type": "application/json"}, b"{}")
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "private response-header witnesses are absent"):
            HARNESS.http_probe_contract(client, HARNESS.Case("no-header-witness"), self.panel)
        self.assertEqual("failed", self.report()["status"])

    def test_csrf_rejections_cannot_be_replaced_by_an_accepted_action(self):
        client = self.client()
        client.json.side_effect = None
        client.json.return_value = {"status": 200}
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "rejection is not canonical"):
            HARNESS.http_probe_contract(client, HARNESS.Case("csrf-bypass"), self.panel)
        client.request.assert_not_called()
        self.assertEqual("failed", self.report()["status"])

    def test_invalid_input_is_not_a_success_shaped_probe_outcome_or_generic_error(self):
        for failure in ({"status": 0, "statusText": "Error", "error": "invalid"},
                        {"error": "generic validation error"}):
            with self.subTest(failure=failure):
                client = self.client()
                actual = client.json.side_effect

                def request(*args, **kwargs):
                    return failure if kwargs.get("expected") == 400 else actual(*args, **kwargs)

                client.json.side_effect = request
                with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "canonical bounded-input error"):
                    HARNESS.http_probe_contract(client, HARNESS.Case("bad-input-error"), self.panel)
                result = self.report()
                self.assertEqual("failed", result["status"])
                self.assertIn("loopback-and-sanitization", [check["name"] for check in result["checks"]])

    def test_initial_native_http_probe_support_must_not_be_marked_unavailable(self):
        manifest = {"platform": "quarkus", "panels": [
            {"id": name, "available": name != "http-probe", "enabled": True,
             "unavailableReason": "not implemented" if name == "http-probe" else None}
            for name in HARNESS.REQUIRED_PANELS]}
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "http-probe is unavailable"):
            HARNESS.check_manifest(manifest)

    def test_supported_http_probe_cannot_be_disabled_to_skip_its_positive_contract(self):
        manifest = {"platform": "quarkus", "panels": [
            {"id": name, "available": True, "enabled": name != "http-probe", "unavailableReason": None}
            for name in HARNESS.REQUIRED_PANELS]}
        client = Mock()
        client.request.side_effect = [
            (200, {"content-type": "text/html"}, b'<div id="app"></div><script src="main.js"></script>'),
            (200, {}, b"native-fixture-ui"),
        ]
        client.json.return_value = manifest
        with self.assertRaisesRegex(HARNESS.AcceptanceFailure, "HTTP Probe was unexpectedly disabled"):
            HARNESS.positive_contract(client, HARNESS.Case("no-http-probe"), ["assets/main.js"],
                                      Mock(), ROOT / "fake-java", ROOT / "fake-cli.jar")


if __name__ == "__main__":
    unittest.main()
