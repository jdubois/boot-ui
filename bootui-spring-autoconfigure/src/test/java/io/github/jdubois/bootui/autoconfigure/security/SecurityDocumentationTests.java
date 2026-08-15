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

    @Test
    void servletCatalogDocumentsEveryActiveRuleWithMatchingSeverity() throws IOException {
        String documentation = Files.readString(securityChecksDocumentation());
        Set<String> documentedRuleIds = new LinkedHashSet<>();
        Matcher headings = SERVLET_RULE_HEADING.matcher(documentation);
        while (headings.find()) {
            documentedRuleIds.add(headings.group(1));
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
            int nextSection = documentation.indexOf("\n### ", sectionStart + heading.length());
            String section =
                    documentation.substring(sectionStart, nextSection < 0 ? documentation.length() : nextSection);
            assertThat(section)
                    .as("documented severity for %s", definition.id())
                    .contains("- **Severity**: " + definition.severity());
        }

        assertThat(documentedRuleIds).containsExactlyInAnyOrderElementsOf(activeRuleIds);
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
