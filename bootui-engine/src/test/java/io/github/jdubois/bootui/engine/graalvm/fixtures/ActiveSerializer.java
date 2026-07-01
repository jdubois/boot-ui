package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/** Triggers GRAAL-SER-002 by serializing an object through the JDK serialization protocol. */
public class ActiveSerializer {

    public void write(OutputStream out, Object value) throws IOException {
        ObjectOutputStream stream = new ObjectOutputStream(out);
        stream.writeObject(value);
    }
}
