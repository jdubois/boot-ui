package io.github.jdubois.bootui.quarkus.sample.catalog;

import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * Catalog service. Mirrors the Spring sample's {@code SampleCatalog}, but uses Quarkus Cache
 * ({@link CacheResult}/{@link CacheInvalidateAll}) so the Quarkus Cache panel has data, and Panache for
 * the live SQL the Database / SQL Trace panels capture.
 */
@ApplicationScoped
public class CatalogService {

    @Inject
    ProductRepository products;

    @CacheResult(cacheName = "sample-greetings")
    public String greeting(String greeting, int retries) {
        return greeting + ", BootUI! (retries=" + retries + ")";
    }

    @CacheResult(cacheName = "sample-products")
    @Transactional
    public List<ProductSummary> activeProducts() {
        return products.findActiveOrderByName().stream()
                .map(ProductSummary::from)
                .toList();
    }

    @Transactional
    public List<ProductSummary> searchProducts(String term) {
        // Intentionally uncached so every call runs a live SQL SELECT for the SQL Trace panel to capture.
        return products.searchByName(term).stream().map(ProductSummary::from).toList();
    }

    @Transactional
    public List<ProductSummary> securedCatalog() {
        return products.findActiveOrderByName().stream()
                .map(ProductSummary::from)
                .toList();
    }

    @CacheInvalidateAll(cacheName = "sample-products")
    public void evictProducts() {}

    /**
     * Runs a live {@code count} query and then holds the JDBC connection for {@code delayMillis} inside a
     * transaction. Calling this concurrently checks out several pool connections at once so the BootUI
     * Database Connection Pools panel shows real activity.
     */
    @Transactional
    public long countWithDelay(long delayMillis) {
        long count = products.count();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return count;
    }
}
