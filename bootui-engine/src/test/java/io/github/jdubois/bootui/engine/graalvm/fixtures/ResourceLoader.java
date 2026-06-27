package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.io.InputStream;

/** Triggers GRAAL-RES-001 by loading a resource by name at runtime. */
public class ResourceLoader {

    public InputStream load() {
        return getClass().getResourceAsStream("/data/values.txt");
    }
}
