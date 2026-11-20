package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.FactoryBeanRegistrySupport;
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
        if (context.getParent() != null) {
            return SqlCapture.notRecorded(
                    "Existing SQL capture metadata cannot be completed across an application-context hierarchy.");
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
            BeanDefinition definition = beans.containsBeanDefinition(name) ? beans.getBeanDefinition(name) : null;
            if (definition != null && definition.isAbstract() && existing == null) {
                continue;
            }
            if (existing instanceof SqlTraceRecorder candidate) {
                if (recorder != null) {
                    return SqlCapture.notRecorded("Existing SQL capture recorders are ambiguous.");
                }
                recorder = candidate;
            }
            if (reactiveType == null) {
                continue;
            }
            if (existing instanceof FactoryBean<?>) {
                Object product = cachedFactoryProduct(beans, name);
                if (product != null) {
                    reactive |= reactiveType.isInstance(product);
                    continue;
                }
                Class<?> productType = factoryProductType(definition, existing.getClass());
                if (productType == null || productType == Object.class) {
                    unknownProducts = true;
                } else {
                    reactive |= reactiveType.isAssignableFrom(productType);
                }
            } else if (existing != null) {
                reactive |= reactiveType.isInstance(existing);
            } else {
                Class<?> declaredType = declaredType(definition, loader);
                if (definition != null && (declaredType == null || declaredType == Object.class)) {
                    unknownProducts = true;
                } else if (declaredType != null && reactiveType.isAssignableFrom(declaredType)) {
                    reactive = true;
                } else if (declaredType != null && FactoryBean.class.isAssignableFrom(declaredType)) {
                    Class<?> productType = factoryProductType(definition, declaredType);
                    if (productType == null || productType == Object.class) {
                        unknownProducts = true;
                    } else if (reactiveType.isAssignableFrom(productType)) {
                        reactive = true;
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

    private static Class<?> factoryProductType(BeanDefinition definition, Class<?> factoryType) {
        if (definition != null) {
            Object attribute = definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
            ResolvableType product = attribute instanceof Class<?> type
                    ? ResolvableType.forClass(type)
                    : attribute instanceof ResolvableType type ? type : ResolvableType.NONE;
            if (product.resolve() != null) {
                return product.resolve();
            }
        }
        return ResolvableType.forClass(factoryType)
                .as(FactoryBean.class)
                .getGeneric()
                .resolve();
    }

    private static Object cachedFactoryProduct(ConfigurableListableBeanFactory beans, String name) {
        if (!(beans instanceof FactoryBeanRegistrySupport)) {
            return null;
        }
        try {
            Field field = FactoryBeanRegistrySupport.class.getDeclaredField("factoryBeanObjectCache");
            Object cache = field.trySetAccessible() ? field.get(beans) : null;
            return cache != null && cache.getClass() == ConcurrentHashMap.class ? ((Map<?, ?>) cache).get(name) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
