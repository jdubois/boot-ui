package io.github.jdubois.bootui.sample.catalog;

import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SampleCatalog {

    private final ProductRepository products;

    public SampleCatalog(ProductRepository products) {
        this.products = products;
    }

    @Cacheable(cacheNames = "sample-greetings", key = "#greeting + ':' + #retries")
    public String greeting(String greeting, int retries) {
        return greeting + ", BootUI! (retries=" + retries + ")";
    }

    @Cacheable(cacheNames = "sample-products", key = "'active'", unless = "#result.isEmpty()")
    public List<ProductSummary> activeProducts() {
        return products.findByActiveTrueOrderByNameAsc().stream()
                .map(ProductSummary::from)
                .toList();
    }

    public List<ProductSummary> searchProducts(String term) {
        // Intentionally uncached so every call runs a live SQL SELECT for the SQL Trace panel to capture.
        return products.searchByName(term).stream().map(ProductSummary::from).toList();
    }

    /**
     * Runs an uncached live {@code SELECT} so a secured (ROLE_ADMIN) request also produces SQL. This
     * lets the BootUI Live Activity profiler show a single request correlated to both a Spring
     * Security event and the SQL statements it executed.
     */
    public List<ProductSummary> securedCatalog() {
        return products.findByActiveTrueOrderByNameAsc().stream()
                .map(ProductSummary::from)
                .toList();
    }

    @CacheEvict(cacheNames = "sample-products", allEntries = true)
    public void evictProducts() {}

    /**
     * Runs a live {@code count} query and then holds the JDBC connection for {@code delayMillis}
     * inside a read-only transaction. Calling this concurrently checks out several HikariCP
     * connections at once so the BootUI Database Connection Pools panel shows real activity.
     */
    @Transactional(readOnly = true)
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
