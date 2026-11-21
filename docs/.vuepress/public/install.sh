#!/bin/sh
#
# BootUI CLI installer for Linux and macOS.
#
#   curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh
#
# It downloads the runnable `bootui` jar from Maven Central, checks it against the
# checksum Maven Central publishes beside it, and writes a small launcher on your PATH.
# It never uses sudo or edits your shell profile. With GnuPG installed, it also
# verifies the pinned release signature using the key served by the BootUI site.

set -eu

GROUP_PATH="com/julien-dubois/bootui"
ARTIFACT="bootui-cli"
DEFAULT_REPO="https://repo1.maven.org/maven2"

version="${BOOTUI_VERSION:-}"
install_dir="${BOOTUI_INSTALL_DIR:-$HOME/.bootui}"
bin_dir="${BOOTUI_BIN_DIR:-$HOME/.local/bin}"
repo="${BOOTUI_MAVEN_REPO:-$DEFAULT_REPO}"
uninstall=no
allow_unverified=no
RELEASE_KEY_FINGERPRINT="7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9"
RELEASE_KEY_URL="https://www.julien-dubois.com/boot-ui/bootui-release-key.asc"

tmp_dir=""
keyring_alias_dir=""
tmp_launcher=""

cleanup() {
  if [ -n "$keyring_alias_dir" ] && [ -d "$keyring_alias_dir" ]; then
    rm -f "$keyring_alias_dir/keyring"
    rmdir "$keyring_alias_dir" ||
      printf 'bootui install: could not remove private keyring alias directory %s\n' "$keyring_alias_dir" >&2
  fi
  if [ -n "$tmp_dir" ] && [ -d "$tmp_dir" ]; then
    rm -rf "$tmp_dir"
  fi
  if [ -n "$tmp_launcher" ]; then
    rm -f "$tmp_launcher"
  fi
}
trap cleanup EXIT INT TERM

die() {
  printf 'bootui install: %s\n' "$1" >&2
  exit 1
}

say() {
  printf '%s\n' "$1"
}

usage() {
  cat <<'USAGE'
Install the BootUI command-line tool.

  curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh

Options, which go after `sh -s --` when the script is piped:

  --version <v>     Install this version instead of the newest release.
  --dir <path>      Where the jar goes.       Default: ~/.bootui
  --bin-dir <path>  Where the launcher goes.  Default: ~/.local/bin
  --repo <url>      Maven repository base.    Default: https://repo1.maven.org/maven2
  --uninstall       Remove what this script installed.
  --allow-unverified  Explicitly permit a missing/uncomputable checksum (not a mismatch).
  -h, --help        Show this message.

The same settings are read from BOOTUI_VERSION, BOOTUI_INSTALL_DIR, BOOTUI_BIN_DIR
and BOOTUI_MAVEN_REPO.

The installed command mentions it, on a terminal, once a newer release appears on
Maven Central. Set BOOTUI_NO_UPDATE_CHECK=1 to switch that off.
USAGE
  exit 0
}

need_value() {
  if [ "$2" -lt 2 ]; then
    die "$1 needs a value"
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version) need_value "$1" $#; version="$2"; shift 2 ;;
    --dir) need_value "$1" $#; install_dir="$2"; shift 2 ;;
    --bin-dir) need_value "$1" $#; bin_dir="$2"; shift 2 ;;
    --repo) need_value "$1" $#; repo="${2%/}"; shift 2 ;;
    --uninstall) uninstall=yes; shift ;;
    --allow-unverified) allow_unverified=yes; shift ;;
    -h | --help) usage ;;
    *) die "unknown option '$1'. Try --help." ;;
  esac
done

# ---------------------------------------------------------------- uninstall

if [ "$uninstall" = yes ]; then
  removed=no
  if [ -e "$bin_dir/bootui" ]; then
    rm -f "$bin_dir/bootui"
    say "Removed $bin_dir/bootui"
    removed=yes
  fi
  # Only ever delete jars this installer wrote, never the directory blindly.
  for jar in "$install_dir/$ARTIFACT"-*-all.jar; do
    if [ -e "$jar" ]; then
      rm -f "$jar"
      say "Removed $jar"
      removed=yes
    fi
  done
  # What the CLI's update check left there.
  rm -f "$install_dir/latest-version" "$install_dir/update-checked-at" "$install_dir/installation-v1" "$install_dir/update.lock"
  if [ -d "$install_dir" ]; then
    rmdir "$install_dir" 2>/dev/null || true
  fi
  if [ "$removed" = no ]; then
    say "Nothing to remove."
  fi
  exit 0
fi

# ---------------------------------------------------------------- downloader

if command -v curl >/dev/null 2>&1; then
  fetch() { curl -fsSL --retry 2 "$1" -o "$2"; }
  fetch_stdout() { curl -fsSL --retry 2 "$1"; }
