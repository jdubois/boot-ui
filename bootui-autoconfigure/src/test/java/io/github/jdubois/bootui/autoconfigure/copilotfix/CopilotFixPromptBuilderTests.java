package io.github.jdubois.bootui.autoconfigure.copilotfix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CopilotFixDescriptorDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotFixPromptBuilderTests {

    @Test
    void systemPromptConstrainsTheAgent() {
        String prompt = CopilotFixPromptBuilder.systemPrompt();
        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("smallest");
        assertThat(prompt).contains("Do not commit, push, or open a pull request");
    }

    @Test
    void userPromptRestatesTheFinding() {
        CopilotFixDescriptorDto descriptor = new CopilotFixDescriptorDto(
                "GHSA-1234",
                "vulnerabilities",
                "Vulnerable dependency",
                "Upgrade to 1.2.3",
                "HIGH",
                List.of("org.example:lib:1.0.0"));

        String prompt = CopilotFixPromptBuilder.userPrompt(descriptor);

        assertThat(prompt).contains("GHSA-1234");
        assertThat(prompt).contains("vulnerabilities");
        assertThat(prompt).contains("Vulnerable dependency");
        assertThat(prompt).contains("HIGH");
        assertThat(prompt).contains("org.example:lib:1.0.0");
        assertThat(prompt).contains("Upgrade to 1.2.3");
    }

    @Test
    void userPromptToleratesMissingFields() {
        CopilotFixDescriptorDto descriptor = new CopilotFixDescriptorDto("SEC-001", null, null, null, null, null);

        String prompt = CopilotFixPromptBuilder.userPrompt(descriptor);

        assertThat(prompt).contains("SEC-001");
        assertThat(prompt).contains("scanner");
    }
}
