#!/usr/bin/env bash
#
# Consumer smoke tests for one BootUI version, run from standalone projects outside the reactor that resolve
# BootUI exactly as a consumer does.
#
# Usage: consumer-smoke-tests.sh <version> [staged-repository-directory]
#
# Without a staged repository, every BootUI artifact must come from Maven Central: release.yml runs this after
# publication. With one, every BootUI artifact must come from that file repository, a release candidate staged by
# stage-release-candidate.sh: release.yml runs this before the release tag is created and again before
# publication, and build.yml on every change to the published shape, so a broken consumer POM fails before a Maven
# Central coordinate is consumed. Either way, the origin of every resolved BootUI file is checked, so a candidate
# version that also exists on Maven Central cannot pass by downloading the published one.
#
# Environment:
#   SMOKE_LOCAL_REPO       Maven local repository the consumers resolve into (default: ~/.m2/repository). The
#                          BootUI group is removed from it first, so pass a dedicated directory locally.
#   SMOKE_MVC_PORT         HTTP port of the Spring MVC consumer (default: 8080).
#   SMOKE_WEBFLUX_PORT     HTTP port of the Spring WebFlux consumer (default: 8081).
#   SMOKE_TIMEOUT_SECONDS  How long a Spring consumer may take to serve BootUI (default: 300).

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf 'Usage: %s <version> [staged-repository-directory]\n' "$0" >&2
  exit 2
fi

readonly VERSION="$1"
REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly MVNW="$REPOSITORY_ROOT/mvnw"
readonly LOCAL_REPO="${SMOKE_LOCAL_REPO:-$HOME/.m2/repository}"
readonly MVC_PORT="${SMOKE_MVC_PORT:-8080}"
readonly WEBFLUX_PORT="${SMOKE_WEBFLUX_PORT:-8081}"
readonly BOOTUI_SMOKE_TIMEOUT="${SMOKE_TIMEOUT_SECONDS:-300}"

# The seven published coordinates. Everything a consumer resolves from com.julien-dubois.bootui must be one of
# them, and together the consumers below resolve all of them. Keep this list in step with release.yml.
readonly PUBLISHED_ARTIFACTS=(
  bootui-engine
  bootui-ui
  bootui-spring-boot-starter
  bootui-quarkus
  bootui-quarkus-deployment
  bootui-cli
  bootui-agent
)

sha1_hex() {
  if command -v sha1sum >/dev/null 2>&1; then sha1sum; else shasum -a 1; fi | cut -d ' ' -f 1
}

