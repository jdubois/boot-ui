package io.github.jdubois.bootui.engine.staticsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticSecurityAnalysisDetectorTests {

    @TempDir
    Path tempDir;

    private final StaticSecurityAnalysisDetector detector = new StaticSecurityAnalysisDetector();

    @Test
    void reportsUnconfiguredOnlyWhenAReadableBuildFileExists() throws IOException {
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_APPLICABLE);

        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);
    }

    @Test
    void recognizesMavenPluginManagementProfilesAndFindSecBugsDependency() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <build><pluginManagement><plugins><plugin>
                    <groupId>com.github.spotbugs</groupId>
                    <artifactId>spotbugs-maven-plugin</artifactId>
                  </plugin></plugins></pluginManagement></build>
                </project>
                """);

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void treatsAnEmptyMavenRelativePathAsNoLocalParentToInspect() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>4.1.2</version>
                    <relativePath/>
                  </parent>
                  <groupId>example</groupId>
                  <artifactId>application</artifactId>
                  <version>1.0.0</version>
                  <properties><java.version>17</java.version></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-webmvc</artifactId></dependency>
                    <dependency><groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
                  </dependencies>
                </project>
                """);

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);
    }

    @Test
    void recognizesMavenSonarAndLocalParentDeclarations() throws IOException {
        Path parent = tempDir.resolve("pom.xml");
        Path module = Files.createDirectories(tempDir.resolve("module"));
        Files.createFile(tempDir.resolve(".git"));
        Files.writeString(parent, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>parent</artifactId>
                  <version>1</version>
                  <build><plugins><plugin>
                    <groupId>org.sonarsource.scanner.maven</groupId>
                    <artifactId>sonar-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """);
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>test</groupId><artifactId>parent</artifactId><version>1</version></parent>
                </project>
                """);

        assertThat(detector.inspect(module).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void recognizesMavenParentPomInsideRelativePathDirectory() throws IOException {
        Path parent = Files.createDirectories(tempDir.resolve("parent"));
        Path module = Files.createDirectories(tempDir.resolve("module"));
        Files.createFile(tempDir.resolve(".git"));
        Files.writeString(parent.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>parent</artifactId>
                  <version>1</version>
                  <build><plugins><plugin>
                    <groupId>com.github.spotbugs</groupId>
                    <artifactId>spotbugs-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """);
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>test</groupId><artifactId>parent</artifactId><version>1</version>
                    <relativePath>../parent</relativePath>
                  </parent>
                </project>
                """);

        assertThat(detector.inspect(module).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void followsMavenParentsThatInheritGroupAndVersion() throws IOException {
        Path rootPom = tempDir.resolve("pom.xml");
        Path parentDirectory = Files.createDirectories(tempDir.resolve("parent"));
        Path module = Files.createDirectories(tempDir.resolve("parent/app"));
        Files.createFile(tempDir.resolve(".git"));
        Files.writeString(rootPom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>root</artifactId>
                  <version>1</version>
                </project>
                """);
        Files.writeString(parentDirectory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>test</groupId>
                    <artifactId>root</artifactId>
                    <version>1</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>parent</artifactId>
                </project>
                """);
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                  </parent>
                </project>
                """);

        assertThat(detector.inspect(module).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(rootPom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>root</artifactId>
                  <version>1</version>
                  <build><plugins><plugin>
                    <groupId>com.github.spotbugs</groupId>
                    <artifactId>spotbugs-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(detector.inspect(module).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void reportsAnUninspectedDefaultParentOutsideTheRepositoryAsIndeterminate() throws IOException {
        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        Path externalPom = tempDir.resolve("pom.xml");
        Files.writeString(externalPom, "<project><artifactId>unrelated</artifactId></project>");
        Files.writeString(repository.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>external</groupId>
                    <artifactId>parent</artifactId>
                    <version>${revision}</version>
                  </parent>
                  <groupId>test</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                </project>
                """);

        assertThat(detector.inspect(repository).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Files.createFile(repository.resolve(".git"));
        assertThat(detector.inspect(repository).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Files.delete(externalPom);
        assertThat(detector.inspect(repository).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);
    }

    @Test
    void doesNotInspectAnUnrelatedPomAsTheDeclaredMavenParent() throws IOException {
        Files.createFile(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>aggregator</artifactId>
                  <version>1</version>
                  <build><plugins><plugin><artifactId>spotbugs-maven-plugin</artifactId></plugin></plugins></build>
                </project>
                """);
        Path module = Files.createDirectories(tempDir.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>test</groupId><artifactId>different-parent</artifactId><version>1</version></parent>
                </project>
                """);

        assertThat(detector.inspect(module).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);
    }

    @Test
    void recognizesGradlePluginsVersionCatalogAliasesAndKnownConfigFiles() throws IOException {
        Files.createFile(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins { alias(libs.plugins.securityScan) }");
        Path catalog = Files.createDirectories(tempDir.resolve("gradle")).resolve("libs.versions.toml");
        Files.writeString(catalog, """
                [plugins]
                securityScan = { id = "com.github.spotbugs", version = "6.0.0" }
                """);

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins { id(\"java\") }");
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins { alias(libs.plugins.securityScan) }");
        Files.writeString(catalog, """
                [plugins]
                securityScan = "com.github.spotbugs:6.0.0"
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void recognizesKotlinApplyPluginSyntax() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "apply(plugin = \"com.github.spotbugs\")");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void recognizesGroovyNamedArgumentPluginApplication() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "apply(plugin: 'com.github.spotbugs')");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void marksUninspectedGradleBuildLogicAsIndeterminate() throws IOException {
        Path appliedScript = Files.createDirectories(tempDir.resolve("applied-script"));
        Files.writeString(
                appliedScript.resolve("build.gradle"),
                "plugins { id 'java' }\napply from: \"$rootDir/gradle/sonar.gradle\"");
        assertThat(detector.inspect(appliedScript).state())
                .isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Path conventionBuild = Files.createDirectories(tempDir.resolve("convention-build"));
        Files.createDirectories(conventionBuild.resolve("buildSrc"));
        Files.writeString(conventionBuild.resolve("build.gradle"), "plugins { id 'java' }");
        assertThat(detector.inspect(conventionBuild).state())
                .isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Path includedBuild = Files.createDirectories(tempDir.resolve("included-build"));
        Files.writeString(includedBuild.resolve("build.gradle.kts"), "plugins { id(\"java\") }");
        Files.writeString(includedBuild.resolve("settings.gradle.kts"), "includeBuild(\"build-logic\")");
        assertThat(detector.inspect(includedBuild).state())
                .isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Path customCatalog = Files.createDirectories(tempDir.resolve("custom-catalog"));
        Files.writeString(customCatalog.resolve("build.gradle.kts"), "plugins { alias(buildLibs.plugins.spotbugs) }");
        assertThat(detector.inspect(customCatalog).state())
                .isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);
    }

    @Test
    void doesNotTreatSpotBugsAnnotationsAsAnAnalyzerPlugin() throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                "dependencies { implementation 'com.github.spotbugs:spotbugs-annotations:4.9.4' }");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'com.github.spotbugs' version '6.0.0' }");
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void stripsGradleCommentsWithoutTruncatingUrlsOrSlowingOnUnterminatedComments() throws IOException {
        Files.createFile(tempDir.resolve(".git"));
        Path buildFile = tempDir.resolve("build.gradle.kts");
        Files.writeString(buildFile, """
                val repository = "https://repo.example/"
                // This is only a comment.
                /* This comment mentions id("com.github.spotbugs"). */
                plugins { id("org.sonarqube") }
                """);

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(buildFile, """
                val repository = "https://repo.example/"
                // id("com.github.spotbugs")
                /* id("org.sonarqube")
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);
    }

    @Test
    void ignoresAnalyzerExamplesInsideGradleStringsAndCatalogComments() throws IOException {
        Files.createFile(tempDir.resolve(".git"));
        Path buildFile = tempDir.resolve("build.gradle.kts");
        Files.writeString(
                buildFile,
                "val example = \"\"\"plugins { id(\"org.sonarqube\") }\"\"\"\n"
                        + "val anotherExample = \"id(\\\"com.github.spotbugs\\\")\"\n");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(buildFile, "plugins { alias(libs.plugins.securityScan) }");
        Path catalog = Files.createDirectories(tempDir.resolve("gradle")).resolve("libs.versions.toml");
        Files.writeString(catalog, """
                [plugins]
                securityScan = { id = "java-library", version = "1.0" } # id = "com.github.spotbugs"
                """);

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);
    }

    @Test
    void checksGradlePluginStringContextInBoundedTime() throws IOException {
        String contents = "plugins { id(\"java\") }\n" + "val item = \"value\"\n".repeat(50_000);
        Files.writeString(tempDir.resolve("build.gradle.kts"), contents);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(detector.inspect(tempDir).state())
                    .isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);
        });
    }

    @Test
    void recognizesSemgrepAndSonarProjectConfiguration() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve(".semgrep.yml"), "rules: []");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.delete(tempDir.resolve(".semgrep.yml"));
        Files.writeString(tempDir.resolve("sonar-project.properties"), "sonar.projectKey=sample");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void recognizesRepositoryLevelCodeQlWorkflow() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.createFile(tempDir.resolve(".git"));
        Path workflows = Files.createDirectories(tempDir.resolve(".github/workflows"));
        Files.writeString(workflows.resolve("codeql.yml"), "uses: github/codeql-action/init@v3");

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void recognizesAnalyzerWorkflowsWithoutMatchingNamesOrComments() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.createFile(tempDir.resolve(".git"));
        Path workflows = Files.createDirectories(tempDir.resolve(".github/workflows"));
        Path workflow = workflows.resolve("analysis.yml");
        Files.writeString(workflow, """
                name: Run CodeQL checklist
                jobs:
                  scan:
                    steps:
                      - name: Mention Semgrep and SpotBugs
                        run: echo "CodeQL is not configured"
                      - run: echo "semgrep scan"
                      - run: printf 'sonar-scanner is not installed'
                """);

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - uses: github/codeql-action/upload-sarif@v4
                      - uses: github/codeql-action/autobuild@v4
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - run: ./gradlew build -x spotbugsMain
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(workflow, """
                name: Analysis
                jobs:
                  scan:
                    steps:
                      - name: Scan
                        run: |
                          ./mvnw verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                name: Analysis
                jobs:
                  scan:
                    steps:
                      - uses: SonarSource/sonarcloud-github-action@v3
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                name: Analysis
                jobs:
                  scan:
                    steps:
                      - run: echo setup && sonar-scanner
                      - run: semgrep ci
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - run: "semgrep --config auto ."
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - run: 'semgrep -f rules.yml .'
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - run: ./gradlew :app:spotbugsMain
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - run: |
                          ./mvnw -B verify \\
                            sonar:sonar
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(workflow, """
                jobs:
                  scan:
                    steps:
                      - run: >
                          ./mvnw -B verify
                          sonar:sonar
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void continuesPastEmptyOversizedAndSymlinkedFilesWhenAnalyzerIsDeclaredElsewhere() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.writeString(tempDir.resolve(".semgrep.yml"), "");
        Path workflows = Files.createDirectories(tempDir.resolve(".github/workflows"));
        Files.writeString(workflows.resolve("codeql.yml"), "uses: github/codeql-action/init@v3");
        Files.writeString(tempDir.resolve("outside.yml"), "name: ignored");
        Files.createSymbolicLink(workflows.resolve("untrusted.yml"), tempDir.resolve("outside.yml"));

        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);

        Files.writeString(tempDir.resolve(".semgrep.yml"), "x".repeat(1_048_577));
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.CONFIGURED);
    }

    @Test
    void treatsWorkflowDirectoriesSymlinkedOutsideTheRepositoryAsIndeterminate() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path externalWorkflows = Files.createDirectories(tempDir.resolve("external/.github/workflows"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Files.createFile(project.resolve(".git"));
        Files.writeString(externalWorkflows.resolve("codeql.yml"), "uses: github/codeql-action/init@v3");
        Files.createSymbolicLink(project.resolve(".github"), externalWorkflows.getParent());

        assertThat(detector.inspect(project).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);
    }

    @Test
    void treatsMalformedXmlUnresolvableLocalParentsAndOversizedFilesAsIndeterminate() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, "<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project/>");
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version></parent>
                </project>
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version>
                    <relativePath>../missing/pom.xml</relativePath>
                  </parent>
                </project>
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);

        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>external</groupId><artifactId>parent</artifactId><version>1</version>
                    <relativePath/>
                  </parent>
                </project>
                """);
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.NOT_CONFIGURED);

        Files.writeString(pom, "x".repeat(1_048_577));
        assertThat(detector.inspect(tempDir).state()).isEqualTo(StaticSecurityAnalysisDetector.State.INDETERMINATE);
    }

    @Test
    void staticOnlyAdvisorDoesNotClaimRuntimeEvidenceOrScanOnRead() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        var inspections = new java.util.concurrent.atomic.AtomicInteger();
        StaticSecurityAdvisorService service = new StaticSecurityAdvisorService(
                () -> {
                    inspections.incrementAndGet();
                    return tempDir;
                },
                java.time.Clock.systemUTC());

        assertThat(service.report().scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(service.report().results()).isEmpty();
        assertThat(inspections).hasValue(0);

        var report = service.scan();
        assertThat(inspections).hasValue(1);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.rulesEvaluated()).isEqualTo(1);
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(StaticSecurityAnalysisDetector.RULE_ID);
            assertThat(result.severity()).isEqualTo("INFO");
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.recommendation())
                    .contains(
                            "For Maven",
                            "com.github.spotbugs:spotbugs-maven-plugin",
                            "com.h3xstream.findsecbugs:findsecbugs-plugin",
                            "./mvnw spotbugs:spotbugs",
                            "For Gradle",
                            "./gradlew spotbugsMain");
            assertThat(result.description())
                    .contains("External Maven parents", "Gradle applied scripts", "organization-managed CI");
        });
        assertThat(service.ruleViolations(
                                StaticSecurityAnalysisDetector.RULE_ID,
                                report.violationDetails().scanId(),
                                0,
                                null)
                        .violationCount())
                .isOne();

        Files.writeString(tempDir.resolve("sonar-project.properties"), "sonar.projectKey=example");
        assertThat(service.report()).isEqualTo(report);
        assertThat(inspections).hasValue(1);
        assertThat(service.scan().results()).isEmpty();
        assertThat(inspections).hasValue(2);
    }

    @Test
    void staticOnlyAdvisorReportsProjectRootInspectionFailure() {
        StaticSecurityAdvisorService service = new StaticSecurityAdvisorService(
                () -> {
                    throw new IllegalStateException("project root unavailable");
                },
                java.time.Clock.systemUTC());

        var report = service.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).contains("could not be assessed");
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.evidence().limitations())
                .contains("Static analyzer configuration could not be assessed from bounded local files.");
        assertThat(report.results()).isEmpty();
    }
}
