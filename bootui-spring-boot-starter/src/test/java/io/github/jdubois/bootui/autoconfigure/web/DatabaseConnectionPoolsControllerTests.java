package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin wiring tests for {@link DatabaseConnectionPoolsController}: the controller delegates to the engine
 * {@link ConnectionPoolService}, so these only assert that the two GET routes call through and map the
 * snapshot's presence onto 200/404. The discovery, masking and MXBean behaviour are covered by
 * {@code SpringConnectionPoolProviderTests} and {@code ConnectionPoolServiceTests}.
 */
class DatabaseConnectionPoolsControllerTests {

    private static MockMvc mvc(ConnectionPoolService service) {
        return standaloneSetup(new DatabaseConnectionPoolsController(service)).build();
    }

    @Test
    void poolsDelegatesToEngineReport() throws Exception {
        ConnectionPoolService service = mock(ConnectionPoolService.class);
        HikariPoolSnapshotDto snapshot = new HikariPoolSnapshotDto(123L, 3, 2, 5, 1);
        HikariPoolDto pool = new HikariPoolDto(
                "dataSource",
                "HikariPool-1",
                "jdbc:postgresql://******@localhost:5432/demo",
                "******",
                "org.postgresql.Driver",
                5,
                10,
                30000L,
                600000L,
                1800000L,
                5000L,
                0L,
                false,
                true,
                true,
                null,
                snapshot);
        when(service.report()).thenReturn(new HikariPoolsReport(true, 1, List.of(pool)));

        mvc(service)
                .perform(get("/bootui/api/database-connection-pools/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hikariPresent").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pools[0].beanName").value("dataSource"))
                .andExpect(jsonPath("$.pools[0].jdbcUrl").value("jdbc:postgresql://******@localhost:5432/demo"))
                .andExpect(jsonPath("$.pools[0].snapshot.active").value(3));
    }

    @Test
    void snapshotReturnsCountsForKnownPool() throws Exception {
        ConnectionPoolService service = mock(ConnectionPoolService.class);
        when(service.snapshot("HikariPool-1")).thenReturn(new HikariPoolSnapshotDto(123L, 3, 2, 5, 1));

        mvc(service)
                .perform(get("/bootui/api/database-connection-pools/pools/HikariPool-1/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(3))
                .andExpect(jsonPath("$.idle").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.pending").value(1));
    }

    @Test
    void snapshotReturnsNotFoundForUnknownPool() throws Exception {
        ConnectionPoolService service = mock(ConnectionPoolService.class);
        when(service.snapshot("does-not-exist")).thenReturn(null);

        mvc(service)
                .perform(get("/bootui/api/database-connection-pools/pools/does-not-exist/snapshot"))
                .andExpect(status().isNotFound());
    }
}
