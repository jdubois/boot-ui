package io.github.jdubois.bootui.sample.catalog;

import java.io.Serializable;

public record ProductSummary(Long id, String name, String category, boolean active) implements Serializable {

    static ProductSummary from(Product product) {
        return new ProductSummary(product.getId(), product.getName(), product.getCategory(), product.isActive());
    }
}
