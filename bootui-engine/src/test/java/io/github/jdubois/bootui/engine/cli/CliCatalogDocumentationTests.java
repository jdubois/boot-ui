package io.github.jdubois.bootui.engine.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CliToolInfo;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The BootUI skill tells agents which fields {@code bootui tools --json} carries, so they can read availability without
 * calling every tool. An agent that trusts a stale list skips a field that is there, so the list is pinned to the
 * {@link CliToolInfo} record the endpoint serializes.
 */
class CliCatalogDocumentationTests {

    @Test
    void theSkillListsEveryFieldOfEachToolInTheCatalog() throws IOException {
        String skill = Files.readString(repositoryFile("skills/bootui/SKILL.md"));
        int start = skill.indexOf("- `bootui tools` prints");
        assertThat(start).as("the skill's bootui tools bullet").isNotNegative();
        String bullet = skill.substring(start, skill.indexOf("\n- ", start + 1));

        assertThat(Arrays.stream(CliToolInfo.class.getRecordComponents()).map(RecordComponent::getName))
                .allSatisfy(field -> assertThat(bullet).contains("`" + field + "`"));
        assertThat(bullet).doesNotContain("no `status` or `command`");
    }

    private static Path repositoryFile(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : new Path[] {
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("../" + relativePath).normalize()
        }) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(relativePath + " could not be located from " + workingDirectory);
    }
}
