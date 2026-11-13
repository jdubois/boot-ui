package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/** Active-console diagnostics, independent of the optional startup banner. */
@Singleton
public class BootUiQuarkusRuntimeDiagnostics {

    private static final Logger LOG = Logger.getLogger(BootUiQuarkusRuntimeDiagnostics.class);
    private static final Set<String> REACTIVE_SQL_TYPES = Set.of(
            "io.vertx.sqlclient.Pool",
            "io.vertx.sqlclient.SqlClient",
            "io.vertx.mutiny.sqlclient.Pool",
            "io.vertx.mutiny.sqlclient.SqlClient",
            "org.hibernate.reactive.mutiny.Mutiny$SessionFactory",
            "org.hibernate.reactive.stage.Stage$SessionFactory");

    private final Config config;
    private final BeanManager beanManager;

    @Inject
    public BootUiQuarkusRuntimeDiagnostics(Config config, BeanManager beanManager) {
        this.config = config;
        this.beanManager = beanManager;
    }

    void onStart(@Observes StartupEvent event) {
        // Inspect CDI declarations, never instantiate a pool or initialize optional client classes.
        Set<String> beanTypes = beanManager.getBeans(Object.class, Any.Literal.INSTANCE).stream()
                .flatMap(bean -> bean.getTypes().stream())
                .map(Type::getTypeName)
                .collect(Collectors.toSet());
        warnings(AgentBridgeAccess.locate().problem(), reactiveSqlDeclared(config, beanTypes))
                .forEach(LOG::warn);
    }

    static List<String> warnings(String agentProblem, boolean reactiveSqlDeclared) {
        var warnings = new ArrayList<String>();
        if (agentProblem != null) {
            warnings.add("BOOTUI-COMPAT-051: " + agentProblem
                    + ". Attach the bootui-agent jar matching this BootUI version; the incompatible agent"
                    + " remains unused.");
        }
        if (reactiveSqlDeclared) {
            warnings.add("BOOTUI-COMPAT-041: Reactive SQL queries are not captured by BootUI's JDBC SQL tracing."
                    + " SQL-based diagnostics cover JDBC only, including applications that also configure JDBC.");
        }
        return List.copyOf(warnings);
    }

    static boolean reactiveSqlDeclared(Config config, Set<String> beanTypes) {
        if (beanTypes.stream().anyMatch(REACTIVE_SQL_TYPES::contains)) {
            return true;
        }
        for (String name : config.getPropertyNames()) {
            if (!name.startsWith("quarkus.datasource.") || !name.endsWith(".reactive.url")) {
                continue;
            }
            String prefix = name.substring(0, name.length() - "url".length());
            String datasourcePrefix = prefix.substring(0, prefix.length() - "reactive.".length());
            if (config.getOptionalValue(prefix + "active", Boolean.class).orElse(true)
                    && config.getOptionalValue(datasourcePrefix + "active", Boolean.class)
                            .orElse(true)
                    && config.getOptionalValue(prefix + "enabled", Boolean.class)
                            .orElse(true)
                    && config.getOptionalValue(name, String.class)
                            .filter(value -> !value.isBlank())
                            .isPresent()) {
                return true;
            }
        }
        return false;
    }
}
