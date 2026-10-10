package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.SqlPayload.Provenance;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlProvenanceTests {

    @Test
    void allOriginalConstructorsRetainTheirExecutionContract() {
        assertThat(List.of(
                        new SqlPayload("select ?", null, "db", false),
                        new SqlPayload("select ?", null, "db", false, null),
                        new SqlPayload("select ?", null, "db", false, null, null, 5),
                        new SqlPayload("select ?", null, "db", false, null, null, 5, 7)))
                .allSatisfy(sql -> {
                    assertThat(sql.executed()).isTrue();
                    assertThat(sql.provenance()).isEqualTo(Provenance.EXECUTION);
                });
    }

    @Test
    void interningPreservesProvenanceAndEqualityAndIncludesItInTheByteEstimate() {
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        SqlPayload execution = payload(Provenance.EXECUTION);
        for (Provenance provenance : Provenance.values()) {
            SqlPayload original = payload(provenance);
            SqlPayload interned = (SqlPayload) original.interned(dictionary);
            assertThat(interned).isEqualTo(original);
            assertThat(interned.hashCode()).isEqualTo(original.hashCode());
            assertThat(interned.provenance()).isEqualTo(provenance);
            assertThat(interned.toString()).contains("provenance=" + provenance);
            assertThat(interned.executed()).isEqualTo(provenance == Provenance.EXECUTION);
            assertThat(original.estimatedBytes())
                    .isEqualTo(48
                            + JournalDictionary.retained(null, "select ?")
                            + JournalDictionary.retained(null, "Repo.find:1")
                            + JournalDictionary.retained(null, "db"));
            if (provenance != Provenance.EXECUTION) {
                assertThat(original).isNotEqualTo(execution);
            }
        }
        assertThat(payload(null).provenance()).isEqualTo(Provenance.UNKNOWN);
        assertThat(payload(null).executed()).isFalse();
    }

    private static SqlPayload payload(Provenance provenance) {
        return new SqlPayload("select ?", "Repo.find:1", "db", false, null, null, 5, 7, provenance);
    }
}