elif command -v wget >/dev/null 2>&1; then
  fetch() { wget -qO "$2" "$1"; }
  fetch_stdout() { wget -qO- "$1"; }
else
  die "neither curl nor wget is available, so nothing can be downloaded."
fi

# ---------------------------------------------------------------- version

if [ -z "$version" ]; then
  say "Asking Maven Central for the newest BootUI CLI release..."
  metadata="$(fetch_stdout "$repo/$GROUP_PATH/$ARTIFACT/maven-metadata.xml")" ||
    die "could not read the repository metadata at
  $repo/$GROUP_PATH/$ARTIFACT/maven-metadata.xml
  Check your network, or pass --version."
  flat="$(printf '%s' "$metadata" | tr -d ' \011\012\015')"
  version="$(printf '%s' "$flat" | sed -n 's:.*<release>\([^<]*\)</release>.*:\1:p')"
  if [ -z "$version" ]; then
    version="$(printf '%s' "$flat" | sed -n 's:.*<latest>\([^<]*\)</latest>.*:\1:p')"
  fi
  if [ -z "$version" ]; then
    die "no released version is listed in the repository metadata."
  fi
fi

# The version becomes part of a URL and of a file name, so hold it to what a version can be.
case "$version" in
  "" | .* | -* | *[!0-9A-Za-z.+-]*) die "'$version' is not a usable version number." ;;
esac

jar_name="$ARTIFACT-$version-all.jar"
jar_url="$repo/$GROUP_PATH/$ARTIFACT/$version/$jar_name"

# ---------------------------------------------------------------- download

tmp_dir="$(mktemp -d 2>/dev/null || mktemp -d -t bootui)" || die "could not create a temporary directory."
tmp_jar="$tmp_dir/$jar_name"

say "Downloading BootUI CLI $version..."
fetch "$jar_url" "$tmp_jar" || die "could not download
  $jar_url
  If $version is the version you meant, it may not be published yet."

# ---------------------------------------------------------------- verify

hash_of() {
  # $1 = 512, 256 or 1; $2 = file. Prints the lowercase hex digest.
  if command -v "sha$1sum" >/dev/null 2>&1; then
    digest="$("sha$1sum" "$2")" || return 1
    printf '%s\n' "$digest" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    digest="$(shasum -a "$1" "$2")" || return 1
    printf '%s\n' "$digest" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    digest="$(openssl dgst "-sha$1" "$2")" || return 1
    printf '%s\n' "$digest" | sed 's/.*= *//'
  else
    return 1
  fi
}

lower() {
  tr 'ABCDEF' 'abcdef'
}

verified=no
for algo in 512 256 1; do
  published="$(fetch_stdout "$jar_url.sha$algo" 2>/dev/null)" || continue
  # A Maven checksum file holds a bare digest, sometimes followed by a file name.
  expected="$(printf '%s\n' "$published" | awk 'NR==1{print $1}' | tr -d '\015' | lower)"
  case "$algo" in
    512) length=128 ;;
    256) length=64 ;;
    1) length=40 ;;
  esac
  case "$expected" in
    *[!0-9a-f]* | "") die "malformed published SHA-$algo checksum. Nothing was installed." ;;
  esac
  [ "${#expected}" -eq "$length" ] || die "malformed published SHA-$algo checksum. Nothing was installed."
  actual="$(hash_of "$algo" "$tmp_jar")" || continue
  actual="$(printf '%s' "$actual" | lower)"
  if [ "$actual" != "$expected" ]; then
    die "the download does not match the SHA-$algo checksum published beside it.
  expected $expected
  got      $actual
  Nothing was installed."
  fi
  say "Verification: published SHA-$algo checksum matched (repository integrity, not signer authentication)."
  if [ "$algo" = 1 ]; then
    say "Warning: this older release supplies only the legacy SHA-1 checksum."
  fi
  verified=yes
  break
done
if [ "$verified" = no ]; then
  [ "$allow_unverified" = yes ] || die "no checksum could be verified. Nothing was installed. Use --allow-unverified only after independent verification."
  say "Warning: checksum verification was explicitly bypassed with --allow-unverified."
fi

