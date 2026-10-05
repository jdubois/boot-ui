package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

import java.io.FileInputStream;
import java.io.IOException;

/** Opens a file handle directly (CRAC-FILE-001). */
public class FileOpener {

    public FileInputStream open() throws IOException {
        return new FileInputStream("data.txt");
    }
}
