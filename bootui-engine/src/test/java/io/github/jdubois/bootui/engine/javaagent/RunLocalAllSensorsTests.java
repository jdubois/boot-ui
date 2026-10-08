package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each sample's run-local-all.sh promises every sensor the agent ships; none may fall behind the catalog. */
class RunLocalAllSensorsTests {

    private static final Pattern DEFAULT_SENSORS = Pattern.compile("BOOTUI_AGENT_SENSORS:-([a-z,-]+)}");

    @ParameterizedTest
    @ValueSource(
            strings = {"bootui-spring-sample-app", "bootui-spring-webflux-sample-app", "bootui-quarkus-sample-app"})
    void runLocalAllAsksForEverySensorTheAgentShips(String sample) throws IOException {
        String script = Files.readString(repositoryRoot().resolve(sample + "/run-local-all.sh"));
        Matcher matcher = DEFAULT_SENSORS.matcher(script);
        assertThat(matcher.find())
                .as(sample + "/run-local-all.sh sets a default BOOTUI_AGENT_SENSORS")
                .isTrue();
        assertThat(Arrays.asList(matcher.group(1).split(",")))
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(AgentSensorSettings.KNOWN_SENSORS);
    }

    private static Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate :
                new Path[] {workingDirectory, workingDirectory.resolve("..").normalize()}) {
            if (Files.isRegularFile(candidate.resolve("bootui-spring-sample-app/run-local-all.sh"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("the repository root could not be located from " + workingDirectory);
    }
}