if command -v gpg >/dev/null 2>&1; then
  keyring_alias_dir="$(mktemp -d /tmp/bootui-install-gpg.XXXXXXXX)" || die "could not create a private keyring."
  keyring="$keyring_alias_dir/keyring"
  mkdir -m 700 "$tmp_dir/keyring"
  # Keep the short socket alias inside its original owner-only directory.
  ln -s "$tmp_dir/keyring" "$keyring"
  fetch "$RELEASE_KEY_URL" "$tmp_dir/release-key.asc" || die "could not download the pinned release key."
  fetch "$jar_url.asc" "$tmp_dir/cli.asc" || die "could not download the release signature."
  gpg --homedir "$keyring" --batch --import "$tmp_dir/release-key.asc" >/dev/null 2>&1 ||
    die "could not import the release key."
  verification="$(gpg --homedir "$keyring" --batch --status-fd 1 --verify "$tmp_dir/cli.asc" "$tmp_jar" 2>/dev/null)" ||
    die "invalid release signature. Nothing was installed."
  printf '%s\n' "$verification" | grep -Eq "^\[GNUPG:\] VALIDSIG .* ${RELEASE_KEY_FINGERPRINT}\$" ||
    die "release signature is not from the pinned BootUI key. Nothing was installed."
  say "Verification: OpenPGP signature matched pinned BootUI key $RELEASE_KEY_FINGERPRINT."
else
  say "Verification: signer not authenticated (GnuPG unavailable)."
fi

# ---------------------------------------------------------------- install

mkdir -p "$install_dir" || die "could not create $install_dir"
mkdir -p "$bin_dir" || die "could not create $bin_dir"
install_dir="$(cd "$install_dir" && pwd -P)"
bin_dir="$(cd "$bin_dir" && pwd -P)"

target_jar="$install_dir/$jar_name"
launcher="$bin_dir/bootui"
# Stage beside the live launcher so publishing it is a same-filesystem rename.
tmp_launcher="$(mktemp "$bin_dir/.bootui-XXXXXX")" || die "could not stage the launcher in $bin_dir"
cat > "$tmp_launcher" <<LAUNCHER
#!/bin/sh
# Generated by the BootUI CLI installer. Re-run the installer to update.
set -eu
JAR="$target_jar"
# Where the CLI keeps what its update check found. Only needed because this
# install chose a directory; the CLI defaults to the same place on its own.
BOOTUI_INSTALL_DIR="\${BOOTUI_INSTALL_DIR:-$install_dir}"
export BOOTUI_INSTALL_DIR
BOOTUI_MAVEN_REPO="\${BOOTUI_MAVEN_REPO:-$repo}"
export BOOTUI_MAVEN_REPO
if [ -n "\${JAVA_HOME:-}" ] && [ -x "\$JAVA_HOME/bin/java" ]; then
  JAVA="\$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA=java
else
  echo "bootui: no Java runtime found. BootUI needs a JDK 17 or later." >&2
  exit 1
fi
exec "\$JAVA" -jar "\$JAR" "\$@"
LAUNCHER
chmod 755 "$tmp_launcher" || die "could not make $launcher executable"

# mv treats a directory destination as a container, not a replacement.
[ ! -d "$target_jar" ] || die "could not write $target_jar (it is a directory)"
[ ! -d "$launcher" ] || die "could not replace $launcher (it is a directory)"
mv -f "$tmp_jar" "$target_jar" || die "could not write $target_jar"
chmod 644 "$target_jar" 2>/dev/null || true
mv -f "$tmp_launcher" "$launcher" || die "could not replace $launcher"
tmp_launcher=""

record="$(mktemp "$install_dir/.installation-XXXXXX")" || die "could not record installation ownership"
printf 'bootui-install-v1\n%s\n%s\n%s\n' "$target_jar" "$launcher" "$repo" > "$record"
mv -f "$record" "$install_dir/installation-v1" || die "could not record installation ownership"

# Only prune previous versions once the new launcher and ownership record are in place.
for jar in "$install_dir/$ARTIFACT"-*-all.jar; do
  if [ -e "$jar" ] && [ "$jar" != "$target_jar" ]; then
    rm -f "$jar"
    say "Removed the previous $(basename "$jar")"
  fi
done

say "Installed $jar_name in $install_dir"
say "Installed the bootui launcher at $launcher"

# ---------------------------------------------------------------- report

# The jar cannot run without a JDK 17, but someone installing tooling may be about
# to install one, so say it plainly rather than refuse to finish.
if command -v java >/dev/null 2>&1; then
  java_major="$(java -version 2>&1 | head -n 1 | sed -n 's/.*version "\{0,1\}\([0-9][0-9]*\).*/\1/p')"
  if [ -n "$java_major" ] && [ "$java_major" -lt 17 ]; then
    say ""
    say "Warning: the java on your PATH is version $java_major. BootUI needs 17 or later."
  fi
else
  say ""
  say "Warning: no java was found on your PATH. BootUI needs a JDK 17 or later to run."
fi

case ":${PATH:-}:" in
  *":$bin_dir:"*) ;;
  *)
    say ""
    say "$bin_dir is not on your PATH. To use the command in this shell:"
    say ""
    say "    export PATH=\"$bin_dir:\$PATH\""
    ;;
esac

say ""
say "Done. Try it against a running application:"
say ""
say "    bootui --url http://localhost:8080 tools"
