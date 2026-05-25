package io.github.bootui.autoconfigure.web;

import io.github.bootui.autoconfigure.database.RecordingDataSourceWrapper;
import io.github.bootui.autoconfigure.database.SqlRecorder;
import io.github.bootui.core.BootUiDtos.ConnectionPoolDto;
import io.github.bootui.core.BootUiDtos.DataSourceInfoDto;
import io.github.bootui.core.BootUiDtos.DatabaseReport;
import io.github.bootui.core.BootUiDtos.SqlRequestDto;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a read-only view of the JDBC data sources currently registered in
 * the application context: connection-pool metrics and recent SQL requests.
 */
@RestController
@ConditionalOnClass(DataSource.class)
@RequestMapping("/bootui/api/database")
public class DatabaseController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseController.class);

    private final ObjectProvider<ApplicationContext> contextProvider;
    private final ObjectProvider<SqlRecorder> recorderProvider;

    public DatabaseController(ObjectProvider<ApplicationContext> contextProvider,
                              ObjectProvider<SqlRecorder> recorderProvider) {
        this.contextProvider = contextProvider;
        this.recorderProvider = recorderProvider;
    }

    @GetMapping
    public DatabaseReport report() {
        Map<String, DataSource> beans = discover();
        List<DataSourceInfoDto> infos = new ArrayList<>(beans.size());
        List<ConnectionPoolDto> pools = new ArrayList<>(beans.size());
        for (Map.Entry<String, DataSource> entry : beans.entrySet()) {
            DataSource original = unwrap(entry.getValue());
            infos.add(buildInfo(entry.getKey(), original));
            ConnectionPoolDto pool = buildPool(entry.getKey(), original);
            if (pool != null) {
                pools.add(pool);
            }
        }

        SqlRecorder recorder = recorderProvider.getIfAvailable();
        List<SqlRequestDto> recent = recorder == null ? Collections.emptyList() : recorder.snapshot();
        int max = recorder == null ? 0 : recorder.capacity();

        return new DatabaseReport(
                !beans.isEmpty(),
                infos,
                pools,
                recent.size(),
                max,
                recent);
    }

    private Map<String, DataSource> discover() {
        ApplicationContext context = contextProvider.getIfAvailable();
        if (context == null) {
            return Collections.emptyMap();
        }
        Map<String, DataSource> all = new LinkedHashMap<>(context.getBeansOfType(DataSource.class));
        return all;
    }

    private DataSource unwrap(DataSource ds) {
        if (ds instanceof RecordingDataSourceWrapper.RecordingDataSource wrapped) {
            return wrapped.bootUiDelegate();
        }
        return ds;
    }

    private DataSourceInfoDto buildInfo(String beanName, DataSource ds) {
        String type = ds.getClass().getName();
        String poolName = readString(ds, "getPoolName");
        String jdbcUrl = readString(ds, "getJdbcUrl");
        if (jdbcUrl == null) {
            jdbcUrl = readString(ds, "getUrl");
        }
        String driver = readString(ds, "getDriverClassName");
        String user = readString(ds, "getUsername");
        String catalog = null;
        String schema = null;
        // Best-effort: ask one connection for catalog/schema. Do NOT block long.
        try (Connection c = ds.getConnection()) {
            catalog = c.getCatalog();
            try {
                schema = c.getSchema();
            } catch (SQLException | AbstractMethodError ignore) {
                // not supported by all drivers
            }
        } catch (Exception ignore) {
            // ignore - DataSource may not be initialized yet, or it may be busy
        }
        return new DataSourceInfoDto(beanName, type, poolName, jdbcUrl, driver, user, catalog, schema);
    }

    private ConnectionPoolDto buildPool(String beanName, DataSource ds) {
        String className = ds.getClass().getName();
        if (className.startsWith("com.zaxxer.hikari.")) {
            return readHikariPool(beanName, ds);
        }
        // Tomcat DBCP & DBCP2 pools: surface what we can via reflection.
        if (className.startsWith("org.apache.tomcat.jdbc.pool.")
                || className.startsWith("org.apache.commons.dbcp2.")) {
            return readGenericPool(beanName, ds, "TOMCAT_OR_DBCP2");
        }
        return null;
    }

    private ConnectionPoolDto readHikariPool(String beanName, DataSource ds) {
        try {
            Object mxBean = invoke(ds, "getHikariPoolMXBean");
            Object configBean = invoke(ds, "getHikariConfigMXBean");
            return new ConnectionPoolDto(
                    beanName,
                    "HIKARI",
                    (String) invokeOptional(configBean, "getPoolName"),
                    (Integer) invokeOptional(mxBean, "getActiveConnections"),
                    (Integer) invokeOptional(mxBean, "getIdleConnections"),
                    (Integer) invokeOptional(mxBean, "getTotalConnections"),
                    (Integer) invokeOptional(mxBean, "getThreadsAwaitingConnection"),
                    (Integer) invokeOptional(configBean, "getMaximumPoolSize"),
                    (Integer) invokeOptional(configBean, "getMinimumIdle"),
                    (Long) invokeOptional(configBean, "getConnectionTimeout"),
                    (Long) invokeOptional(configBean, "getIdleTimeout"),
                    (Long) invokeOptional(configBean, "getMaxLifetime"));
        } catch (Throwable ex) {
            log.debug("BootUI: could not read HikariCP metrics for bean '{}'", beanName, ex);
            return null;
        }
    }

    private ConnectionPoolDto readGenericPool(String beanName, DataSource ds, String type) {
        Integer active = (Integer) invokeOptional(ds, "getNumActive");
        Integer idle = (Integer) invokeOptional(ds, "getNumIdle");
        Integer max = (Integer) invokeOptional(ds, "getMaxTotal");
        if (max == null) {
            max = (Integer) invokeOptional(ds, "getMaxActive");
        }
        Integer minIdle = (Integer) invokeOptional(ds, "getMinIdle");
        Integer total = active != null && idle != null ? active + idle : null;
        return new ConnectionPoolDto(beanName, type, null, active, idle, total, null, max, minIdle, null, null, null);
    }

    private String readString(Object target, String methodName) {
        Object value = invokeOptional(target, methodName);
        return value == null ? null : value.toString();
    }

    private Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method m = target.getClass().getMethod(methodName);
        return m.invoke(target);
    }

    private Object invokeOptional(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return invoke(target, methodName);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
}
