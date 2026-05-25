package io.github.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.bootui.autoconfigure.database.RecordingDataSourceWrapper;
import io.github.bootui.autoconfigure.database.SqlRecorder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link DatabaseController}.
 */
class DatabaseControllerTests {

    @Test
    void reportIsEmptyWhenNoDataSources() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(DataSource.class)).thenReturn(Map.of());

        DatabaseController controller = new DatabaseController(
                providerOf(ctx), providerOf(new SqlRecorder(10)));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSourcePresent").value(false))
                .andExpect(jsonPath("$.dataSources").isEmpty())
                .andExpect(jsonPath("$.pools").isEmpty())
                .andExpect(jsonPath("$.recordedSqlRequests").value(0))
                .andExpect(jsonPath("$.maxSqlRequests").value(10));
    }

    @Test
    void reportListsDataSourcesAndCapturedSql() throws Exception {
        SqlRecorder recorder = new SqlRecorder(10);

        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(3);
        when(connection.getCatalog()).thenReturn("BOOTUI");
        when(connection.getSchema()).thenReturn("PUBLIC");

        DataSource wrapped = RecordingDataSourceWrapper.wrap(delegate, "primary", recorder);
        try (Connection c = wrapped.getConnection()) {
            PreparedStatement ps = c.prepareStatement("update t set v = ? where id = ?");
            ps.executeUpdate();
        }

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(DataSource.class)).thenReturn(Map.of("primary", wrapped));

        DatabaseController controller = new DatabaseController(providerOf(ctx), providerOf(recorder));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSourcePresent").value(true))
                .andExpect(jsonPath("$.dataSources[0].beanName").value("primary"))
                .andExpect(jsonPath("$.dataSources[0].catalog").value("BOOTUI"))
                .andExpect(jsonPath("$.dataSources[0].schema").value("PUBLIC"))
                .andExpect(jsonPath("$.recordedSqlRequests").value(1))
                .andExpect(jsonPath("$.recentSql[0].sql").value("update t set v = ? where id = ?"))
                .andExpect(jsonPath("$.recentSql[0].statementType").value("PREPARED"))
                .andExpect(jsonPath("$.recentSql[0].dataSource").value("primary"))
                .andExpect(jsonPath("$.recentSql[0].affectedRows").value(3))
                .andExpect(jsonPath("$.recentSql[0].success").value(true));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
