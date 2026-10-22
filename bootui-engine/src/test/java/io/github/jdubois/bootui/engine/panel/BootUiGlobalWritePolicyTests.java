package io.github.jdubois.bootui.engine.panel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootUiGlobalWritePolicyTests {

    @Test
    void resolvesDismissedRuleWritesWithoutMatchingLookalikePaths() {
        assertThat(BootUiGlobalWritePolicy.subjectFor("/dismissed-rules/rule-id"))
                .contains("dismissed-rules");
        assertThat(BootUiGlobalWritePolicy.subjectFor("/dismissed-rules-extra/rule-id"))
                .isEmpty();
        assertThat(BootUiGlobalWritePolicy.subjectFor("/dismissed-findings")).contains("dismissed-findings");
        assertThat(BootUiGlobalWritePolicy.subjectFor("/dismissed-findings/"))
                .as("a trailing slash still resolves to the same subject")
                .isNotEmpty();
        assertThat(BootUiGlobalWritePolicy.subjectFor("/dismissed-findings-extra"))
                .isEmpty();
        assertThat(BootUiGlobalWritePolicy.subjectFor("/mcp")).isEmpty();
        assertThat(BootUiGlobalWritePolicy.subjectFor("/otlp/v1/traces")).isEmpty();
    }
}
