package io.github.jdubois.bootui.quarkus.beans;

import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.BeanProvider;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Quarkus {@link BeanProvider} backed by the Arc/CDI container.
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code SpringBeanProvider} (which reads Actuator's
 * {@code BeansEndpoint}). It enumerates the live container beans via the CDI {@link BeanManager}
 * ({@code getBeans(Object.class, Any.Literal.INSTANCE)} — every enabled bean), maps each to a neutral
 * {@link BeanSummary}, applies BootUI's self-data filter (dropping the extension's own beans) and computes
 * the Quarkus-flavored classification. The engine {@code BeansService} then sorts, classification/free-text
 * filters and pages on top.</p>
 *
 * <p><strong>Reduced fidelity vs Spring (documented honestly).</strong> Arc does not expose, at runtime,
 * the bean's defining resource nor its inter-bean injection edges the way Actuator's {@code BeansEndpoint}
 * does, so {@code resource} and {@code dependencies} are always empty here. The {@code scope} is the CDI
 * scope vocabulary ({@code ApplicationScoped}, {@code Singleton}, {@code RequestScoped}, {@code Dependent},
 * …) rather than Spring's {@code singleton}/{@code prototype}. {@code name} is the bean's EL name when it
 * is {@code @Named}, otherwise a synthetic decapitalized simple class name (most CDI beans are unnamed).
 * CDI qualifiers have no {@link BeanSummary} field — the DTO is the frozen UI contract — so they are not
 * surfaced. {@code aliases} is therefore always empty. For producer (<code>@Produces</code>) beans Arc reports
 * the declaring class as {@link Bean#getBeanClass()}, so such beans show their producer's class as the type;
 * this also makes the BootUI self-filter robust, since BootUI's own engine services are produced from
 * producer classes under {@code io.github.jdubois.bootui.quarkus}. The self-filter reuses the shared engine
 * {@link InternalPackageMatcher} scoped to {@code io.github.jdubois.bootui.quarkus}/{@code .core} (the same
 * prefixes {@code QuarkusScheduledTaskProvider} and the Log Tail/Exceptions captures use) rather than the
 * whole {@code io.github.jdubois.bootui} tree, so it does not also swallow application code that happens to
 * live under that root package (for example a sample/demo app packaged as {@code io.github.jdubois.bootui.sample}),
 * nor the framework-neutral {@code engine}/{@code spi} packages.</p>
 *
 * <p>The inventory reflects the beans Arc actually retains: Arc removes unused beans at build time as an
 * optimization, so a bean that is never injected (and is not {@code @Unremovable}) does not appear here.
 * In a typical application its own beans are retained because they are used.</p>
 */
public final class QuarkusBeanProvider implements BeanProvider {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.quarkus", "io.github.jdubois.bootui.core"));

    private static final List<String> FRAMEWORK_PREFIXES =
            List.of("io.quarkus.", "io.vertx.", "org.jboss.", "io.smallrye.", "org.eclipse.microprofile.", "io.netty.");

    private final BeanManager beanManager;

    public QuarkusBeanProvider(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    @Override
    public boolean available() {
        // Arc is a core dependency of the Quarkus extension, so the container is always present.
        return beanManager != null;
    }

    @Override
    public List<BeanSummary> beans() {
        if (beanManager == null) {
            return List.of();
        }
        List<BeanSummary> summaries = new ArrayList<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            String type = beanClass == null ? null : beanClass.getName();
            if (type != null && INTERNAL_PACKAGES.matchesName(type)) {
                continue;
            }
            summaries.add(toSummary(bean, beanClass, type));
        }
        return summaries;
    }

    private BeanSummary toSummary(Bean<?> bean, Class<?> beanClass, String type) {
        return new BeanSummary(name(bean, beanClass), type, scope(bean), null, List.of(), List.of(), classify(type));
    }

    private String name(Bean<?> bean, Class<?> beanClass) {
        String name = bean.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (beanClass == null) {
            return bean.getScope().getSimpleName();
        }
        return decapitalize(beanClass.getSimpleName());
    }

    private String scope(Bean<?> bean) {
        Class<?> scope = bean.getScope();
        return scope == null ? null : scope.getSimpleName();
    }

    private String classify(String type) {
        if (type == null) {
            return "OTHER";
        }
        if (INTERNAL_PACKAGES.matchesName(type)) {
            return "BOOTUI";
        }
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (type.startsWith(prefix)) {
                return "FRAMEWORK";
            }
        }
        if (type.startsWith("java.") || type.startsWith("jakarta.")) {
            return "PLATFORM";
        }
        return "APPLICATION";
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }
}
