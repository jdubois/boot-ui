package io.github.jdubois.bootui.engine.model;

import java.util.Objects;

/**
 * A node of the runtime model.
 *
 * @param id its interned id, dense from {@code 0}
 * @param type what it is
 * @param key its name within its type, such as {@code GET /api/orders/{id}} or {@code orders}
 */
public record ModelNode(int id, NodeType type, String key) {

    public ModelNode {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
    }
}
