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
        DismissedRulesStore store = new DismissedRulesStore(dir.resolve("boot-ui.yml"));

        assertThat(store.load()).isEmpty();
    }

    @Test
    void dismissPersistsRuleIdsAndIsIdempotent(@TempDir Path dir) {
        Path file = dir.resolve("boot-ui.yml");
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
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        store.dismiss("SPRING-WEB-001");
        store.dismiss("SEC-CONFIG-001");

        assertThat(store.restore("SPRING-WEB-001")).containsExactly("SEC-CONFIG-001");
        // Restoring a rule that was never dismissed leaves the set untouched.
        assertThat(store.restore("MEM-HEAP-001")).containsExactly("SEC-CONFIG-001");
        assertThat(new DismissedRulesStore(file).load()).containsExactly("SEC-CONFIG-001");
    }

    @Test
    void persistedFileUsesAGenericBootUiYamlNode(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);
        store.dismiss("SPRING-WEB-001");
        store.dismiss("MEM-HEAP-001");

        assertThat(Files.readString(file))
                .isEqualTo("# BootUI configuration (developer-local; safe to delete).\n\n"
                        + "dismissedRules:\n  - SPRING-WEB-001\n  - MEM-HEAP-001\n");
    }

    @Test
    void repeatedWritesAreByteStable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);

        store.dismiss("SPRING-WEB-001");
        store.dismiss("MEM-HEAP-001");
        String afterFirstPair = Files.readString(file);

        // Toggling a third rule on and off must return the file to the exact same bytes:
        // no accumulating blank lines and no duplicated header comment.
        store.dismiss("SEC-CONFIG-001");
        store.restore("SEC-CONFIG-001");

        assertThat(Files.readString(file)).isEqualTo(afterFirstPair);
    }

    @Test
    void preservesOtherTopLevelSectionsAcrossWrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        Files.writeString(
                file, "# My BootUI config\nsomeSetting: value\nnested:\n  key: 1\ndismissedRules:\n  - OLD-001\n");
        DismissedRulesStore store = new DismissedRulesStore(file);

        store.dismiss("NEW-002");

        String written = Files.readString(file);
        assertThat(written).contains("# My BootUI config");
        assertThat(written).contains("someSetting: value");
        assertThat(written).contains("nested:\n  key: 1");
        // The managed node is rewritten in place with both the old and new ids and is not duplicated.
        assertThat(written.split("dismissedRules:", -1)).hasSize(2);
        assertThat(new DismissedRulesStore(file).load()).containsExactly("OLD-001", "NEW-002");
    }

    @Test
    void rewritesAnInlineEmptyNodeToBlockForm(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("boot-ui.yml");
        Files.writeString(file, "dismissedRules: []\n");
        DismissedRulesStore store = new DismissedRulesStore(file);

        assertThat(store.load()).isEmpty();
        store.dismiss("SPRING-WEB-001");

        String written = Files.readString(file);
        // The inline node is replaced by the block form, never duplicated.
        assertThat(written.split("dismissedRules", -1)).hasSize(2);
        assertThat(new DismissedRulesStore(file).load()).containsExactly("SPRING-WEB-001");
    }

    @Test
    void roundTripsRuleIdsContainingSlashesAndSpaces(@TempDir Path dir) {
        Path file = dir.resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);

        store.dismiss("A/B C");

        assertThat(new DismissedRulesStore(file).load()).containsExactly("A/B C");
    }

    @Test
    void createsTheParentDirectoryOnFirstWrite(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("boot-ui.yml");
        DismissedRulesStore store = new DismissedRulesStore(file);

        store.dismiss("SPRING-WEB-001");

        assertThat(Files.exists(file)).isTrue();
        assertThat(store.load()).containsExactly("SPRING-WEB-001");
    }
}
