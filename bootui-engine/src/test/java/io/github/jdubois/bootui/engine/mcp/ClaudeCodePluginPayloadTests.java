package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Claude Code plugin under {@code plugins/bootui} ships a copy of the canonical {@code
 * skills/bootui} skill, because a plugin marketplace installs a directory and cannot follow a
 * pointer out of the plugin root. Pointing the plugin at the repository root instead would copy the
 * whole monorepo into every user's plugin cache, once per version, so the copy is deliberate. These
 * tests are what stops the two copies drifting.
 */
class ClaudeCodePluginPayloadTests {

    @Test
    void shippedSkillMatchesTheCanonicalSkill() throws IOException {
        String canonical = Files.readString(repositoryFile("skills/bootui/SKILL.md"));
        String shipped = Files.readString(repositoryFile("plugins/bootui/skills/bootui/SKILL.md"));

        assertThat(shipped)
                .as("plugins/bootui/skills/bootui/SKILL.md is generated from skills/bootui/SKILL.md; "
                        + "copy the canonical file over it rather than editing it in place")
                .isEqualTo(canonical);
    }

    @Test
    void pluginShipsOnlyTheUserFacingSkill() throws IOException {
        Path shippedSkills = repositoryFile("plugins/bootui/skills");

        try (var entries = Files.list(shippedSkills)) {
            assertThat(entries)
                    .as("the plugin ships the bootui skill only; maintainer skills such as "
                            + ".github/skills/bootui-java-development must not reach users")
                    .extracting(path -> path.getFileName().toString())
                    .containsExactly("bootui");
        }
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
