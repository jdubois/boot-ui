package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.io.Serializable;

/** Triggers GRAAL-SER-001 and is a reflection/serialization metadata candidate. */
public class SerializableModel implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;

    public SerializableModel(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
