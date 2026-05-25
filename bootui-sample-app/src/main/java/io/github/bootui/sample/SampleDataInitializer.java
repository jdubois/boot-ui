package io.github.bootui.sample;

import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SampleDataInitializer {

    @Bean
    ApplicationRunner seedSampleProducts(ProductRepository products) {
        return args -> {
            if (products.count() > 0) {
                return;
            }
            products.saveAll(List.of(
                    new Product("BootUI Starter", "library", true),
                    new Product("Sample Console", "demo", true),
                    new Product("Archived Prototype", "demo", false)));
        };
    }
}
