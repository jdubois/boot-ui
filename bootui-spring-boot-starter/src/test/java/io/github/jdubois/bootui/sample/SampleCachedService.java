package io.github.jdubois.bootui.sample;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

public class SampleCachedService {

    @Cacheable(cacheNames = "sample-products", key = "#category", condition = "#category != null")
    public String productName(String category) {
        return category;
    }

    @CacheEvict(cacheNames = "sample-products", allEntries = true, beforeInvocation = true)
    public void resetProducts() {}
}
