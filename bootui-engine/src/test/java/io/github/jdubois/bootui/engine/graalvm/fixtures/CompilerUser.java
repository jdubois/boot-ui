package io.github.jdubois.bootui.engine.graalvm.fixtures;

import javax.tools.ToolProvider;

/** Triggers GRAAL-JDK-001 by requesting javac at run time. */
public class CompilerUser {

    public boolean compilerAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }
}
