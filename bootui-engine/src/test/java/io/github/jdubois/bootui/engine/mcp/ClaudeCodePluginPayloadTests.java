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
        String canonical = Files.readString(RepositoryFiles.file("skills/bootui/SKILL.md"));
        String shipped = Files.readString(RepositoryFiles.file("plugins/bootui/skills/bootui/SKILL.md"));

        assertThat(shipped)
                .as("plugins/bootui/skills/bootui/SKILL.md is generated from skills/bootui/SKILL.md; "
                        + "copy the canonical file over it rather than editing it in place")
                .isEqualTo(canonical);
    }

    @Test
    void pluginShipsOnlyTheUserFacingSkill() throws IOException {
        Path shippedSkills = RepositoryFiles.file("plugins/bootui/skills");

        try (var entries = Files.list(shippedSkills)) {
            assertThat(entries)
                    .as("the plugin ships the bootui skill only; maintainer skills such as "
                            + ".github/skills/bootui-java-development must not reach users")
                    .extracting(path -> path.getFileName().toString())
                    .containsExactly("bootui");
        }
    }
}
