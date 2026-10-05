#!/usr/bin/env bash
#
# Stages a BootUI release candidate as a local Maven file repository, from the working tree.
#
# Usage: stage-release-candidate.sh <output-directory>
#
# Builds the publication-only reactor exactly as release.yml publishes it, unsigned, with central-publishing-maven-plugin
# uploading to a local stand-in instead of the Central Portal: the plugin writes the bundle it uploads, already
# filtered by the root POM's excludeArtifacts, to target/central-publishing/central-bundle.zip.
# The bundle is a Maven repository layout, unzipped into <output-directory> and checked by check-central-bundle.py
# (the published coordinates and the flattened, parentless POMs). consumer-smoke-tests.sh <version>
# <output-directory> then runs the consumer smoke tests against it, before anything reaches Maven Central.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <output-directory>\n' "$0" >&2
  exit 2
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly OUTPUT="$1"
readonly BUNDLE="$REPOSITORY_ROOT/target/central-publishing/central-bundle.zip"

cd "$REPOSITORY_ROOT"
VERSION="$(./mvnw -B -ntp -q -N -DforceStdout help:evaluate -Dexpression=project.version | tail -n 1)"
readonly VERSION

rm -f "$BUNDLE"
WORK="$(mktemp -d)"
STUB_PID=""
cleanup() {
  if [[ -n "$STUB_PID" ]]; then
    kill "$STUB_PID" 2>/dev/null || true
    wait "$STUB_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# central-publishing-maven-plugin writes the bundle immediately before uploading it, and its skipPublishing switch
# skips both. So the plugin uploads to a stand-in for the Central Portal's upload endpoint on the loopback interface
# instead, which accepts the bundle and answers with a deployment id; -DwaitUntil=uploaded stops the plugin there.
# Nothing leaves this machine, and the placeholder credentials below could not authenticate anywhere.
python3 - "$WORK/stub-port" "$WORK/uploads" <<'STUB' &
import http.server
import sys

port_file, uploads_file = sys.argv[1], sys.argv[2]


class Upload(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            while True:
                size = int(self.rfile.readline().split(b";")[0], 16)
                self.rfile.read(size + 2)
                if size == 0:
                    break
        else:
            self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with open(uploads_file, "a", encoding="utf-8") as uploads:
            uploads.write(self.path + "\n")
        body = b"bootui-staged-candidate"
        self.send_response(201)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.send_error(404)

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Upload)
with open(port_file, "w", encoding="utf-8") as port:
    port.write(str(server.server_address[1]))
server.serve_forever()
STUB
STUB_PID=$!
for _ in $(seq 1 50); do
  [[ -s "$WORK/stub-port" ]] && break
  sleep 0.1
done
if [[ ! -s "$WORK/stub-port" ]]; then
  printf 'The local upload stand-in did not start\n' >&2
  exit 1
fi
STUB_URL="http://127.0.0.1:$(cat "$WORK/stub-port")"

SETTINGS="$WORK/settings.xml"
cat > "$SETTINGS" <<'SETTINGS_XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>central</id>
      <username>staging-only</username>
      <password>staging-only</password>
    </server>
  </servers>
</settings>
SETTINGS_XML

# The same reactor as release.yml's "Publish to Maven Central" step; check-release-integrity.sh keeps them equal.
./mvnw -B -ntp -s "$SETTINGS" -Prelease clean deploy \
  -pl .,bootui-core,bootui-engine,bootui-spring-boot-starter,bootui-ui,bootui-quarkus-parent,bootui-quarkus,bootui-quarkus-deployment,bootui-cli,bootui-agent-bridge,bootui-agent \
  -am \
  -DskipTests \
  -Dgpg.skip=true \
  -DcentralBaseUrl="$STUB_URL" \
  -Dcentral.autoPublish=false \
  -DwaitUntil=uploaded

if [[ "$(wc -l < "$WORK/uploads" 2>/dev/null || echo 0)" -ne 1 ]]; then
  printf 'Expected exactly one bundle upload to the local stand-in\n' >&2
  exit 1
fi

if [[ ! -s "$BUNDLE" ]]; then
  printf 'The Central bundle was not written to %s\n' "$BUNDLE" >&2
  exit 1
fi

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"
unzip -q "$BUNDLE" -d "$OUTPUT"
python3 "$REPOSITORY_ROOT/.github/scripts/check-central-bundle.py" "$OUTPUT" "$VERSION"
printf 'Staged BootUI %s release candidate in %s\n' "$VERSION" "$OUTPUT"
