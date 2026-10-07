package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightAgentDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsWindowDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.mcp.McpGuidance;
import io.github.jdubois.bootui.engine.mcp.McpPrompt;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Plan identifiers and the external validation ({@code docs/PLAN-v2.md} M4-20) stay in the plan, the validation report,
 * and code comments: no text a user or an agent reads, in the panel, the API, MCP, or the CLI, may name them.
 */
class UserFacingPlanJargonTests {

    /**
     * The words users must never see: plan identifiers such as M4-20, D36, or §5.17 (an RFC section stays allowed), the
     * plan's documents, and the validation's process language.
     */
    static final Pattern PLAN_JARGON = Pattern.compile("PLAN(-v2)?\\.md|PLAN-v2|V2-VALIDATION|\\bM[0-9]+-[0-9]+\\b"
            + "|\\bD[0-9]{2}\\b|(?<!RFC [0-9]{1,5} )§\\s?[0-9]"
            + "|(?i:validat\\w* (on|against) real applications|external(ly)? validat|validation (application|run)"
            + "|not (yet )?validated|reviewers?\\b|adjudicat|per-kind gate|seeded case|counterexample|judged by"
            + "|not judged yet|too few facts)");

    @Test
    void thePatternCatchesTheOldValidationNotesAndSparesRfcSections() {
        assertThat(PLAN_JARGON
                        .matcher("It found nothing on the seven validation applications (M4-20).")
                        .find())
                .isTrue();
        assertThat(PLAN_JARGON.matcher("Added after the validation run (D36).").find())
                .isTrue();
        assertThat(PLAN_JARGON.matcher("see docs/PLAN-v2.md §5.17").find()).isTrue();
        assertThat(PLAN_JARGON.matcher("Not externally validated").find()).isTrue();
        assertThat(PLAN_JARGON
                        .matcher("Consider a Retry-After header (RFC 9110 §10.2.3) with 429 (RFC 6585 §4).")
                        .find())
                .isFalse();
    }

    @Test
    void everyReasonARowLeftOutOfTheDefaultListGivesIsPlain() {
        Map<String, String> texts = new LinkedHashMap<>();
        ExternalValidation.kinds()
                .forEach((kind, entry) -> texts.put("unlisted reason of " + kind, entry.unlistedReason()));
        texts.put(
                "unlisted reason of a later kind",
                ExternalValidation.of("a-kind-added-later").unlistedReason());
        texts.put("memory rows", DefaultListing.MEMORY);

        assertPlain(texts);
    }

    @Test
    void whatAgentsReadOfEveryKindsRowsIsPlain() {
        List<RuntimeObservationDto> observations = new ArrayList<>();
        List<RuntimeInsightCheckDto> checks = new ArrayList<>();
        List<String> kinds = new ArrayList<>(ExternalValidation.kinds().keySet());
        kinds.add("a-kind-added-later");
        for (int i = 0; i < kinds.size(); i++) {
            String kind = kinds.get(i);
            checks.add(new RuntimeInsightCheckDto(kind, kind, "EVALUATED", 10, 1, null));
            observations.add(observation(i, kind, ExternalValidation.of(kind).unlistedReason()));
        }
        RuntimeInsightsReportDto report = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", 1L, 2L, 100, 12, 0, 0),
                List.of(),
                checks,
                observations,
                List.of(),
                List.of(),
                0);
        Map<String, String> texts = new LinkedHashMap<>();
        for (String query : new String[] {null, "all"}) {
            RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report, query, 50);
            list.limitations()
                    .forEach(limitation -> texts.put("list " + query + " limitation " + texts.size(), limitation));
        }
        for (RuntimeObservationDto observation : observations) {
            RuntimeInsightAgentDetailDto detail = RuntimeInsightsAgentView.detail(
                    new RuntimeObservationDetailDto(true, null, observation, List.of(), List.of(), 0));
            detail.limitations().forEach(limitation -> texts.put(observation.kind() + " " + texts.size(), limitation));
        }

        assertThat(texts).isNotEmpty();
        assertPlain(texts);
    }

    @Test
    void everyMcpDescriptionInstructionAndPromptIsPlain() {
        Map<String, String> texts = new LinkedHashMap<>();
        for (McpToolCatalog.Stack stack : McpToolCatalog.Stack.values()) {
            for (McpToolCatalog.Entry entry : McpToolCatalog.entriesFor(stack)) {
                texts.put(
                        stack + " " + entry.name(),
                        stack == McpToolCatalog.Stack.QUARKUS
                                ? McpToolDescriptions.quarkus(entry.name())
                                : McpToolDescriptions.spring(entry.name()));
            }
        }
        for (String framework : new String[] {"Spring Boot", "Quarkus"}) {
            texts.put(framework + " instructions", McpGuidance.instructions(framework));
            for (McpPrompt prompt : McpGuidance.prompts(framework)) {
                texts.put(framework + " prompt " + prompt.name(), prompt.description() + " " + prompt.text());
            }
        }

        assertPlain(texts);
    }

    @Test
    void theCliManifestAndTheConsumerSkillArePlain() throws IOException {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String file : new String[] {
            "../bootui-cli/src/main/resources/bootui-tools.json",
            "../skills/bootui/SKILL.md",
            "../plugins/bootui/skills/bootui/SKILL.md"
        }) {
            Path path = Path.of(file);
            assertThat(path).as(file).exists();
            texts.put(file, Files.readString(path, StandardCharsets.UTF_8));
        }

        assertPlain(texts);
    }

    private static void assertPlain(Map<String, String> texts) {
        texts.forEach((where, text) -> {
            if (text != null) {
                assertThat(PLAN_JARGON.matcher(text).results().map(match -> match.group()))
                        .as("%s names plan or validation jargon: %s", where, text)
                        .isEmpty();
            }
        });
    }

    private static RuntimeObservationDto observation(int i, String kind, String unlistedReason) {
        return new RuntimeObservationDto(
                kind + ":" + i,
                kind,
                "GET /api/" + i,
                "OBSERVED",
                "Something happened.",
                10,
                3,
                "REQUEST_ID",
                List.of("Check the call site."),
                List.of("r-" + i),
                1,
                List.of(),
                unlistedReason == null,
                unlistedReason);
    }
}
