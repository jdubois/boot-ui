package io.github.jdubois.bootui.autoconfigure.crac;

import java.util.List;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;

/**
 * Resolves the host application's auto-configuration base packages, which are the
 * {@code @SpringBootApplication} package(s). Used both to gate panel availability and to bound the
 * CRaC readiness scan to the application's own code.
 */
final class CracPackages {

    private CracPackages() {}

    static boolean available(BeanFactory beanFactory) {
        return !detect(beanFactory).isEmpty();
    }

    static List<String> detect(BeanFactory beanFactory) {
        if (beanFactory == null) {
            return List.of();
        }
        try {
            if (!AutoConfigurationPackages.has(beanFactory)) {
                return List.of();
            }
            return List.copyOf(AutoConfigurationPackages.get(beanFactory));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
