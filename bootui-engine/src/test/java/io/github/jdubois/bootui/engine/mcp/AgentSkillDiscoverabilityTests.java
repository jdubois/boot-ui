package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Users find the BootUI skill through a skill registry and install it with a command copied from the
 * documentation. Nothing else in the build exercises that path, so these tests are its smoke check:
 * the canonical skill stays installable, and every documented command keeps pointing at it by path
 * rather than by a name that a repository-wide search resolves to two identical copies.
 */
class AgentSkillDiscoverabilityTests {

    private static final String CANONICAL_SKILL_PATH = "skills/bootui";

    private static final String CANONICAL_SKILL_URL =
            "https://github.com/jdubois/boot-ui/tree/main/" + CANONICAL_SKILL_PATH;

    /** The Agent Skills frontmatter contract: a slug name, and a description agents can match on. */
    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final int MAXIMUM_DESCRIPTION_LENGTH = 1024;

    private static final Pattern GH_SKILL_COMMAND =
            Pattern.compile("gh skill (?:install|preview) jdubois/boot-ui(?<path>[^\\n`]*)");

    private static final Pattern NPX_SKILLS_COMMAND = Pattern.compile("npx skills add (?<path>[^\\s`]*)");

    private static final Pattern PLUGIN_SOURCE = Pattern.compile("\"source\"\\s*:\\s*\"(?<path>[^\"]+)\"");

    private static final List<String> DOCUMENTED_SURFACES =
            List.of("README.md", "docs/README.md", "docs/AI-AGENTS.md", ".github/DISTRIBUTION.md");

    @Test
    void canonicalSkillDeclaresInstallableFrontmatter() throws IOException {
        Path skill = RepositoryFiles.file(CANONICAL_SKILL_PATH + "/SKILL.md");
        String contents = Files.readString(skill);

        assertThat(contents)
                .as("a skill registry only indexes SKILL.md when it opens with YAML frontmatter")
                .startsWith("---\n")
                .contains("\n---\n");

        String frontmatter = contents.substring(4, contents.indexOf("\n---\n", 4));

        assertThat(frontmatterValue(frontmatter, "name"))
                .as("the frontmatter name must match the directory, otherwise an installed skill lands "
                        + "under a different name than the documented install path")
                .isEqualTo(skill.getParent().getFileName().toString())
                .matches(SKILL_NAME);
        assertThat(frontmatterValue(frontmatter, "description"))
                .as("the description is what an agent matches a user request against")
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(MAXIMUM_DESCRIPTION_LENGTH);
        assertThat(frontmatterValue(frontmatter, "license")).isNotBlank();
    }

    @Test
    void documentedInstallCommandsPointAtTheCanonicalSkill() throws IOException {
        for (String surface : DOCUMENTED_SURFACES) {
            String contents = Files.readString(RepositoryFiles.file(surface));

            assertThat(matches(GH_SKILL_COMMAND, contents))
                    .as(
                            "%s must name the skill by path: a repository-wide search for the bootui name "
                                    + "also matches the copy shipped inside the Claude Code plugin",
                            surface)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).isEqualTo(CANONICAL_SKILL_PATH));
            assertThat(matches(NPX_SKILLS_COMMAND, contents))
                    .as(
                            "%s must promote the portable skills.sh installer and point it at the "
                                    + "canonical skill directory",
                            surface)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).isEqualTo(CANONICAL_SKILL_URL));
        }
    }

    @Test
    void documentedSkillUrlResolvesToTheCanonicalSkill() {
        String repositoryPath = CANONICAL_SKILL_URL.substring(CANONICAL_SKILL_URL.indexOf("/tree/main/") + 11);

        assertThat(RepositoryFiles.root().resolve(repositoryPath).resolve("SKILL.md"))
                .as("the documented skills.sh URL must resolve to a skill that exists in this repository")
                .exists();
    }

    @Test
    void claudeMarketplaceAdvertisesAnInstallablePlugin() throws IOException {
        String manifest = Files.readString(RepositoryFiles.file(".claude-plugin/marketplace.json"));
        List<String> sources = matches(PLUGIN_SOURCE, manifest);

        assertThat(sources)
                .as("the marketplace must advertise at least one plugin")
                .isNotEmpty();
        for (String source : sources) {
            Path plugin = RepositoryFiles.root().resolve(source).normalize();

            assertThat(plugin.resolve(".claude-plugin/plugin.json"))
                    .as("%s is advertised by .claude-plugin/marketplace.json, so it must be a plugin", source)
                    .exists();
            assertThat(plugin.resolve(CANONICAL_SKILL_PATH + "/SKILL.md"))
                    .as(
                            "%s must carry its own copy of the skill, because a plugin cannot reference files "
                                    + "outside its root",
                            source)
                    .exists();
        }
    }

    @Test
    void noUncheckedDocumentCarriesAnInstallCommand() throws IOException {
        Path root = RepositoryFiles.root();
        List<Path> documentedPaths = DOCUMENTED_SURFACES.stream().map(root::resolve).toList();

        try (Stream<Path> markdown = Files.walk(root)) {
            List<Path> unchecked = markdown.filter(AgentSkillDiscoverabilityTests::isSourceMarkdown)
                    .filter(path -> !documentedPaths.contains(path))
                    .filter(AgentSkillDiscoverabilityTests::carriesAnInstallCommand)
                    .map(root::relativize)
                    .toList();

            assertThat(unchecked)
                    .as("these documents carry a skill install command; add them to DOCUMENTED_SURFACES so "
                            + "the canonical path is enforced there too")
                    .isEmpty();
        }
    }

    private static boolean isSourceMarkdown(Path path) {
        if (!path.getFileName().toString().endsWith(".md")) {
            return false;
        }
        for (Path segment : path) {
            String name = segment.toString();
            if (name.equals("target") || name.equals("node_modules") || name.equals("dist") || name.startsWith(".m2")) {
                return false;
            }
        }
        return true;
    }

    private static boolean carriesAnInstallCommand(Path path) {
        try {
            String contents = Files.readString(path);
            return GH_SKILL_COMMAND.matcher(contents).find()
                    || NPX_SKILLS_COMMAND.matcher(contents).find();
        } catch (IOException e) {
            throw new IllegalStateException(path + " could not be read", e);
        }
    }

    private static String frontmatterValue(String frontmatter, String key) {
        Matcher matcher = Pattern.compile("^" + key + ": (?<value>.*)$", Pattern.MULTILINE)
                .matcher(frontmatter);
        assertThat(matcher.find()).as("the skill frontmatter declares %s", key).isTrue();
        return matcher.group("value").trim();
    }

    private static List<String> matches(Pattern pattern, String contents) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(contents);
        while (matcher.find()) {
            values.add(matcher.group("path").trim());
        }
        return values;
    }
}
