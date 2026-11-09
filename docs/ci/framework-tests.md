# Framework-managed CI tests

Use this path when `@SpringBootTest` or `@QuarkusTest` owns the application's lifetime. For an application started as a
separate process, the [GitHub Actions quick start](../CI.md) is simpler.

<!-- bootui-ci:release:start -->
> **CI support is not yet published.** Published BootUI CLI 2.3.0 does not contain these CI commands. The examples below are prepared for the first signed CI-capable release; `UNRELEASED` is not an installable version. Do not substitute the old published CLI.
<!-- bootui-ci:release:end -->

## The lifecycle

Prepare the expected test-window plan **before** starting tests. In each expected window, call `CiCollection.begin()`
after the app is ready, execute the real workload, and call `finish()` **before** framework teardown.
Once Maven and its producers have exited, run `check-contexts` as the final gate.

`CiCollection` is a public Java API in the ordinary `bootui-cli` artifact. Add it to the test classpath; use the shaded
`all` jar only for running the CLI. This release does not automatically register JUnit, Spring, or Quarkus hooks.

## Spring MVC and WebFlux

Use the test framework's actual random port, injected through `@LocalServerPort`, not an assumed port 8080.
The complete example uses a fixed expected window ID and finishes collection within the live application's test.
The same pattern applies to MVC and WebFlux; the selected web starter determines the stack.

Use the [starter kit](../CI.md#try-a-complete-sample), select its framework workflow, and keep the scripts and
project together. The Maven configuration includes `bootui-cli` as an ordinary test-scoped dependency only in the
diagnostic profile. It does not add the shaded CLI jar to the test classpath.

::: details Complete Spring Maven configuration

<!-- bootui-ci:source:spring/pom.xml.template:start -->
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>
    <groupId>example</groupId>
    <artifactId>orders-spring</artifactId>
    <version>1.0.0</version>
    <properties>
        <java.version>17</java.version>
        <bootui.version>UNRELEASED</bootui.version>
        <spring.web.starter>spring-boot-starter-web</spring.web.starter>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>${spring.web.starter}</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <profiles>
        <profile>
            <id>webflux</id>
            <properties>
                <spring.web.starter>spring-boot-starter-webflux</spring.web.starter>
            </properties>
        </profile>
        <profile>
            <id>bootui-ci</id>
            <dependencies>
                <dependency>
                    <groupId>com.julien-dubois.bootui</groupId>
                    <artifactId>bootui-spring-boot-starter</artifactId>
                    <version>${bootui.version}</version>
                    <scope>runtime</scope>
                </dependency>
                <dependency>
                    <groupId>com.julien-dubois.bootui</groupId>
                    <artifactId>bootui-cli</artifactId>
                    <version>${bootui.version}</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.6.1</version>
                        <executions>
                            <execution>
                                <id>ci-tests</id>
                                <phase>generate-test-sources</phase>
                                <goals><goal>add-test-source</goal></goals>
                                <configuration>
                                    <sources><source>src/ci/java</source></sources>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <forkCount>1</forkCount>
                    <rerunFailingTestsCount>0</rerunFailingTestsCount>
                    <systemPropertyVariables>
                        <junit.jupiter.execution.parallel.enabled>false</junit.jupiter.execution.parallel.enabled>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```
<!-- bootui-ci:source:spring/pom.xml.template:end -->

:::

::: details Complete Spring lifecycle test

<!-- bootui-ci:source:spring/src/ci/java/example/orders/OrdersLifecycleTest.java:start -->
```java
package example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.jdubois.bootui.cli.ci.CiCollection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrdersLifecycleTest {

    @LocalServerPort
    int port;

    @Test
    void orders() throws Exception {
        exercise("orders", "[\"order-1\",\"order-2\"]");
    }

    @Test
    void catalog() throws Exception {
        exercise("catalog", "[\"book\",\"pen\"]");
    }

    private void exercise(String contextId, String expectedBody) throws Exception {
        String url = "http://127.0.0.1:" + port;
        CiCollection.Window window = begin(contextId, url);
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/" + contextId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(expectedBody, response.body());
            System.out.println("CI_WORKLOAD_COMPLETED=" + contextId);
            assertFalse("true".equals(System.getenv("CI_EXAMPLE_FAIL_WORKLOAD")), "Requested workload failure");
        } finally {
            if (window != null) {
                window.finish();
            }
        }
    }

    private CiCollection.Window begin(String contextId, String url) throws Exception {
        var keys = List.of("BOOTUI_CI_EVIDENCE_ROOT", "BOOTUI_CI_PLAN_SHA256", "BOOTUI_CI_ASSESSMENT_ID");
        long configured =
                keys.stream().filter(key -> System.getenv(key) != null).count();
        if (configured == 0) {
            if ("true".equals(System.getenv("CI")) || "true".equals(System.getenv("GITHUB_ACTIONS"))) {
                throw new IllegalStateException("CI collection requires the prepared BOOTUI_CI_* plan");
            }
            System.out.println("BootUI CI collection is opt-in; running the local workload without collection.");
            return null;
        }
        if (configured != keys.size()
                || keys.stream().anyMatch(key -> System.getenv(key).isBlank())) {
            throw new IllegalStateException("Set all three BOOTUI_CI_* collection values, or none for local tests");
        }
        var window = CiCollection.begin(
                Path.of(System.getenv(keys.get(0))),
                System.getenv(keys.get(1)),
                System.getenv(keys.get(2)),
                contextId,
                url,
                "/bootui/api",
                30);
        if (!window.attached()) {
            System.out.println("BootUI attach refused: " + window.attachFailureCode() + "; workload still runs.");
        }
        return window;
    }
}
```
<!-- bootui-ci:source:spring/src/ci/java/example/orders/OrdersLifecycleTest.java:end -->

:::

## Quarkus

Use `@QuarkusTest` and the actual test URL supplied by `@TestHTTPResource`. Do not assess a production-mode application
or try to attach after QuarkusTest has already stopped it.
The complete example finishes its window inside the test while the application is alive.

The Maven profile enables the extension only for diagnostics, with the ordinary CLI artifact on the test classpath.

::: details Complete Quarkus Maven configuration

<!-- bootui-ci:source:quarkus/pom.xml.template:start -->
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>example</groupId>
    <artifactId>orders-quarkus</artifactId>
    <version>1.0.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.version>3.40.1.1</quarkus.platform.version>
        <bootui.version>UNRELEASED</bootui.version>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <profiles>
        <profile>
            <id>bootui-ci</id>
            <dependencies>
                <dependency>
                    <groupId>com.julien-dubois.bootui</groupId>
                    <artifactId>bootui-quarkus</artifactId>
                    <version>${bootui.version}</version>
                </dependency>
                <dependency>
                    <groupId>com.julien-dubois.bootui</groupId>
                    <artifactId>bootui-cli</artifactId>
                    <version>${bootui.version}</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.6.1</version>
                        <executions>
                            <execution>
                                <id>ci-tests</id>
                                <phase>generate-test-sources</phase>
                                <goals><goal>add-test-source</goal></goals>
                                <configuration>
                                    <sources><source>src/ci/java</source></sources>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
    <build>
        <plugins>
            <plugin>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                            <goal>build</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.16.0</version>
                <configuration><parameters>true</parameters></configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.6.1</version>
                <configuration>
                    <forkCount>1</forkCount>
                    <rerunFailingTestsCount>0</rerunFailingTestsCount>
                    <systemPropertyVariables>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                        <junit.jupiter.execution.parallel.enabled>false</junit.jupiter.execution.parallel.enabled>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```
