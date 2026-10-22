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
    void batchSizeIsOneForAnExecutionByDefaultUnknownOtherwiseAndPartOfEqualityAndInterning() {
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        SqlPayload single = new SqlPayload("insert ?", null, "db", false);
        SqlPayload batch = new SqlPayload("insert ?", null, "db", false, null, null, -1, 0L, Provenance.EXECUTION, 12);

        assertThat(single.batchSize()).isEqualTo(1);
        assertThat(batch.batchSize()).isEqualTo(12);
        assertThat(batch).isNotEqualTo(single);
        assertThat(batch.toString()).contains("batchSize=12");
        assertThat(((SqlPayload) batch.interned(dictionary)).batchSize()).isEqualTo(12);
        assertThat(batch.interned(dictionary)).isEqualTo(batch);
        assertThat(batch.estimatedBytes()).isEqualTo(single.estimatedBytes());
        assertThat(new SqlPayload("insert ?", null, "db", false, null, null, -1, 0L, Provenance.EXECUTION, -3)
                        .batchSize())
                .as("a negative size is unknown")
                .isZero();
        for (Provenance provenance : List.of(Provenance.PREPARATION, Provenance.UNKNOWN)) {
            assertThat(new SqlPayload("insert ?", null, "db", false, null, null, -1, 0L, provenance, 9).batchSize())
                    .as("%s knows no batch size", provenance)
                    .isZero();
            assertThat(new SqlPayload("insert ?", null, "db", false, null, null, -1, 0L, provenance).batchSize())
                    .isZero();
        }
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
                    .isEqualTo(56
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
