package io.github.jdubois.bootui.autoconfigure.beans;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.spi.BeanProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeanDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeansDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.ContextBeansDescriptor;

/**
 * Spring Boot {@link BeanProvider} backed by Actuator's {@link BeansEndpoint}.
 *
 * <p>This class is the single touch-point for the Actuator beans descriptor types, and it is only
 * instantiated inside the {@code @ConditionalOnClass} nested configuration in
 * {@code BootUiEngineConfiguration}, so {@link BeansEndpoint} and the descriptor types are never linked
 * in an Actuator-absent application. The endpoint is resolved <em>live</em> through a supplier because the
 * endpoint bean may be absent (Actuator present but the beans endpoint not exposed), in which case
 * {@link #available()} reports {@code false} and the engine serves an empty report.</p>
 *
 * <p>The mapping, BootUI self-data filtering and classification live here (not in the engine) on purpose:
 * the self-data filter inspects the live {@link Class}{@code <?>} and the actuator-supplied defining
 * {@code resource}, and the classification's {@code FRAMEWORK} bucket is Spring-specific
 * ({@code org.springframework.}). Performing both here is byte-identical to the original
 * {@code BeansController}; the engine {@code BeansService} then only sorts, classification/free-text
 * filters and pages.</p>
 */
public final class SpringBeanProvider implements BeanProvider {

    private final Supplier<BeansEndpoint> endpoint;

    private final BootUiSelfDataFilter selfDataFilter;

    public SpringBeanProvider(Supplier<BeansEndpoint> endpoint, BootUiSelfDataFilter selfDataFilter) {
        this.endpoint = endpoint;
        this.selfDataFilter = selfDataFilter;
    }

    @Override
    public boolean available() {
        return endpoint.get() != null;
    }

    @Override
    public List<BeanSummary> beans() {
        BeansEndpoint be = endpoint.get();
        if (be == null) {
            return List.of();
        }
        BeansDescriptor descriptor = be.beans();
        List<BeanSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, ContextBeansDescriptor> ctxEntry :
                descriptor.getContexts().entrySet()) {
            for (Map.Entry<String, BeanDescriptor> beanEntry :
                    ctxEntry.getValue().getBeans().entrySet()) {
                BeanDescriptor beanDescriptor = beanEntry.getValue();
                if (!selfDataFilter.shouldIncludeBean(
                        beanEntry.getKey(), beanDescriptor.getType(), beanDescriptor.getResource())) {
                    continue;
                }
                summaries.add(toSummary(beanEntry.getKey(), beanEntry.getValue()));
            }
        }
        return summaries;
    }

    private BeanSummary toSummary(String name, BeanDescriptor descriptor) {
        String type = descriptor.getType() == null ? null : descriptor.getType().getName();
        return new BeanSummary(
                name,
                type,
                descriptor.getScope(),
                descriptor.getResource(),
                descriptor.getDependencies() == null ? List.of() : Arrays.asList(descriptor.getDependencies()),
                descriptor.getAliases() == null ? List.of() : Arrays.asList(descriptor.getAliases()),
                classify(name, type));
    }

    private String classify(String name, String type) {
        if (type == null) {
            return "OTHER";
        }
        if (selfDataFilter.isBootUiClassOrResource(type)) {
            return "BOOTUI";
        }
        if (type.startsWith("org.springframework.")) {
            return "FRAMEWORK";
        }
        if (type.startsWith("java.") || type.startsWith("jakarta.")) {
            return "PLATFORM";
        }
        return "APPLICATION";
    }
}