if [[ $# -eq 2 ]]; then
  STAGED_REPOSITORY="$(cd "$2" && pwd)"
  if [[ ! -d "$STAGED_REPOSITORY/com/julien-dubois/bootui" ]]; then
    printf 'No staged BootUI artifacts in %s\n' "$STAGED_REPOSITORY" >&2
    exit 2
  fi
  readonly EXPECTED_ORIGIN="bootui-staged"
  readonly EXPECTED_ORIGIN_URL="file://${STAGED_REPOSITORY}"
  readonly SOURCE_LABEL="the staged release candidate in $STAGED_REPOSITORY"
else
  STAGED_REPOSITORY=""
  readonly EXPECTED_ORIGIN="central"
  readonly EXPECTED_ORIGIN_URL="https://repo.maven.apache.org/maven2"
  readonly SOURCE_LABEL="Maven Central"
fi
readonly STAGED_REPOSITORY
# Maven records where it downloaded a file from in _remote.repositories: Maven Resolver 1 (Maven 3.9) as the
# repository id, Maven Resolver 2 (Maven 3.10 and later) as the id, a hyphen, and the SHA-1 of the repository URL.
EXPECTED_ORIGIN_KEY="${EXPECTED_ORIGIN}-$(printf '%s' "$EXPECTED_ORIGIN_URL" | sha1_hex)"
readonly EXPECTED_ORIGIN_KEY

# Whether this file in the local repository was downloaded from the source under test, in either format. Both name
# exactly that repository, never another one or a locally installed file, whose origin is empty.
resolved_from_source_under_test() {
  local name
  name="$(basename "$1")"
  grep -Fxq -e "${name}>${EXPECTED_ORIGIN}=" -e "${name}>${EXPECTED_ORIGIN_KEY}=" \
    "$(dirname "$1")/_remote.repositories" 2>/dev/null
}

WORK_DIR="$(mktemp -d)"
readonly WORK_DIR
readonly SETTINGS="$WORK_DIR/settings.xml"

# A settings file of its own, so a mirror or repository in the developer's settings can neither redirect nor
# supply BootUI. In staged mode, the candidate is the first repository Maven searches.
if [[ -n "$STAGED_REPOSITORY" ]]; then
  cat > "$SETTINGS" <<SETTINGS_XML
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <localRepository>${LOCAL_REPO}</localRepository>
  <profiles>
    <profile>
      <id>bootui-staged</id>
      <repositories>
        <repository>
          <id>bootui-staged</id>
          <url>file://${STAGED_REPOSITORY}</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>bootui-staged</activeProfile>
  </activeProfiles>
</settings>
SETTINGS_XML
else
  cat > "$SETTINGS" <<SETTINGS_XML
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <localRepository>${LOCAL_REPO}</localRepository>
</settings>
SETTINGS_XML
fi

# MAVEN_OPTS is cleared so an inherited -Dmaven.repo.local cannot point the consumers at another repository.
consumer_mvn() {
  MAVEN_OPTS="" "$MVNW" -B -ntp -s "$SETTINGS" -Dmaven.repo.local="$LOCAL_REPO" "$@"
}

project_property() {
  "$MVNW" -B -ntp -q -N -f "$REPOSITORY_ROOT/pom.xml" -DforceStdout help:evaluate -Dexpression="$1" | tail -n 1
}

SPRING_BOOT_VERSION="$(project_property spring-boot.version)"
QUARKUS_VERSION="$(project_property quarkus.platform.version)"
DEPENDENCY_PLUGIN_VERSION="$(project_property maven-dependency-plugin.version)"
readonly SPRING_BOOT_VERSION QUARKUS_VERSION DEPENDENCY_PLUGIN_VERSION
readonly DEPENDENCY_PLUGIN="org.apache.maven.plugins:maven-dependency-plugin:${DEPENDENCY_PLUGIN_VERSION}"

echo "Smoke-testing BootUI ${VERSION} from ${SOURCE_LABEL}."
echo "Distributions: Spring MVC and Spring WebFlux (bootui-spring-boot-starter), CLI and its client, Quarkus extension, Java agent."

# Drop every locally built or installed BootUI artifact, so each consumer below resolves it from the source
# under test, exactly what a consumer downloads. Never re-install between the tests.
rm -rf "$LOCAL_REPO/com/julien-dubois/bootui"

# True when something accepts connections on the local port.
port_open() {
  (echo > "/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# APP_PID is the consumer application's own JVM, started with java -jar, so stopping it stops the application:
# nothing is left holding its port for the next run of this script in the same release job.
APP_PID=""
APP_PORT=""
stop_app() {
  if [[ -n "$APP_PID" ]]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
  if [[ -n "$APP_PORT" ]]; then
    local deadline=$(( SECONDS + 60 ))
    while port_open "$APP_PORT"; do
      if (( SECONDS > deadline )); then
        echo "::error::Port ${APP_PORT} is still in use after its smoke application stopped."
        return 1
      fi
      sleep 1
    done
    APP_PORT=""
  fi
}
trap stop_app EXIT

# Usage: wait_for_bootui <label> <base-url> <log-file>
wait_for_bootui() {
  local label="$1" base_url="$2" log="$3"
  local body="" ready="" deadline=$(( SECONDS + BOOTUI_SMOKE_TIMEOUT ))
  while (( SECONDS <= deadline )); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "::error::${label}: app exited before BootUI became available at ${base_url}."
      cat "$log"; return 1
    fi
    body="$(curl -fsSL -H 'X-Forwarded-For: 127.0.0.1' "$base_url" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q 'id="app"'; then ready=1; break; fi
    sleep 5
  done
  if [[ -z "$ready" ]]; then
    echo "::error::${label}: BootUI did not serve its UI at ${base_url} within the timeout."
    cat "$log"; return 1
  fi
  if ! printf '%s' "$body" | grep -q '<title>BootUI</title>'; then
    echo "::error::${label}: BootUI index.html at ${base_url} is missing <title>BootUI</title>."
    printf '%s\n' "$body"; return 1
  fi
  local asset
  asset="$(printf '%s' "$body" | grep -oE 'assets/[A-Za-z0-9._-]+\.js' | head -n 1 || true)"
  if [[ -z "$asset" ]]; then
    echo "::error::${label}: No bundled JS asset reference found in BootUI index.html."
    printf '%s\n' "$body"; return 1
  fi
  curl -fsSL -o /dev/null -H 'X-Forwarded-For: 127.0.0.1' "${base_url%/}/${asset}"
  echo "${label}: BootUI served its index and bundled assets."
}

# Usage: create_spring_smoke_project <dir> <web-starter> <port>
# The application declares its own web starter, as every Spring Boot web application does; the BootUI starter
# brings none. SmokeApplication prints the web application type Spring Boot deduced and the server it started.
create_spring_smoke_project() {
  local project_dir="$1" web_starter="$2" port="$3"
  mkdir -p "$project_dir/src/main/java/smoke" "$project_dir/src/main/resources"
  cat > "$project_dir/pom.xml" <<SPRING_POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>${SPRING_BOOT_VERSION}</version>
  </parent>
  <groupId>smoke</groupId>
  <artifactId>${web_starter}-consumer-smoke</artifactId>
  <version>1</version>
  <properties>
    <java.version>17</java.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>${web_starter}</artifactId>
    </dependency>
    <dependency>
      <groupId>com.julien-dubois.bootui</groupId>
      <artifactId>bootui-spring-boot-starter</artifactId>
      <version>${VERSION}</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
SPRING_POM
  cat > "$project_dir/src/main/java/smoke/SmokeApplication.java" <<'SPRING_APPLICATION'
package smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SmokeApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SmokeApplication.class);
        ConfigurableApplicationContext context = application.run(args);
        System.out.println("BOOTUI_SMOKE web-application-type=" + application.getWebApplicationType()
                + " web-server=" + ((WebServerApplicationContext) context).getWebServer().getClass().getName());
    }
}
SPRING_APPLICATION
  cat > "$project_dir/src/main/resources/application.properties" <<SPRING_PROPERTIES
bootui.enabled=ON
server.port=${port}
SPRING_PROPERTIES
}

# Usage: runtime_classpath <project-dir>  (prints groupId:artifactId of every runtime dependency)
runtime_classpath() {
  local project_dir="$1" listing="$1/runtime-classpath.txt"
  consumer_mvn -q -f "$project_dir/pom.xml" "${DEPENDENCY_PLUGIN}:list" \
    -DincludeScope=runtime -DoutputFile="$listing" -DoutputAbsoluteArtifactFilename=false >/dev/null
  if ! grep -qE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[a-z-]+:' "$listing"; then
    echo "::error::No runtime dependency was listed for ${project_dir}:" >&2
    cat "$listing" >&2
    return 1
  fi
  grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[a-z-]+:' "$listing" | cut -d: -f1,2 | sort -u
}

# Usage: forbid_on_classpath <label> <classpath> <groupId:artifactId>...
forbid_on_classpath() {
  local label="$1" classpath="$2"; shift 2
  local forbidden found=""
  for forbidden in "$@"; do
    if grep -Fxq -- "$forbidden" <<<"$classpath"; then
      echo "::error::${label}: ${forbidden} is on the runtime classpath; bootui-spring-boot-starter must not bring a web stack."
      found=1
    fi
  done
  [[ -z "$found" ]]
}

# Usage: run_spring_smoke <label> <dir> <port> <expected-type> <expected-server-class>
# Builds the consumer's executable jar, as an application ships, and runs it.
run_spring_smoke() {
  local label="$1" project_dir="$2" port="$3" expected_type="$4" expected_server="$5"
  local log="$project_dir/app.log"
  if port_open "$port"; then
    echo "::error::${label}: port ${port} is already in use; set SMOKE_MVC_PORT or SMOKE_WEBFLUX_PORT to a free port."
    return 1
  fi
  consumer_mvn -q -f "$project_dir/pom.xml" -Dmaven.test.skip=true package
  local app_jar
  app_jar="$(ls "$project_dir"/target/*-consumer-smoke-1.jar)"
  java -jar "$app_jar" > "$log" 2>&1 &
  APP_PID=$!
  APP_PORT="$port"
  wait_for_bootui "$label" "http://localhost:${port}/bootui/" "$log"
  # The line is printed once SpringApplication.run returns, after the runners, which can follow the UI by a moment.
  local expected="BOOTUI_SMOKE web-application-type=${expected_type} web-server=${expected_server}"
  local deadline=$(( SECONDS + 120 ))
  until grep -Fq -- 'BOOTUI_SMOKE' "$log"; do
    if (( SECONDS > deadline )) || ! kill -0 "$APP_PID" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if ! grep -Fq -- "$expected" "$log"; then
    echo "::error::${label}: expected '${expected}', but the application reported:"
    grep -F 'BOOTUI_SMOKE' "$log" || cat "$log"
    return 1
  fi
  echo "${label}: ${expected_type} application on ${expected_server##*.}."
}

# ── 1/5: Spring MVC — bootui-spring-boot-starter with spring-boot-starter-web ──────────────────────────────
echo "=== Smoke test 1/5: Spring MVC (bootui-spring-boot-starter + spring-boot-starter-web) ==="
MVC_SMOKE_DIR="$WORK_DIR/spring-mvc"
create_spring_smoke_project "$MVC_SMOKE_DIR" "spring-boot-starter-web" "$MVC_PORT"
MVC_CLASSPATH="$(runtime_classpath "$MVC_SMOKE_DIR")"
forbid_on_classpath "Spring MVC" "$MVC_CLASSPATH" \
  org.springframework:spring-webflux \
  io.projectreactor.netty:reactor-netty-http \
  org.springframework.boot:spring-boot-starter-webflux \
  org.springframework.boot:spring-boot-reactor-netty
run_spring_smoke "Spring MVC" "$MVC_SMOKE_DIR" "$MVC_PORT" SERVLET org.springframework.boot.tomcat.TomcatWebServer

# ── 2/5: CLI and its client, against the running Spring MVC application ──────────────────────────────────────
# The runnable CLI is the shaded "all" classifier (JBang's alias and the documented download). The plain
# bootui-cli jar is the dependency-free client library: a consumer depending on it must get no dependency,
# picocli included, and must be able to talk to BootUI with it.
echo "=== Smoke test 2/5: CLI (bootui-cli:all) and its dependency-free client (bootui-cli) ==="
CLI_SMOKE_DIR="$WORK_DIR/cli"
mkdir -p "$CLI_SMOKE_DIR/src/main/java/smoke"
cat > "$CLI_SMOKE_DIR/pom.xml" <<CLI_POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>smoke</groupId>
  <artifactId>bootui-cli-consumer-smoke</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>com.julien-dubois.bootui</groupId>
      <artifactId>bootui-cli</artifactId>
      <version>${VERSION}</version>
    </dependency>
  </dependencies>
</project>
CLI_POM
cat > "$CLI_SMOKE_DIR/src/main/java/smoke/ClientSmoke.java" <<'CLIENT_SMOKE'
package smoke;

import io.github.jdubois.bootui.client.BootUiCatalog;
import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import java.time.Duration;

public class ClientSmoke {

    public static void main(String[] args) {
        BootUiClientOptions options = new BootUiClientOptions(
                args[0], BootUiClientOptions.DEFAULT_API_PATH, null, Duration.ofSeconds(60));
        try (BootUiClient client = new BootUiClient(options)) {
            BootUiCatalog catalog = client.catalog();
            if (!catalog.enabled() || catalog.tools().isEmpty()) {
                throw new IllegalStateException("BootUI advertised no command-line tools: " + catalog);
            }
            System.out.println("BOOTUI_CLIENT_SMOKE tools=" + catalog.tools().size());
        }
    }
}
CLIENT_SMOKE
CLI_CLASSPATH="$(runtime_classpath "$CLI_SMOKE_DIR")"
if [[ "$CLI_CLASSPATH" != "com.julien-dubois.bootui:bootui-cli" ]]; then
  echo "::error::The published bootui-cli ${VERSION} POM must give a client consumer no dependency (picocli is optional), but its runtime classpath resolved:"
  printf '%s\n' "$CLI_CLASSPATH"
  exit 1
fi
consumer_mvn -q -f "$CLI_SMOKE_DIR/pom.xml" compile "${DEPENDENCY_PLUGIN}:copy-dependencies" \
  -DincludeScope=runtime -DoutputDirectory="$CLI_SMOKE_DIR/lib"
CLIENT_OUTPUT="$(java -cp "$CLI_SMOKE_DIR/target/classes:$CLI_SMOKE_DIR/lib/bootui-cli-${VERSION}.jar" \
  smoke.ClientSmoke "http://localhost:${MVC_PORT}")"
printf '%s\n' "$CLIENT_OUTPUT"
if ! grep -q '^BOOTUI_CLIENT_SMOKE tools=[1-9]' <<<"$CLIENT_OUTPUT"; then
  echo "::error::The bootui-cli ${VERSION} client did not read the application's tool catalog."
  exit 1
fi
if java -cp "$CLI_SMOKE_DIR/lib/bootui-cli-${VERSION}.jar" io.github.jdubois.bootui.cli.BootUiCli --version \
  >/dev/null 2>&1; then
  echo "::error::The thin bootui-cli ${VERSION} jar ran the command line on its own; picocli must not be in it."
  exit 1
fi
consumer_mvn -q -f "$CLI_SMOKE_DIR/pom.xml" "${DEPENDENCY_PLUGIN}:copy" \
  -Dartifact="com.julien-dubois.bootui:bootui-cli:${VERSION}:jar:all" -DoutputDirectory="$CLI_SMOKE_DIR/all"
CLI_ALL_JAR="$CLI_SMOKE_DIR/all/bootui-cli-${VERSION}-all.jar"
export BOOTUI_NO_UPDATE_CHECK=1
CLI_VERSION_OUTPUT="$(java -jar "$CLI_ALL_JAR" --version)"
if [[ "$CLI_VERSION_OUTPUT" != "bootui ${VERSION}" ]]; then
  echo "::error::java -jar bootui-cli-${VERSION}-all.jar --version printed '${CLI_VERSION_OUTPUT}', expected 'bootui ${VERSION}'."
  exit 1
fi
java -jar "$CLI_ALL_JAR" --help >/dev/null
CLI_TOOLS_OUTPUT="$(java -jar "$CLI_ALL_JAR" --url "http://localhost:${MVC_PORT}" --json tools)"
if [[ -z "$CLI_TOOLS_OUTPUT" ]] || ! grep -q '"tools"' <<<"$CLI_TOOLS_OUTPUT"; then
  echo "::error::java -jar bootui-cli-${VERSION}-all.jar tools did not list the application's tools:"
  printf '%s\n' "$CLI_TOOLS_OUTPUT"
  exit 1
fi
echo "CLI smoke test passed: the all jar runs, and the client works without picocli."
stop_app
echo "Spring MVC smoke test passed."

# ── 3/5: Spring WebFlux — bootui-spring-boot-starter with spring-boot-starter-webflux ──────────────────────
# The same starter as above. The application must stay REACTIVE on Netty: before 2.0, a servlet starter on a
# WebFlux application made Spring Boot pick SERVLET.
echo "=== Smoke test 3/5: Spring WebFlux (bootui-spring-boot-starter + spring-boot-starter-webflux) ==="
WEBFLUX_SMOKE_DIR="$WORK_DIR/spring-webflux"
create_spring_smoke_project "$WEBFLUX_SMOKE_DIR" "spring-boot-starter-webflux" "$WEBFLUX_PORT"
WEBFLUX_CLASSPATH="$(runtime_classpath "$WEBFLUX_SMOKE_DIR")"
forbid_on_classpath "Spring WebFlux" "$WEBFLUX_CLASSPATH" \
  jakarta.servlet:jakarta.servlet-api \
  org.apache.tomcat.embed:tomcat-embed-core \
  org.springframework:spring-webmvc \
  org.springframework.boot:spring-boot-starter-web \
  org.springframework.boot:spring-boot-starter-tomcat \
  org.springframework.boot:spring-boot-tomcat
run_spring_smoke "Spring WebFlux" "$WEBFLUX_SMOKE_DIR" "$WEBFLUX_PORT" REACTIVE org.springframework.boot.reactor.netty.NettyWebServer
stop_app
echo "Spring WebFlux smoke test passed."

# ── 4/5: Quarkus — bootui-quarkus + bootui-quarkus-deployment ───────────────────────────────────────────────
# Declaring the runtime artifact is enough: Quarkus build-time augmentation resolves bootui-quarkus-deployment
# through the extension's quarkus-extension.properties, so both coordinates are exercised. A clean augmentation
# is the bar; no HTTP server is started.
echo "=== Smoke test 4/5: Quarkus extension (bootui-quarkus + bootui-quarkus-deployment) ==="
QUARKUS_SMOKE_DIR="$WORK_DIR/quarkus"
mkdir -p "$QUARKUS_SMOKE_DIR/src/main/java"
cat > "$QUARKUS_SMOKE_DIR/pom.xml" <<QUARKUS_POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>smoke</groupId>
  <artifactId>bootui-quarkus-consumer-smoke</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-bom</artifactId>
        <version>${QUARKUS_VERSION}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.julien-dubois.bootui</groupId>
      <artifactId>bootui-quarkus</artifactId>
      <version>${VERSION}</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
        <version>${QUARKUS_VERSION}</version>
        <extensions>true</extensions>
        <executions>
          <execution>
            <goals>
              <goal>build</goal>
              <goal>generate-code</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
QUARKUS_POM
consumer_mvn -f "$QUARKUS_SMOKE_DIR/pom.xml" package -Dmaven.test.skip=true
echo "Quarkus extension smoke test passed."

# ── 5/5: Java agent — bootui-agent ──────────────────────────────────────────────────────────────────────────
# The published POM must give a consumer no dependency: the bridge and Byte Buddy are shaded in and declared
# optional, and bootui-agent-bridge is never published, so the runtime classpath must hold the agent jar alone.
# The JVM then starts with it as its -javaagent, and the agent must report itself attached and dormant.
echo "=== Smoke test 5/5: Java agent (bootui-agent) ==="
AGENT_SMOKE_DIR="$WORK_DIR/agent"
mkdir -p "$AGENT_SMOKE_DIR"
cat > "$AGENT_SMOKE_DIR/pom.xml" <<AGENT_POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>smoke</groupId>
  <artifactId>bootui-agent-consumer-smoke</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>com.julien-dubois.bootui</groupId>
      <artifactId>bootui-agent</artifactId>
      <version>${VERSION}</version>
    </dependency>
  </dependencies>
</project>
AGENT_POM
consumer_mvn -f "$AGENT_SMOKE_DIR/pom.xml" \
  "${DEPENDENCY_PLUGIN}:copy-dependencies" \
  -DincludeScope=runtime \
  -DoutputDirectory="$AGENT_SMOKE_DIR/lib"
AGENT_JAR="$AGENT_SMOKE_DIR/lib/bootui-agent-${VERSION}.jar"
AGENT_CLASSPATH="$(ls -1 "$AGENT_SMOKE_DIR/lib")"
if [[ "$AGENT_CLASSPATH" != "bootui-agent-${VERSION}.jar" ]]; then
  echo "::error::The published bootui-agent ${VERSION} POM must give a consumer no compile or runtime dependency, but its runtime classpath resolved:"
  printf '%s\n' "$AGENT_CLASSPATH"
  exit 1
fi
AGENT_STDERR="$AGENT_SMOKE_DIR/stderr.txt"
# java -version prints to stderr, which also carries the agent's dormant line and, on HotSpot, a class-data-sharing
# warning because the agent appends itself to the bootstrap class path.
if ! java -javaagent:"$AGENT_JAR" -version 2> "$AGENT_STDERR"; then
  echo "::error::A JVM started with -javaagent:bootui-agent-${VERSION}.jar exited with a failure."
  cat "$AGENT_STDERR"
  exit 1
fi
cat "$AGENT_STDERR"
AGENT_DORMANT_LINE="[BootUI agent] BootUI agent ${VERSION} attached (javaagent); dormant until BootUI claims it"
if ! grep -Fxq -- "$AGENT_DORMANT_LINE" "$AGENT_STDERR"; then
  echo "::error::bootui-agent ${VERSION} did not report itself attached and dormant; expected: ${AGENT_DORMANT_LINE}"
  exit 1
fi
echo "Java agent smoke test passed."

# ── Where BootUI came from ──────────────────────────────────────────────────────────────────────────────────
# Together, the consumers above resolved every published coordinate, and only those: a parent POM, the agent
# bridge, or a pre-2.0 coordinate here means a consumer POM still points at an artifact that is not published.
# Each file must have been downloaded from the source under test (resolved_from_source_under_test).
BOOTUI_LOCAL="$LOCAL_REPO/com/julien-dubois/bootui"
RESOLVED_ARTIFACTS="$(find "$BOOTUI_LOCAL" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)"
EXPECTED_ARTIFACTS="$(printf '%s\n' "${PUBLISHED_ARTIFACTS[@]}" | sort)"
if [[ "$RESOLVED_ARTIFACTS" != "$EXPECTED_ARTIFACTS" ]]; then
  echo "::error::The consumers resolved these BootUI artifacts:"
  printf '%s\n' "$RESOLVED_ARTIFACTS"
  echo "::error::expected exactly the published coordinates:"
  printf '%s\n' "$EXPECTED_ARTIFACTS"
  exit 1
fi
origin_errors=0
for artifact in "${PUBLISHED_ARTIFACTS[@]}"; do
  artifact_dir="$BOOTUI_LOCAL/$artifact/$VERSION"
  for file in "$artifact_dir"/*.pom "$artifact_dir"/*.jar; do
    [[ -e "$file" ]] || continue
    if ! resolved_from_source_under_test "$file"; then
      echo "::error::$(basename "$file") was not resolved from ${SOURCE_LABEL} (origin '${EXPECTED_ORIGIN}' or '${EXPECTED_ORIGIN_KEY}'):"
      cat "$artifact_dir/_remote.repositories" 2>/dev/null || echo "(no _remote.repositories)"
      origin_errors=$((origin_errors + 1))
    fi
  done
  if [[ ! -e "$artifact_dir/$artifact-$VERSION.pom" ]]; then
    echo "::error::No $artifact $VERSION POM was resolved."
    origin_errors=$((origin_errors + 1))
  fi
done
if (( origin_errors > 0 )); then
  exit 1
fi

rm -rf "$WORK_DIR"
echo "All BootUI ${VERSION} distribution smoke tests passed from ${SOURCE_LABEL} (Spring MVC, CLI and client, Spring WebFlux, Quarkus, Java agent)."
