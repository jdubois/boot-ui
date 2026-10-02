package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The named persistence units whose sessions the {@code orm} journal source also meters (M4-9). */
class BootUiQuarkusProcessorOrmTest {

    @Test
    void namedUnitsComeFromTheirDatasourceOrPackagesPropertyQuotedOrNot() {
        assertThat(BootUiQuarkusProcessor.namedPersistenceUnits(List.of(
                        "quarkus.hibernate-orm.datasource",
                        "quarkus.hibernate-orm.packages",
                        "quarkus.hibernate-orm.database.generation",
                        "quarkus.hibernate-orm.\"users\".datasource",
                        "quarkus.hibernate-orm.\"users\".packages",
                        "quarkus.hibernate-orm.inventory.packages",
                        "quarkus.hibernate-orm.\"<default>\".datasource",
                        "quarkus.hibernate-orm.\"orders.v2\".datasource")))
                .containsExactly("inventory", "orders.v2", "users");
    }
}
