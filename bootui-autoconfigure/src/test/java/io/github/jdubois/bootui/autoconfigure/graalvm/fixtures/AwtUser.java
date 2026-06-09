package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.awt.Color;

/** Triggers GRAAL-AWT-001 by depending on java.awt.Color. */
public class AwtUser {

    public int red(Color color) {
        return color.getRed();
    }
}
