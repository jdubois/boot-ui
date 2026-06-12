package io.github.jdubois.bootui.sample.catalog;

import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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

    @CacheEvict(cacheNames = "sample-products", allEntries = true)
    public void evictProducts() {}
}
