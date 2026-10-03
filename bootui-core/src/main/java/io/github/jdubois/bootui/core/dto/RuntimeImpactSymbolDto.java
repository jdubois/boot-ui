package io.github.jdubois.bootui.core.dto;

/**
 * One symbol change impact can check ({@code docs/PLAN-v2.md} §5.7): a node of the run's model that a developer can
 * name. Asking for {@code kind + " " + name}, such as {@code TABLE sample_products}, resolves to exactly this node.
 *
 * @param kind its node type, such as {@code ROUTE}, {@code BEAN}, {@code REPOSITORY}, {@code TABLE}, {@code CACHE},
 *     {@code HOST}, or {@code EVENT}
 * @param name its name within its kind, such as {@code GET /api/products} or {@code productRepository}
 * @param type a bean's or repository's class, or {@code null}
 */
public record RuntimeImpactSymbolDto(String kind, String name, String type) {}
