package io.github.jdubois.bootui.autoconfigure.sql;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.SqlTraceQueryDto;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SqlTraceControllerTests {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ListableBeanFactory> providerOf(ListableBeanFactory factory) {
        ObjectProvider<ListableBeanFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        return provider;
    }

    private static ListableBeanFactory factoryWithDataSources(String... beanNames) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(DataSource.class)).thenReturn(beanNames);
        return factory;
    }

    private static SqlTraceStore storeWith(int maxQueries) {
        BootUiProperties.SqlTrace config = new BootUiProperties.SqlTrace();
        config.setMaxQueries(maxQueries);
        return new SqlTraceStore(config);
    }

    private static SqlTraceQueryDto select(String sql) {
        return new SqlTraceQueryDto(
                0L,
                1_000L,
                "ds",
                "1",
                "PREPARED",
                "SELECT",
                false,
                0,
                7,
                true,
                false,
                null,
                "main",
                List.of(sql),
                null);
    }

    private static MockMvc mvc(SqlTraceStore store, BootUiProperties properties, ListableBeanFactory factory) {
        return standaloneSetup(new SqlTraceController(store, properties, providerOf(factory)))
                .build();
    }

    @Test
    void reportsUnavailableWhenNoDataSourceWrapped() throws Exception {
        SqlTraceStore store = storeWith(50);
        BootUiProperties properties = new BootUiProperties();

        mvc(store, properties, factoryWithDataSources())
                .perform(get("/bootui/api/sql-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value(org.hamcrest.Matchers.containsString("DataSource")))
                .andExpect(jsonPath("$.stats.recorded").value(0));
    }

    @Test
    void reportsRecordedQueriesNewestFirstWithStats() throws Exception {
        SqlTraceStore store = storeWith(50);
        store.registerDataSource("dataSource");
        store.add(select("select * from a"));
        store.add(select("select * from b"));
        BootUiProperties properties = new BootUiProperties();

        mvc(store, properties, factoryWithDataSources("dataSource"))
                .perform(get("/bootui/api/sql-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.dataSources[0]").value("dataSource"))
                .andExpect(jsonPath("$.stats.recorded").value(2))
                .andExpect(jsonPath("$.stats.selectCount").value(2))
                // Newest execution first.
                .andExpect(jsonPath("$.queries[0].statements[0]").value("select * from b"))
                .andExpect(jsonPath("$.queries[1].statements[0]").value("select * from a"));
    }

    @Test
    void clearEmptiesTheBuffer() throws Exception {
        SqlTraceStore store = storeWith(50);
        store.registerDataSource("dataSource");
        store.add(select("select 1"));
        store.add(select("select 2"));
        BootUiProperties properties = new BootUiProperties();

        mvc(store, properties, factoryWithDataSources("dataSource"))
                .perform(post("/bootui/api/sql-trace/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(2));

        org.assertj.core.api.Assertions.assertThat(store.snapshot().queries()).isEmpty();
    }

    @Test
    void recordingEndpointTogglesState() throws Exception {
        SqlTraceStore store = storeWith(50);
        store.registerDataSource("dataSource");
        BootUiProperties properties = new BootUiProperties();
        MockMvc mvc = mvc(store, properties, factoryWithDataSources("dataSource"));

        mvc.perform(post("/bootui/api/sql-trace/recording")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recording").value(false));
        org.assertj.core.api.Assertions.assertThat(store.isRecording()).isFalse();

        mvc.perform(post("/bootui/api/sql-trace/recording")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recording").value(true));
        org.assertj.core.api.Assertions.assertThat(store.isRecording()).isTrue();
    }
}
