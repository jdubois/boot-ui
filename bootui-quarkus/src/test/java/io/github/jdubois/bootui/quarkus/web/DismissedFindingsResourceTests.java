package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DismissedFindingsResourceTests {

    private static final String BODY =
            "{\"ruleId\":\"DB-SCHEMA-001\",\"identity\":\"v1:ds=primary|table=orders\",\"note\":\"Legacy table\"}";
    private static final String CANARY = "PrivateDismissalReason";

    @Test
    void dismissesListsAndRestoresAFindingWithItsNote(@TempDir Path dir) {
        DismissedFindingsResource resource =
                new DismissedFindingsResource(new DismissedRulesStore(dir.resolve("boot-ui.yml")));

        assertThat(resource.dismiss(BODY).dismissed()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("DB-SCHEMA-001");
            assertThat(finding.note()).isEqualTo("Legacy table");
        });
        assertThat(resource.list().dismissed()).hasSize(1);
        assertThat(resource.list().ignored()).isZero();
        assertThat(resource.restore(BODY).dismissed()).isEmpty();
    }

    @Test
    void refusesBadBodiesWithAFixedMessageThatNeverQuotesTheNote(@TempDir Path dir) {
        DismissedFindingsResource resource =
                new DismissedFindingsResource(new DismissedRulesStore(dir.resolve("boot-ui.yml")));

        for (String body : new String[] {
            "{\"note\":" + CANARY + "}",
            "{\"ruleId\":\"DB-SCHEMA-001\",\"identity\":\"bad identity\",\"note\":\"" + CANARY + "\"}",
            "{\"ruleId\":\"DB-SCHEMA-001\",\"identity\":\"v1:a=b\",\"note\":7}",
            "[\"" + CANARY + "\"]",
            BODY + " {}",
            BODY + " " + CANARY,
            "",
            null
        }) {
            assertThatThrownBy(() -> resource.dismiss(body))
                    .isInstanceOfSatisfying(AdvisorViolationException.class, e -> {
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.getMessage()).doesNotContain(CANARY);
                        assertThat(e.getCause()).isNull();
                    });
            if (body == null || !body.contains("\"note\":7")) {
                // A restore reads no note, so only a bad rule id or identity can refuse it.
                assertThatThrownBy(() -> resource.restore(body)).isInstanceOf(AdvisorViolationException.class);
            }
        }
        assertThat(resource.list().dismissed()).isEmpty();
    }
}