<!-- bootui-ci:source:quarkus/pom.xml.template:end -->

:::

::: details Complete Quarkus lifecycle test

<!-- bootui-ci:source:quarkus/src/ci/java/example/orders/OrdersLifecycleTest.java:start -->
```java
package example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.jdubois.bootui.cli.ci.CiCollection;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrdersLifecycleTest {

    @TestHTTPResource
    URI application;

    @Test
    void orders() throws Exception {
        exercise("orders", "[\"order-1\",\"order-2\"]");
    }

    @Test
    void catalog() throws Exception {
        exercise("catalog", "[\"book\",\"pen\"]");
    }

    private void exercise(String contextId, String expectedBody) throws Exception {
        String url = "http://127.0.0.1:" + application.getPort();
        CiCollection.Window window = begin(contextId, url);
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/" + contextId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(expectedBody, response.body());
            System.out.println("CI_WORKLOAD_COMPLETED=" + contextId);
            assertFalse("true".equals(System.getenv("CI_EXAMPLE_FAIL_WORKLOAD")), "Requested workload failure");
        } finally {
            if (window != null) {
                window.finish();
            }
        }
    }

    private CiCollection.Window begin(String contextId, String url) throws Exception {
        var keys = List.of("BOOTUI_CI_EVIDENCE_ROOT", "BOOTUI_CI_PLAN_SHA256", "BOOTUI_CI_ASSESSMENT_ID");
        long configured =
                keys.stream().filter(key -> System.getenv(key) != null).count();
        if (configured == 0) {
            if ("true".equals(System.getenv("CI")) || "true".equals(System.getenv("GITHUB_ACTIONS"))) {
                throw new IllegalStateException("CI collection requires the prepared BOOTUI_CI_* plan");
            }
            System.out.println("BootUI CI collection is opt-in; running the local workload without collection.");
            return null;
        }
        if (configured != keys.size()
                || keys.stream().anyMatch(key -> System.getenv(key).isBlank())) {
            throw new IllegalStateException("Set all three BOOTUI_CI_* collection values, or none for local tests");
        }
        var window = CiCollection.begin(
                Path.of(System.getenv(keys.get(0))),
                System.getenv(keys.get(1)),
                System.getenv(keys.get(2)),
                contextId,
                url,
                "/bootui/api",
                30);
        if (!window.attached()) {
            System.out.println("BootUI attach refused: " + window.attachFailureCode() + "; workload still runs.");
        }
        return window;
    }
}
```
<!-- bootui-ci:source:quarkus/src/ci/java/example/orders/OrdersLifecycleTest.java:end -->

