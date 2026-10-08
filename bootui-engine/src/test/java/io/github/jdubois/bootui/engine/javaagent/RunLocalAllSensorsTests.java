package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The sample's run-local-all.sh promises every sensor the agent ships; it must not fall behind the catalog. */
class RunLocalAllSensorsTests {

    private static final Pattern DEFAULT_SENSORS = Pattern.compile("BOOTUI_AGENT_SENSORS:-([a-z,-]+)}");

    @Test
    void runLocalAllAsksForEverySensorTheAgentShips() throws IOException {
        String script = Files.readString(repositoryRoot().resolve("bootui-spring-sample-app/run-local-all.sh"));
        Matcher matcher = DEFAULT_SENSORS.matcher(script);
        assertThat(matcher.find())
                .as("run-local-all.sh sets a default BOOTUI_AGENT_SENSORS")
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
