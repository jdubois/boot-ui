package io.github.jdubois.bootui.autoconfigure.architecture;

import io.github.jdubois.bootui.spi.BasePackageProvider;
import java.util.List;
import org.springframework.beans.factory.BeanFactory;

/**
 * Spring Boot {@link BasePackageProvider}: resolves the host application's base packages from
 * {@code AutoConfigurationPackages} (the {@code @SpringBootApplication} package(s)) via
 * {@link ArchitecturePackages}. Read live on every scan and degrading to an empty list when the base
 * packages cannot be determined, so the advisors degrade to a stable "nothing to analyse" report.
 */
public final class SpringBasePackageProvider implements BasePackageProvider {

    private final BeanFactory beanFactory;

    public SpringBasePackageProvider(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public List<String> basePackages() {
        return ArchitecturePackages.detect(beanFactory);
    }
}
