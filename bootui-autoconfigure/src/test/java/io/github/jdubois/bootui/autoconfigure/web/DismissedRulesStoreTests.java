package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DismissedRulesStoreTests {

    @Test
    void loadReturnsEmptySetWhenFileDoesNotExist(@TempDir Path dir) {
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("dismissed-rules.yaml"));

        assertThat(store.load()).isEmpty();
    }

    @Test
    void dismissPersistsRuleIdsAndIsIdempotent(@TempDir Path dir) {
        Path file = dir.resolve("dismissed-rules.yaml");
        DismissedRulesStore store = new DismissedRulesStore(file);

        assertThat(store.dismiss("SPRING-WEB-001")).containsExactly("SPRING-WEB-001");
        assertThat(store.dismiss("SEC-CONFIG-001")).containsExactly("SPRING-WEB-001", "SEC-CONFIG-001");
        // Dismissing the same rule again is a no-op that keeps the set unchanged.
        assertThat(store.dismiss("SPRING-WEB-001")).containsExactly("SPRING-WEB-001", "SEC-CONFIG-001");

        // A fresh store reads the persisted file, proving the set survived the round-trip.
        assertThat(new DismissedRulesStore(file).load()).containsExactly("SPRING-WEB-001", "SEC-CONFIG-001");
    }

    @Test
    void restoreRemovesRuleIdsAndIsANoOpForUnknownRules(@TempDir Path dir) {
        Path file = dir.resolve("dismissed-rules.yaml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        store.dismiss("SPRING-WEB-001");
        store.dismiss("SEC-CONFIG-001");

        assertThat(store.restore("SPRING-WEB-001")).containsExactly("SEC-CONFIG-001");
        // Restoring a rule that was never dismissed leaves the set untouched.
        assertThat(store.restore("MEM-HEAP-001")).containsExactly("SEC-CONFIG-001");
        assertThat(new DismissedRulesStore(file).load()).containsExactly("SEC-CONFIG-001");
    }

    @Test
    void persistedFileUsesAMinimalYamlListFormat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dismissed-rules.yaml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        store.dismiss("SPRING-WEB-001");
        store.dismiss("MEM-HEAP-001");

        assertThat(Files.readString(file)).isEqualTo("dismissed:\n  - SPRING-WEB-001\n  - MEM-HEAP-001\n");
    }

    @Test
    void createsTheParentDirectoryOnFirstWrite(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("dismissed-rules.yaml");
        DismissedRulesStore store = new DismissedRulesStore(file);

        store.dismiss("SPRING-WEB-001");

        assertThat(Files.exists(file)).isTrue();
        assertThat(store.load()).containsExactly("SPRING-WEB-001");
    }
}
