package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SecurityDocumentationTests {

    private static final Pattern SERVLET_RULE_HEADING = Pattern.compile("(?m)^### (SEC-(?!RXF-)[A-Z0-9-]+) - ");
    private static final Pattern NEXT_HEADING = Pattern.compile("(?m)^#{2,3} ");
    private static final Pattern RETIRED_SECTION = Pattern.compile("(?m)^\\*\\*Retired\\.\\*\\*\\s+");

    @Test
    void servletCatalogDocumentsEveryActiveRuleWithMatchingTitleAndSeverity() throws IOException {
        String documentation = Files.readString(securityChecksDocumentation());
        Set<String> documentedRuleIds = new LinkedHashSet<>();
        Set<String> documentedRetiredHeadingIds = new LinkedHashSet<>();
        Matcher headings = SERVLET_RULE_HEADING.matcher(documentation);
        while (headings.find()) {
            String ruleId = headings.group(1);
            String section = sectionAt(documentation, headings.start(), headings.end());
            if (RETIRED_SECTION.matcher(section).find()) {
                assertThat(section)
                        .as("retired rule section for %s must not declare an active severity bullet", ruleId)
                        .doesNotContain("- **Severity**:");
                documentedRetiredHeadingIds.add(ruleId);
            } else {
                documentedRuleIds.add(ruleId);
            }
        }

        Set<String> activeRuleIds = new LinkedHashSet<>();
        for (SecurityRule rule : SecurityRuleRegistry.activeRules()) {
            SecurityRuleDefinition definition = rule.definition();
            activeRuleIds.add(definition.id());

            String heading = "### " + definition.id() + " - ";
            int sectionStart = documentation.indexOf(heading);
            assertThat(sectionStart)
                    .as("documentation heading for %s", definition.id())
                    .isNotNegative();
            String section = sectionAt(documentation, sectionStart, sectionStart + heading.length());
            assertThat(section)
                    .as("documented title for %s", definition.id())
                    .startsWith(heading + definition.name() + "\n");
            assertThat(section)
                    .as("documented severity for %s", definition.id())
                    .contains("- **Severity**: " + definition.severity());
        }

        assertThat(documentedRuleIds).containsExactlyInAnyOrderElementsOf(activeRuleIds);
        assertThat(documentedRetiredHeadingIds)
                .containsExactlyInAnyOrder("SEC-SESSION-007", "SEC-HEAD-010", "SEC-ACT-004", "SEC-CONFIG-005")
                .isSubsetOf(SecurityRuleRegistry.RETIRED_RULE_IDS)
                .doesNotContainAnyElementsOf(activeRuleIds);
    }

    private static String sectionAt(String documentation, int sectionStart, int searchFrom) {
        Matcher next = NEXT_HEADING.matcher(documentation);
        int sectionEnd = next.find(searchFrom) ? next.start() : documentation.length();
        return documentation.substring(sectionStart, sectionEnd);
    }

    private static Path securityChecksDocumentation() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : new Path[] {
            workingDirectory.resolve("docs/SECURITY-CHECKS.md"),
            workingDirectory.resolve("../docs/SECURITY-CHECKS.md").normalize()
        }) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/SECURITY-CHECKS.md could not be located from " + workingDirectory);
    }
}
