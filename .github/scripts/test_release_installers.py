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

    def install(self, *options, version="test"):
        return subprocess.run(
            ["/bin/sh", str(PUBLIC / "install.sh"), "--version", version, "--repo", "https://fixture.invalid", *options],
            env=self.environment, capture_output=True, text=True, check=False,
        )

    def prepare_upgrade(self):
        self.checksum()
        self.tool("java", """#!/bin/sh
if [ "$1" = -version ]; then echo 'java version 17'; exit 0; fi
[ "$1" = -jar ] || exit 99
cat "$2" || exit 99
shift 2
printf '\\n%s\\n' "$@"
exit 2
""")
        self.environment["JAVA_HOME"] = str(self.root / "missing-java")
        installed = self.install()
        self.assertEqual(installed.returncode, 0, installed.stdout + installed.stderr)
        next_jar = self.repository / "bootui-cli-next-all.jar"
        next_jar.write_bytes(b"next artifact")
        next_jar.with_suffix(".jar.sha256").write_text(hashlib.sha256(next_jar.read_bytes()).hexdigest())
        return (
            self.root / "install" / self.jar.name,
            self.root / "install" / next_jar.name,
            self.root / "bin" / "bootui",
            self.root / "install" / "installation-v1",
        )

    def assert_launcher_runs(self, launcher, artifact):
        result = subprocess.run(
            [str(launcher), "argument with spaces"], env=self.environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(result.stdout, f"{artifact}\nargument with spaces\n")

    def assert_failed_upgrade_preserves_install(self, tool, script, failure_code):
        previous, _, launcher, record = self.prepare_upgrade()
        previous_launcher = launcher.read_bytes()
        previous_record = record.read_bytes()
        self.tool(tool, script)
        result = self.install(version="next")
        self.assertEqual(result.returncode, failure_code, result.stdout + result.stderr)
        self.assertEqual(
            (launcher.read_bytes(), previous.exists()),
            (previous_launcher, True),
            "the previous launcher and its JAR must both survive",
        )
        self.assertEqual(previous.read_bytes(), self.jar.read_bytes())
        self.assertEqual(record.read_bytes(), previous_record)
        self.assertEqual(list(launcher.parent.iterdir()), [launcher])
        self.assert_launcher_runs(launcher, "test artifact")

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

    def test_failed_launcher_write_keeps_previous_installation(self):
        self.assert_failed_upgrade_preserves_install("cat", """#!/bin/sh
if [ "$#" -eq 0 ]; then
  printf 'partial launcher\\n'
  exit 42
fi
exec /bin/cat "$@"
""", failure_code=42)

    def test_failed_launcher_replacement_keeps_previous_installation(self):
        self.assert_failed_upgrade_preserves_install("mv", """#!/bin/sh
for argument do
  case "$argument" in */bootui) exit 43;; esac
done
exec /bin/mv "$@"
""", failure_code=1)

    def test_successful_upgrade_replaces_launcher_and_cleans_up_previous_jar(self):
        previous, target, launcher, record = self.prepare_upgrade()
        self.assert_launcher_runs(launcher, "test artifact")
        result = self.install(version="next")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(list(target.parent.glob("bootui-cli-*-all.jar")), [target])
        self.assertFalse(previous.exists())
        self.assertEqual(
            record.read_text().splitlines(),
            ["bootui-install-v1", str(target.resolve()), str(launcher.resolve()), "https://fixture.invalid"],
        )
        self.assertEqual(list(launcher.parent.iterdir()), [launcher])
        self.assert_launcher_runs(launcher, "next artifact")

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

    def test_powershell_accepts_successful_native_diagnostics(self):
        runtime = shutil.which("powershell") or shutil.which("pwsh")
        if runtime is None:
            self.skipTest("PowerShell unavailable for native diagnostic execution")
        self.checksum()
        self.jar.with_name(self.jar.name + ".asc").write_text("fixture signature")
        if os.name == "nt":
            self.tool("gpg.cmd", f"""@echo off
echo successful GnuPG diagnostic 1>&2
echo [GNUPG:] VALIDSIG SIGNING-SUBKEY 0 {FINGERPRINT}
exit /b 0
""")
        else:
            self.tool("gpg", f"""#!/bin/sh
echo 'successful GnuPG diagnostic' >&2
printf '[GNUPG:] VALIDSIG SIGNING-SUBKEY 0 {FINGERPRINT}\\n'
""")

        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"

        bootstrap = self.root / "install-fixture.ps1"
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
& {quote(PUBLIC / 'install.ps1')} -Version test -MavenRepo https://fixture.invalid `
  -InstallDir {quote(self.root / 'install')} -BinDir {quote(self.root / 'bin')} -NoPathUpdate
exit $LASTEXITCODE
""")
        environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""))
        result = subprocess.run(
            [runtime, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(bootstrap)],
            env=environment, capture_output=True, text=True, timeout=30, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("signature matched pinned BootUI key", result.stdout)
        self.assertEqual((self.root / "install" / self.jar.name).read_bytes(), self.jar.read_bytes())

    def test_powershell_contract_has_the_same_fail_closed_and_pinned_signer_checks(self):
        script = (PUBLIC / "install.ps1").read_text()
        self.assertIn("[switch] $AllowUnverified", script)
        self.assertIn("if (-not $AllowUnverified)", script)
        self.assertIn("malformed published $algorithm checksum", script)
        self.assertIn("$keyImport.ExitCode -ne 0", script)
        self.assertIn("$verification.ExitCode -ne 0", script)
        self.assertIn("signer not authenticated", script)
        self.assertIn(FINGERPRINT, script)


if __name__ == "__main__":
    unittest.main()
