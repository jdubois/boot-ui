import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / "docs/.vuepress/public"
KEY = ROOT / ".github/actions/bootui/bootui-release-key.asc"
FINGERPRINT = "7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9"


class InstallerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bin = self.root / "tools"
        self.bin.mkdir()
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.jar = self.repository / "bootui-cli-test-all.jar"
        self.jar.write_bytes(b"test artifact")
        for command in (
            "sh", "awk", "tr", "sed", "cat", "cp", "mv", "rm", "rmdir", "ln", "mkdir",
            "chmod", "mktemp", "head", "dirname", "basename", "grep",
            "sha512sum", "sha256sum", "sha1sum", "shasum", "openssl",
        ):
            executable = shutil.which(command)
            if executable is not None:
                (self.bin / command).symlink_to(executable)
        self.environment = dict(
            os.environ, PATH=str(self.bin),
            FIXTURE=str(self.repository), KEY=str(KEY),
            BOOTUI_INSTALL_DIR=str(self.root / "install"), BOOTUI_BIN_DIR=str(self.root / "bin"),
        )
        self.tool("curl", """#!/bin/sh
set -eu
out=
if [ "$1" = -fsSL ]; then shift; fi
if [ "$1" = --retry ]; then shift 2; fi
url="$1"; shift
if [ "${1:-}" = -o ]; then out="$2"; fi
case "$url" in
  */bootui-release-key.asc) file="$KEY";;
  *) file="$FIXTURE/${url##*/}";;
esac
[ -f "$file" ] || exit 22
if [ -n "$out" ]; then cp "$file" "$out"; else cat "$file"; fi
""")

    def tool(self, name, text):
        path = self.bin / name
        path.unlink(missing_ok=True)
        path.write_text(text)
        path.chmod(0o755)

    def checksum(self, algorithm="sha256", value=None):
        digest = value if value is not None else hashlib.new(algorithm, self.jar.read_bytes()).hexdigest()
        self.jar.with_name(self.jar.name + "." + algorithm).write_text(digest)

    def install(self, *options):
        return subprocess.run(
            ["/bin/sh", str(PUBLIC / "install.sh"), "--version", "test", "--repo", "https://fixture.invalid", *options],
            env=self.environment, capture_output=True, text=True, check=False,
        )

    def assert_not_installed(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / "install").exists())
        self.assertFalse((self.root / "bin").exists())

    def test_missing_checksum_fails_closed(self):
        result = self.install()
        self.assert_not_installed(result)
        self.assertIn("no checksum could be verified", result.stderr)

    def test_mismatch_and_malformed_checksums_cannot_be_bypassed(self):
        for value in ("0" * 64, "malformed", ""):
            with self.subTest(value=value):
                self.checksum(value=value)
                self.assert_not_installed(self.install("--allow-unverified"))

    def test_failed_hash_command_does_not_become_a_successful_empty_pipeline(self):
        self.checksum()
        for command in ("sha512sum", "sha256sum", "sha1sum", "shasum", "openssl"):
            self.tool(command, "#!/bin/sh\nexit 1\n")
        result = self.install()
        self.assert_not_installed(result)
        self.assertIn("no checksum could be verified", result.stderr)

    def test_checksum_only_install_reports_no_signer_authentication(self):
        self.checksum()
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("published SHA-256 checksum matched", result.stdout)
        self.assertIn("signer not authenticated", result.stdout)
        self.assertEqual((self.root / "install" / self.jar.name).read_bytes(), self.jar.read_bytes())

    def test_legacy_sha1_is_retained_with_an_explicit_warning(self):
        self.checksum("sha1")
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("only the legacy SHA-1", result.stdout)

    def test_explicit_missing_checksum_override_is_honest(self):
        result = self.install("--allow-unverified")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("checksum verification was explicitly bypassed", result.stdout)
        self.assertIn("signer not authenticated", result.stdout)

    def test_available_gpg_requires_a_valid_signature_from_the_pinned_key(self):
        self.checksum()
        self.jar.with_name(self.jar.name + ".asc").write_text("fixture signature")
        self.tool("gpg", """#!/bin/sh
case " $* " in
  *" --import "*) exit 0;;
  *" --verify "*)
    [ "${VERIFY:-valid}" != invalid ] || exit 1
    printf '[GNUPG:] VALIDSIG SIGNING-SUBKEY 0 %s\\n' "${SIGNER:-7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9}"
    ;;
  *) exit 2;;
esac
""")
        for mode, signer in (("invalid", FINGERPRINT), ("valid", "F" * 40)):
            self.environment.update(VERIFY=mode, SIGNER=signer)
            self.assert_not_installed(self.install("--allow-unverified"))
        self.environment.update(VERIFY="valid", SIGNER=FINGERPRINT)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"signature matched pinned BootUI key {FINGERPRINT}", result.stdout)

    def test_bundled_and_published_keys_match_the_pin(self):
        self.assertEqual(KEY.read_bytes(), (PUBLIC / "bootui-release-key.asc").read_bytes())
        gpg = shutil.which("gpg")
        if gpg is None:
            self.skipTest("GnuPG unavailable for public-key fingerprint inspection")
        keyring = self.root / "key-inspection"
        keyring.mkdir(mode=0o700)
        result = subprocess.run(
            [gpg, "--homedir", str(keyring), "--batch", "--no-autostart", "--show-keys", "--with-colons", str(KEY)],
            capture_output=True, text=True, check=True,
        )
        self.assertIn(f"fpr:::::::::{FINGERPRINT}:", result.stdout)

    def test_short_gpg_alias_remains_inside_a_private_directory(self):
        self.checksum()
        self.jar.with_name(self.jar.name + ".asc").write_text("fixture signature")
        probe = self.root / "keyring-probe.py"
        record = self.root / "alias-directory"
        probe.write_text("""import os, stat, sys
from pathlib import Path
home = Path(sys.argv[1])
assert home.is_symlink()
assert not home.parent.is_symlink()
assert stat.S_IMODE(home.parent.stat().st_mode) == 0o700
Path(os.environ["KEYRING_RECORD"]).write_text(str(home.parent))
""")
        self.environment.update(
            PYTHON_FIXTURE=sys.executable, KEYRING_PROBE=str(probe), KEYRING_RECORD=str(record)
        )
        self.tool("gpg", f"""#!/bin/sh
home=
previous=
for argument do
  if [ "$previous" = --homedir ]; then home="$argument"; fi
  previous="$argument"
done
"$PYTHON_FIXTURE" "$KEYRING_PROBE" "$home" || exit 1
printf '[GNUPG:] VALIDSIG SIGNING-SUBKEY 0 {FINGERPRINT}\\n'
""")
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(Path(record.read_text()).exists())

    def test_powershell_contract_has_the_same_fail_closed_and_pinned_signer_checks(self):
        script = (PUBLIC / "install.ps1").read_text()
        self.assertIn("[switch] $AllowUnverified", script)
        self.assertIn("if (-not $AllowUnverified)", script)
        self.assertIn("malformed published $algorithm checksum", script)
        self.assertIn("$keyImport.ExitCode -ne 0", script)
        self.assertIn("$verification.ExitCode -ne 0", script)
        self.assertIn("signer not authenticated", script)
        self.assertIn(FINGERPRINT, script)


