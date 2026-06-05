package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single, scanner-produced finding that the "Fix it with Copilot" action can act on.
 *
 * <p>This is a browser-safe, sanitized descriptor: it carries only the metadata needed to
 * build a remediation prompt (rule/advisory id, human title, remediation summary, severity and
 * the affected dependency coordinates or source files). It never carries secrets, raw command
 * output or diffs.
 *
 * @param findingId stable identifier of the finding (e.g. an OSV id such as {@code GHSA-xxxx} or
 *     a Security Advisor rule id such as {@code SEC-001})
 * @param source the scanner that produced the finding (e.g. {@code vulnerabilities})
 * @param title short human-readable title shown on the finding card
 * @param summary remediation hint / advisory details used to seed the agent prompt
 * @param severity normalized severity label (e.g. {@code CRITICAL}, {@code HIGH})
 * @param targets affected dependency coordinates (e.g. {@code group:artifact:version}) or source
 *     file paths the fix should focus on
 */
public record CopilotFixDescriptorDto(
        String findingId,
        String source,
        String title,
        String summary,
        String severity,
        List<String> targets) {

    public CopilotFixDescriptorDto {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
