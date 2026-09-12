package io.github.jdubois.bootui.autoconfigure.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Honest availability of the PostgreSQL panel. Like SQL Trace, the panel's only requirement is a
 * {@code javax.sql.DataSource} bean, so the {@link PanelsController} reports it available exactly when one is
 * present and gives the honest reason otherwise, without touching a database.
 */
class PostgresqlPanelAvailabilityTests {

    private static PanelDto postgresqlPanel(GenericApplicationContext context) {
        return new PanelsController(context, context.getEnvironment(), new BootUiProperties())
                .panels().panels().stream()
                        .filter(panel -> panel.id().equals(BootUiPanels.POSTGRESQL))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void unavailableWithTheHonestReasonWhenNoDataSourceIsPresent() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            PanelDto panel = postgresqlPanel(context);
            assertThat(panel.available()).isFalse();
            assertThat(panel.unavailableReason()).isEqualTo("No DataSource bean is available");
        }
    }

    @Test
    void availableWhenADataSourceBeanIsPresent() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> Mockito.mock(DataSource.class));
            context.refresh();

            assertThat(postgresqlPanel(context).available()).isTrue();
        }
    }
}
