package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the shared deny-list both {@link io.github.jdubois.bootui.engine.exceptions.ExceptionStore}'s
 * exception location and {@code SqlTraceRecorder}'s SQL call-site capture rely on to pick the first
 * "application frame" out of a stack trace.
 */
class StackFramePrefixesTests {

    @Test
    void treatsNullAsNotApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass(null)).isTrue();
    }

    @Test
    void recognizesJdkAndCommonFrameworkPackages() {
        assertThat(StackFramePrefixes.isFrameworkClass("java.sql.Statement")).isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("javax.sql.DataSource")).isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("jakarta.persistence.EntityManager"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("jdk.internal.reflect.NativeMethodAccessorImpl"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("sun.reflect.GeneratedMethodAccessor1"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("com.sun.proxy.$Proxy42"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.springframework.web.servlet.DispatcherServlet"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.apache.tomcat.util.net.NioEndpoint"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.hibernate.engine.spi.SessionImpl"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("com.zaxxer.hikari.pool.HikariPool"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.junit.jupiter.engine.execution.ExecutableInvoker"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.netty.channel.nio.NioEventLoop"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.vertx.core.impl.ContextImpl"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.quarkus.runtime.Application"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("org.jboss.threads.EnhancedQueueExecutor"))
                .isTrue();
    }

    @Test
    void recognizesBootUisOwnInstrumentationAsNotApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder"))
                .isTrue();
        assertThat(StackFramePrefixes.isFrameworkClass("io.github.jdubois.bootui.spring.web.SqlTraceController"))
                .isTrue();
    }

    @Test
    void treatsAnythingElseAsApplicationCode() {
        assertThat(StackFramePrefixes.isFrameworkClass("com.example.app.OrderRepository"))
                .isFalse();
        assertThat(StackFramePrefixes.isFrameworkClass("com.acme.billing.InvoiceService"))
                .isFalse();
    }
}
