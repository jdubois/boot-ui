package io.github.jdubois.bootui.sample.catalog;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/** Seeds a handful of catalog rows on startup so the catalog/DB/SQL panels have data to show. */
@ApplicationScoped
public class CatalogSeeder {

    @Inject
    ProductRepository products;

    @Transactional
    void seed(@Observes StartupEvent event) {
        if (products.count() > 0) {
            return;
        }
        products.persist(List.of(
                new Product("BootUI Console", "console", true),
                new Product("Dev Services Bridge", "console", true),
                new Product("Sample Starter", "starter", true),
                new Product("Legacy Adapter", "starter", false)));
    }
}
