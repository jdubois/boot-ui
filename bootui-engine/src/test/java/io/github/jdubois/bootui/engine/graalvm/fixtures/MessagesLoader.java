package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.util.ResourceBundle;

/** Triggers GRAAL-RES-002 by loading a localized resource bundle. */
public class MessagesLoader {

    public ResourceBundle messages() {
        return ResourceBundle.getBundle("messages");
    }
}
