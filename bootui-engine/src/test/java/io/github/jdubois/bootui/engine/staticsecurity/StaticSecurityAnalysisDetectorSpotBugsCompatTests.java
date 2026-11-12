package io.github.jdubois.bootui.engine.staticsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticSecurityAnalysisDetectorSpotBugsCompatTests {

    private final StaticSecurityAnalysisDetector detector = new StaticSecurityAnalysisDetector();

    @TempDir
    Path tempDir;

    @Test
    void flagsPropertyResolvedSpotBugsVersionTooOldForJava25() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <properties>
                <java.version>25</java.version>
                <spotbugs-maven-plugin.version>4.9.3.0</spotbugs-maven-plugin.version>
              </properties>
              <build><plugins><plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>${spotbugs-maven-plugin.version}</version>
              </plugin></plugins></build>
            </project>
            """);
        Optional<StaticSecurityAnalysisDetector.SpotBugsCompatibility> result =
                detector.inspectSpotBugsCompatibility(tempDir);
        assertThat(result).isPresent();
        assertThat(result.get().declaredCore()).isEqualTo("4.9.3");
        assertThat(result.get().minimumCore()).isEqualTo("4.9.4");
        assertThat(result.get().javaRelease()).isEqualTo(25);
    }

    @Test
    void doesNotFlagCurrentSpotBugsOnJava25() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <properties><maven.compiler.release>25</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.10.5.0</version>
              </plugin></plugins></build>
            </project>
            """);
        assertThat(detector.inspectSpotBugsCompatibility(tempDir)).isEmpty();
    }

    @Test
    void doesNotFlagWhenNoJavaReleaseDeclared() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <build><plugins><plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.9.3.0</version>
              </plugin></plugins></build>
            </project>
            """);
        assertThat(detector.inspectSpotBugsCompatibility(tempDir)).isEmpty();
    }

    @Test
    void doesNotFlagOlderJavaRelease() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <properties><java.version>17</java.version></properties>
              <build><plugins><plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.8.6.0</version>
              </plugin></plugins></build>
            </project>
            """);
        assertThat(detector.inspectSpotBugsCompatibility(tempDir)).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoSpotBugsDeclared() throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><properties><java.version>25</java.version></properties></project>");
        assertThat(detector.inspectSpotBugsCompatibility(tempDir)).isEmpty();
    }

    @Test
    void advisorScanReportsSecStatic002ForAnIncompatibleSpotBugsVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <properties><java.version>25</java.version></properties>
              <build><plugins><plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.9.3.0</version>
              </plugin></plugins></build>
            </project>
            """);
        StaticSecurityAdvisorService service =
                new StaticSecurityAdvisorService(() -> tempDir, java.time.Clock.systemUTC());

        var report = service.scan();

        assertThat(report.results()).anySatisfy(result -> {
            assertThat(result.id()).isEqualTo(SpotBugsAnalyzerVersionRule.RULE_ID);
            assertThat(result.severity()).isEqualTo("MEDIUM");
            assertThat(result.recommendation()).contains("4.9.4.0");
        });
    }

    @Test
    void advisorScanHasNoSecStatic002ForACompatibleSpotBugsVersion() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <properties><java.version>25</java.version></properties>
              <build><plugins><plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>4.10.5.0</version>
              </plugin></plugins></build>
            </project>
            """);
        StaticSecurityAdvisorService service =
                new StaticSecurityAdvisorService(() -> tempDir, java.time.Clock.systemUTC());

        assertThat(service.scan().results())
                .noneMatch(result -> result.id().equals(SpotBugsAnalyzerVersionRule.RULE_ID));
    }
}
