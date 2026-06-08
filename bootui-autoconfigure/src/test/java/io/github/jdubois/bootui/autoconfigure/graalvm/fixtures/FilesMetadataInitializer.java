package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Calls only metadata-style java.nio.file.Files helpers from a static initializer and therefore must
 * NOT trigger GRAAL-INIT-001, which now matches filesystem-touching Files I/O methods only.
 */
public final class FilesMetadataInitializer {

    static final boolean PRESENT = Files.exists(Path.of("/tmp"));

    private FilesMetadataInitializer() {}
}
