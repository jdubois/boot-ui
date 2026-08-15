package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.io.IOException;
import java.io.InputStream;

public class ModuleResourceLoader {

    public InputStream load(Module module, String name) throws IOException {
        return module.getResourceAsStream(name);
    }
}
