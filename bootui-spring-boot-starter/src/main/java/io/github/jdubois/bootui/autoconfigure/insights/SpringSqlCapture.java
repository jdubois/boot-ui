package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

/**
 * Whether this Spring application's SQL is recorded, for Runtime Insights ({@code docs/PLAN-v2.md} §5.5): BootUI records
 * the statements of each {@code DataSource} bean its post-processor traced, so without a traced one, as in an R2DBC
 * application, the checks that read SQL are unavailable rather than evaluated over nothing. Shared by Spring MVC and
 * WebFlux. R2DBC is looked up by name, so an application without it never loads its types.
 */
final class SpringSqlCapture implements Supplier<SqlCapture> {

    static final String CONNECTION_FACTORY = "io.r2dbc.spi.ConnectionFactory";

    private final ApplicationContext context;
    private final String connectionFactory;

    SpringSqlCapture(ApplicationContext context) {
        this(context, CONNECTION_FACTORY);
    }

    /** With the connection factory type named {@code connectionFactory}, for tests without R2DBC. */
    SpringSqlCapture(ApplicationContext context, String connectionFactory) {
        this.context = context;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public SqlCapture get() {
        if (!(context instanceof ConfigurableApplicationContext configurable)) {
            return SqlCapture.notRecorded("Existing SQL capture metadata cannot be read passively.");
        }
        var beans = configurable.getBeanFactory();
        String[] singletonNames = beans.getSingletonNames();
        String[] definitionNames = beans.getBeanDefinitionNames();
        Set<String> names = new LinkedHashSet<>();
        for (String name : definitionNames) {
            names.add(name);
            if (names.size() > 4096) {
                return boundedInventory();
            }
        }
        for (String name : singletonNames) {
            names.add(name);
            if (names.size() > 4096) {
                return boundedInventory();
            }
        }
        if (definitionNames.length > 4096 || singletonNames.length > 4096) {
            return SqlCapture.notRecorded("Existing SQL capture metadata exceeds the bounded passive read.");
        }
        Class<?> reactiveType = null;
        ClassLoader loader = context.getClassLoader();
        if (ClassUtils.isPresent(connectionFactory, loader)) {
            try {
                reactiveType = ClassUtils.forName(connectionFactory, loader);
            } catch (ClassNotFoundException | LinkageError ex) {
                return SqlCapture.notRecorded("Reactive database capture metadata could not be read passively.");
            }
        }
        SqlTraceRecorder recorder = null;
        boolean reactive = false;
        boolean unknownProducts = false;
        for (String name : names) {
            Object existing = beans.getSingleton(name);
            if (existing instanceof SqlTraceRecorder candidate) {
                if (recorder != null) {
                    return SqlCapture.notRecorded("Existing SQL capture recorders are ambiguous.");
                }
                recorder = candidate;
            }
            reactive |= reactiveType != null && reactiveType.isInstance(existing);
            unknownProducts |= reactiveType != null && existing instanceof FactoryBean<?>;
            BeanDefinition definition = beans.containsBeanDefinition(name) ? beans.getBeanDefinition(name) : null;
            Class<?> declaredType = declaredType(definition, loader);
            if (reactiveType != null && declaredType != null) {
                reactive |= reactiveType.isAssignableFrom(declaredType);
                if (FactoryBean.class.isAssignableFrom(declaredType)) {
                    Class<?> product = ResolvableType.forClass(declaredType)
                            .as(FactoryBean.class)
                            .getGeneric()
                            .resolve();
                    if (product != null && reactiveType.isAssignableFrom(product)) {
                        reactive = true;
                    } else {
                        unknownProducts = true;
                    }
                }
            }
        }
        SqlCapture capture = SqlCapture.of(recorder, true, reactive);
        return capture.recorded() && !reactive && unknownProducts
                ? SqlCapture.recordedExcept("Only observed JDBC access is counted: reactive database factory products"
                        + " cannot be identified without invoking application callbacks.")
                : capture;
    }

    private static SqlCapture boundedInventory() {
        return SqlCapture.notRecorded("Existing SQL capture metadata exceeds the bounded passive read.");
    }

    private static Class<?> declaredType(BeanDefinition definition, ClassLoader loader) {
        if (!(definition instanceof AbstractBeanDefinition beanDefinition)) {
            return null;
        }
        Class<?> declared = beanDefinition.getResolvableType().resolve();
        if (declared != null || definition.getBeanClassName() == null) {
            return declared;
        }
        try {
            return ClassUtils.forName(definition.getBeanClassName(), loader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }
}
