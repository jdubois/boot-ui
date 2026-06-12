package io.github.jdubois.bootui.autoconfigure.sqltrace;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers the JDK dynamic proxies created by {@link SqlTracingProxies} as GraalVM native-image
 * proxy metadata.
 *
 * <p>SQL tracing wraps the application's {@code DataSource}, its {@code Connection}s, and the
 * {@code Statement}/{@code PreparedStatement}/{@code CallableStatement} objects they create with
 * {@link java.lang.reflect.Proxy} instances. The native-image build can only materialize a JDK proxy
 * class if the exact, ordered interface set is known at build time, so each proxy that BootUI creates
 * is declared here using the same fixed interface arrays the runtime uses. Keeping these registrations
 * driven by the {@code SqlTracingProxies.*_INTERFACES} constants guarantees they cannot drift apart.</p>
 *
 * <p>This is imported from {@code BootUiAutoConfiguration} via {@code @ImportRuntimeHints}, so the
 * hints are only contributed when BootUI auto-configuration is present. If a proxy is ever requested
 * for an interface set that was not registered, {@code SqlTraceDataSourceBeanPostProcessor} still fails
 * open and leaves the {@code DataSource} untraced rather than breaking application startup.</p>
 */
public class SqlTraceRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.proxies().registerJdkProxy(SqlTracingProxies.DATA_SOURCE_INTERFACES);
        hints.proxies().registerJdkProxy(SqlTracingProxies.CONNECTION_INTERFACES);
        hints.proxies().registerJdkProxy(SqlTracingProxies.STATEMENT_INTERFACES);
        hints.proxies().registerJdkProxy(SqlTracingProxies.PREPARED_STATEMENT_INTERFACES);
        hints.proxies().registerJdkProxy(SqlTracingProxies.CALLABLE_STATEMENT_INTERFACES);
    }
}
