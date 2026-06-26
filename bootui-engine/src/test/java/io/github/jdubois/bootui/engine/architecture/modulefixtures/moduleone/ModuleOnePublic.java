package io.github.jdubois.bootui.engine.architecture.modulefixtures.moduleone;

import io.github.jdubois.bootui.engine.architecture.modulefixtures.moduleone.internal.ModuleOneInternal;

/** Public API of module one; allowed to depend on its own internal package. */
public class ModuleOnePublic {

    private final ModuleOneInternal internal = new ModuleOneInternal();

    public String value() {
        return internal.secret();
    }
}
