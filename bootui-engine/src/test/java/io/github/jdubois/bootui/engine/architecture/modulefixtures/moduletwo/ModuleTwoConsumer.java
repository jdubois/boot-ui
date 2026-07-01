package io.github.jdubois.bootui.engine.architecture.modulefixtures.moduletwo;

import io.github.jdubois.bootui.engine.architecture.modulefixtures.moduleone.internal.ModuleOneInternal;

/** Belongs to module two but reaches into module one's internal package. */
public class ModuleTwoConsumer {

    private final ModuleOneInternal internal = new ModuleOneInternal();

    public String borrow() {
        return internal.secret();
    }
}
