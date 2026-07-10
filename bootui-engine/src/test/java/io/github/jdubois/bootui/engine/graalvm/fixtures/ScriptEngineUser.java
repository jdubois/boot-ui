package io.github.jdubois.bootui.engine.graalvm.fixtures;

import javax.script.ScriptEngineManager;

/** Triggers GRAAL-JDK-002 by discovering JSR-223 engines at run time. */
public class ScriptEngineUser {

    public ScriptEngineManager engines() {
        return new ScriptEngineManager();
    }
}
