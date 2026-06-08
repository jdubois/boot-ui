package io.github.jdubois.bootui.autoconfigure.architecture.modulefixtures.moduleone.internal;

/** Implementation detail of module one; only module one may depend on it. */
public class ModuleOneInternal {

    public String secret() {
        return "internal";
    }
}