:::

## GitHub Actions workflow

From the extracted kit's root, select the framework-managed workflow:

```bash
mkdir -p .github/workflows
cp workflows/framework.yml .github/workflows/bootui.yml
```

Retain the complete kit, including dotfiles, at the repository root. It runs on pushes and pull requests, with an
input-free manual dispatch once it is on your default branch. It runs Spring MVC, Spring WebFlux, and Quarkus
independently. When adapting it,
keep only your stack; the scripts prepare the expected plan, run tests, check the evidence after Maven exits, and
upload completed reports without hiding workload failures.

::: details Complete framework-managed workflow

<!-- bootui-ci:source:workflows/framework.yml.template:start -->
```yaml
name: BootUI framework-managed applications
on:
  push:
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  integration:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        include:
          - project: spring
            stack: mvc
            label: spring-mvc
          - project: spring
            stack: webflux
            label: spring-webflux
          - project: quarkus
            stack: mvc
            label: quarkus
    env:
      BOOTUI_VERSION: 'UNRELEASED'
      BOOTUI_ASSESSMENT_ID: ${{ github.run_id }}:${{ github.run_attempt }}:${{ matrix.label }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@77dd5b2ce225ea11469928ac317850245a74ae78 # v6.2.0
        with:
          distribution: temurin
          java-version: '25'
      - name: Verify the matching published CLI
        uses: jdubois/boot-ui@vUNRELEASED
        with:
          phase: prepare-cli
          bootui-version: 'UNRELEASED'
      - name: Locate source checkout or extracted kit
        run: |
          recipe_dir=examples/ci
          test -f "$recipe_dir/run-framework.py" || recipe_dir=.
          test -f "$recipe_dir/run-framework.py"
          printf 'BOOTUI_EXAMPLE_DIR=%s\n' "$recipe_dir" >> "$GITHUB_ENV"
          printf 'BOOTUI_CI_ROOT=%s\n' "$RUNNER_TEMP/bootui-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-${{ matrix.label }}" >> "$GITHUB_ENV"
      - name: Prepare fixed windows, run tests, and check after teardown
        run: python3 "$BOOTUI_EXAMPLE_DIR/run-framework.py" "${{ matrix.project }}" --stack "${{ matrix.stack }}"
      - name: Upload only bounded sanitized BootUI reports
        if: ${{ !cancelled() && env.BOOTUI_CI_ROOT != '' }}
        uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
        with:
          name: bootui-framework-${{ matrix.label }}
          path: ${{ env.BOOTUI_CI_ROOT }}/artifacts/
          if-no-files-found: error
          retention-days: 7
      - name: Require both ordinary tests and complete assessment success
        if: ${{ !cancelled() }}
        run: python3 "$BOOTUI_EXAMPLE_DIR/check-result.py"
```
<!-- bootui-ci:source:workflows/framework.yml.template:end -->

:::

## What the gate guarantees

The planned IDs are fixed before the workload. A filtered, skipped, crashed, duplicate, or unfinished window cannot
disappear from the denominator. Disable automatic retries for these windows or plan each attempt under its own ID.
An ordinary attach refusal still permits the workload, but the later gate fails. Do not swallow errors that prevent
safe access to the evidence store.

One evidence root holds one exact policy snapshot. Keep its plan digest and assessment ID outside the writable root,
and the evidence itself outside cleanable build output and Quarkus watched paths.
Run the final check only after all producers exit. `!cancelled()` avoids racing cancelled processes; a bounded job
timeout still matters.

The final gate returns `0` for a complete pass, `3` for a complete threshold violation, and `1` for missing, refused,
or incomplete evidence. Errors take precedence over findings.
For a completed assessment it prints the directory containing the per-window reports. A refused/missing window has a
stable error code, not a fabricated passing bundle.

Unlike the live-target Action's `assess` phase, `check-contexts` does not automatically concatenate reports into
GitHub's job summary. The recipe uploads sanitized reports for completed windows; the console gate result also records
missing/refused windows. An upload of report files alone is not the complete plan/claim/completion archive needed to
repeat the collection gate on another machine.

::: details Filesystem support and evidence trust

Collection requires a Java filesystem provider supporting `SecureDirectoryStream`; the examples use Linux runners.
Common Windows providers fail closed. This restriction is specific to collection and its gate, not ordinary live
attachment/assessment or single-bundle offline checking.

Protect the runner and evidence root from concurrent or hostile writers. Secure path handling and hashes validate
consistency; they do not prove which requests exercised the application. Workload attribution remains the
application test harness's responsibility.

:::

See the [policy and Action reference](reference.md) for advisor availability, errors, bounded collection, and optional
report-only Runtime Insights.
