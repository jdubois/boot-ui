package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertMatchingSkillTrees(
                RepositoryFiles.file("skills/bootui"), RepositoryFiles.file("plugins/bootui/skills/bootui"));
    }

    @Test
    void treeGuardRejectsMissingExtraAndChangedReferences(@TempDir Path directory) throws IOException {
        Path canonical = Files.createDirectories(directory.resolve("canonical"));
        Path shipped = Files.createDirectories(directory.resolve("shipped"));
        Files.writeString(canonical.resolve("SKILL.md"), "skill");
        Files.writeString(shipped.resolve("SKILL.md"), "skill");
        assertMatchingSkillTrees(canonical, shipped);

        Files.writeString(canonical.resolve("reference.md"), "guide");
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertMatchingSkillTrees(canonical, shipped));
        Files.writeString(shipped.resolve("reference.md"), "guide");
        assertMatchingSkillTrees(canonical, shipped);

        Files.writeString(shipped.resolve("stale.md"), "old guide");
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertMatchingSkillTrees(canonical, shipped));
        Files.delete(shipped.resolve("stale.md"));
        Files.writeString(shipped.resolve("reference.md"), "changed");
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertMatchingSkillTrees(canonical, shipped));
    }

    @Test
    void shippedReferencesMatchTheCanonicalReferences() throws IOException {
        Path canonical = RepositoryFiles.file("skills/bootui/references");
        Path shipped = RepositoryFiles.file("plugins/bootui/skills/bootui/references");
        try (var canonicalFiles = Files.walk(canonical);
                var shippedFiles = Files.walk(shipped)) {
            var references = canonicalFiles
                    .filter(Files::isRegularFile)
                    .map(canonical::relativize)
                    .sorted()
                    .toList();
            assertThat(shippedFiles
                            .filter(Files::isRegularFile)
                            .map(shipped::relativize)
                            .sorted()
                            .toList())
                    .containsExactlyElementsOf(references);
            for (Path reference : references) {
                assertThat(Files.readString(shipped.resolve(reference)))
                        .as("plugin reference %s must match the canonical skill", reference)
                        .isEqualTo(Files.readString(canonical.resolve(reference)));
            }
        }
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

    private static void assertMatchingSkillTrees(Path canonical, Path shipped) throws IOException {
        List<Path> canonicalFiles = skillFiles(canonical);
        assertThat(skillFiles(shipped))
                .as("the plugin must ship the entire canonical skill tree, without missing or stale references")
                .containsExactlyElementsOf(canonicalFiles);
        for (Path relative : canonicalFiles) {
            assertThat(Files.readAllBytes(shipped.resolve(relative)))
                    .as(
                            "plugin file %s must match skills/bootui; copy the canonical tree, not individual edits",
                            relative)
                    .isEqualTo(Files.readAllBytes(canonical.resolve(relative)));
        }
    }

    private static List<Path> skillFiles(Path root) throws IOException {
        try (var entries = Files.walk(root)) {
            return entries.peek(path -> assertThat(Files.isSymbolicLink(path))
                            .as("a self-contained skill must not depend on symlinks: %s", path)
                            .isFalse())
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .sorted()
                    .toList();
        }
    }
}