class PowerShellInstallerTests(unittest.TestCase):
    def setUp(self):
        self.runtime = (
            Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
            if os.name == "nt" else shutil.which("pwsh")
        )
        if self.runtime is None:
            self.skipTest("PowerShell unavailable for installer execution")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.install_dir = self.root / "install dir"
        self.bin_dir = self.root / "bin dir"
        self.launcher = self.bin_dir / "bootui.cmd"
        self.record = self.install_dir / "installation-v1"
        tools = self.root / "tools"
        tools.mkdir()
        if os.name == "nt":
            (tools / "gpg.cmd").write_text(f"""@echo off
echo successful GnuPG diagnostic 1>&2
echo [GNUPG:] VALIDSIG SIGNING-SUBKEY 0 {FINGERPRINT}
exit /b 0
""")
        else:
            gpg = tools / "gpg"
            gpg.write_text(f"""#!/bin/sh
echo 'successful GnuPG diagnostic' >&2
printf '[GNUPG:] VALIDSIG SIGNING-SUBKEY 0 {FINGERPRINT}\\n'
""")
            gpg.chmod(0o755)
        self.environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ.get("PATH", ""))

    def jar(self, version):
        return self.install_dir / f"bootui-cli-{version}-all.jar"

    def install(
        self, version, payload, *, repo="https://fixture.invalid", locked=None,
        fail_launcher_write=False, fail_old_jar_lookup=False,
    ):
        fixture = self.repository / self.jar(version).name
        fixture.write_bytes(payload)
        fixture.with_suffix(".jar.sha256").write_text(hashlib.sha256(payload).hexdigest())
        fixture.with_suffix(".jar.asc").write_text("fixture signature")

        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"

        bootstrap = self.root / "install-fixture.ps1"
        lock_path = quote(locked) if locked is not None and os.name == "nt" else "$null"
        launcher_write_fault = r"""
function Set-Content {
  param([string] $LiteralPath, [string] $Encoding)
  begin {
    [IO.File]::WriteAllText($LiteralPath, 'partial launcher')
    throw 'Fixture launcher write failed'
  }
}
""" if fail_launcher_write else ""
        old_jar_lookup_fault = """
$WarningPreference = 'Stop'
function Get-ChildItem { throw 'Fixture could not enumerate old JARs' }
""" if fail_old_jar_lookup else ""
        bootstrap.write_text(f"""
$ErrorActionPreference = 'Stop'
function Invoke-WebRequest {{
  param([string] $Uri, [string] $OutFile, [switch] $UseBasicParsing)
  $name = [IO.Path]::GetFileName(([Uri] $Uri).AbsolutePath)
  $source = if ($name -eq 'bootui-release-key.asc') {{ {quote(KEY)} }} else {{ Join-Path {quote(self.repository)} $name }}
  if (-not (Test-Path -LiteralPath $source)) {{ throw 'Fixture asset absent' }}
  if ($OutFile) {{ Copy-Item -LiteralPath $source -Destination $OutFile; return }}
  [pscustomobject] @{{ Content = [IO.File]::ReadAllText($source) }}
}}
{launcher_write_fault}
{old_jar_lookup_fault}
$path = {lock_path}
$lock = if ($path) {{ [IO.File]::Open($path, 'Open', 'Read', 'Read') }}
try {{
  & {quote(PUBLIC / 'install.ps1')} -Version {quote(version)} -MavenRepo {quote(repo)} `
    -InstallDir {quote(self.install_dir)} -BinDir {quote(self.bin_dir)} -NoPathUpdate
  exit $LASTEXITCODE
}} finally {{
  if ($lock) {{ $lock.Dispose() }}
}}
""", encoding="utf-8-sig" if os.name == "nt" else "utf-8")
        command = [self.runtime, "-NoLogo", "-NoProfile", "-NonInteractive"]
        if os.name == "nt":
            command += ["-ExecutionPolicy", "RemoteSigned"]
        if locked is not None and sys.platform == "darwin":
            subprocess.run(["chflags", "uchg", str(locked)], check=True)
        try:
            return subprocess.run(
                [*command, "-File", str(bootstrap)],
                env=self.environment, capture_output=True, text=True, timeout=30, check=False,
            )
        finally:
            if locked is not None and sys.platform == "darwin":
                subprocess.run(["chflags", "nouchg", str(locked)], check=True)

    def test_powershell_accepts_successful_native_diagnostics(self):
        result = self.install("test", b"test artifact")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("signature matched pinned BootUI key", result.stdout)
        self.assertEqual(self.jar("test").read_bytes(), b"test artifact")

    def test_powershell_successful_upgrade_switches_launcher_record_and_jar(self):
        installed = self.install("previous", b"previous artifact")
        self.assertEqual(installed.returncode, 0, installed.stdout + installed.stderr)
        repo = "https://fixture.invalid/upgraded"
        result = self.install("next", b"next artifact", repo=repo)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f'set "BOOTUI_JAR={self.jar("next")}"', self.launcher.read_text())
        self.assertEqual(
            self.record.read_bytes().decode("utf-8"),
            f"bootui-install-v1\n{self.jar('next')}\n{self.launcher}\n{repo}\n",
        )
        self.assertEqual(self.jar("next").read_bytes(), b"next artifact")
        self.assertFalse(self.jar("previous").exists())
        self.assertCountEqual(self.install_dir.iterdir(), [self.record, self.jar("next")])
        self.assertCountEqual(self.bin_dir.iterdir(), [self.launcher])

    def test_powershell_cleanup_lookup_failure_leaves_new_install_runnable(self):
        if os.name != "nt" and not all(shutil.which(tool) for tool in ("java", "javac", "jar")):
            self.skipTest("a local JDK is needed for the runnable JAR fixture")
        previous = self.install("previous", b"previous artifact")
        self.assertEqual(previous.returncode, 0, previous.stdout + previous.stderr)
        payload = self.launcher_probe_jar()
        result = self.install("next", payload, fail_old_jar_lookup=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Fixture could not enumerate old JARs", result.stdout)
        self.assertIn("WARNING:", result.stdout)
        self.assertIn("Installed bootui-cli-next-all.jar", result.stdout)
        self.assertEqual(self.jar("previous").read_bytes(), b"previous artifact")
        self.assertEqual(self.jar("next").read_bytes(), payload)
        self.assertEqual(self.record.read_text(encoding="utf-8").splitlines()[1], str(self.jar("next")))
        self.assertIn(f'set "BOOTUI_JAR={self.jar("next")}"', self.launcher.read_text())

        # Exercise the generated launcher on Windows and its target JAR on other platforms.
        command = [os.environ["ComSpec"], "/d", "/c", r".\bootui.cmd"] if os.name == "nt" else [
            "java", "-jar", str(self.jar("next")),
        ]
        probe = subprocess.run(
            [*command, "0", "new install"], cwd=self.bin_dir, env=self.environment,
            capture_output=True, text=True, timeout=30, check=False,
        )
        self.assertEqual(probe.returncode, 0, probe.stdout + probe.stderr)
        self.assertEqual(probe.stdout.splitlines(), ["0", "new install"])

    def test_powershell_failed_upgrade_keeps_previous_launch_files(self):
        if os.name != "nt" and sys.platform != "darwin":
            self.skipTest("failure fixture needs a Windows sharing lock or macOS immutable flag")
        payload = b"replacement artifact"
        repo = "https://fixture.invalid/upgraded"
        for locked, requested in (
            (self.launcher, "next"),
            (self.record, "next"),
            (self.launcher, "previous"),
        ):
            with self.subTest(locked=locked.name, requested=requested):
                installed = self.install("previous", b"previous artifact")
                self.assertEqual(installed.returncode, 0, installed.stdout + installed.stderr)
                previous = {path: path.read_bytes() for path in (self.launcher, self.record, self.jar("previous"))}
                result = self.install(requested, payload, repo=repo, locked=locked)
                self.assert_replacement_failure(result)
                self.assert_previous_install(result, previous)
                if sys.platform == "darwin":
                    # .NET on macOS can create a hard-linked backup before the failed rename.
                    # Neither link can be removed until the fixture clears their shared flag.
                    for backup in locked.parent.glob(".bootui-*.bak"):
                        self.assertIn(f"Could not remove installer scratch file {backup}", result.stdout)
                        self.assertEqual(backup.read_bytes(), previous[locked])
                        backup.unlink()
                self.assert_only_previous_files()
                retry = self.install(requested, payload, repo=repo)
                self.assertEqual(retry.returncode, 0, retry.stdout + retry.stderr)
                self.assertEqual(self.jar(requested).read_bytes(), payload)

    def test_powershell_failed_launcher_write_keeps_previous_launch_files(self):
        installed = self.install("previous", b"previous artifact")
        self.assertEqual(installed.returncode, 0, installed.stdout + installed.stderr)
        previous = {path: path.read_bytes() for path in (self.launcher, self.record, self.jar("previous"))}
        result = self.install("next", b"replacement artifact", fail_launcher_write=True)
        self.assertIn("Fixture launcher write failed", result.stderr)
        self.assert_previous_install(result, previous)
        self.assert_only_previous_files()

    @unittest.skipUnless(os.name == "nt", "requires native cmd.exe")
    def test_powershell_launcher_forwards_arguments_and_status_after_failed_upgrade(self):
        initial = self.install("previous", self.launcher_probe_jar())
        self.assertEqual(initial.returncode, 0, initial.stdout + initial.stderr)
        failure = self.install("next", b"replacement artifact", locked=self.launcher)
        self.assert_replacement_failure(failure)
        self.assert_only_previous_files()

        for use_java_home in (True, False):
            environment = dict(self.environment)
            if use_java_home:
                environment["PATH"] = str(self.root / "tools")
            else:
                environment.pop("JAVA_HOME", None)
            for status in (0, 1, 2):
                with self.subTest(use_java_home=use_java_home, status=status):
                    arguments = [str(status), "--name", "two words"]
                    result = subprocess.run(
                        [os.environ["ComSpec"], "/d", "/c", r".\bootui.cmd", *arguments],
                        cwd=self.bin_dir, env=environment, capture_output=True, text=True, timeout=30, check=False,
                    )
                    self.assertEqual(result.returncode, status, result.stdout + result.stderr)
                    self.assertEqual(result.stdout.splitlines(), arguments)

    def launcher_probe_jar(self):
        (self.root / "LauncherProbe.java").write_text("""public class LauncherProbe {
    public static void main(String[] args) {
        for (String arg : args) {
            System.out.println(arg);
        }
        System.exit(Integer.parseInt(args[0]));
    }
}
""", encoding="ascii")
        subprocess.run(
            ["javac", "--release", "17", "LauncherProbe.java"], cwd=self.root, timeout=30, check=True,
        )
        subprocess.run(
            ["jar", "--create", "--file", "launcher-probe.jar", "--main-class", "LauncherProbe", "LauncherProbe.class"],
            cwd=self.root, timeout=30, check=True,
        )
        return (self.root / "launcher-probe.jar").read_bytes()

    def assert_replacement_failure(self, result):
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("bootui install: could not complete installation:", result.stderr, output)

    def assert_previous_install(self, result, previous):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("Installed bootui-cli-", result.stdout)
        for path, contents in previous.items():
            self.assertTrue(path.is_file(), f"Missing previous file: {path.name}")
            self.assertEqual(path.read_bytes(), contents, path.name)

    def assert_only_previous_files(self):
        self.assertCountEqual(self.install_dir.iterdir(), [self.record, self.jar("previous")])
        self.assertCountEqual(self.bin_dir.iterdir(), [self.launcher])


if __name__ == "__main__":
    unittest.main()
