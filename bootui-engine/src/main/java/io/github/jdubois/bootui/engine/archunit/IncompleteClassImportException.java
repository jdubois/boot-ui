package io.github.jdubois.bootui.engine.archunit;

/** An explicit application bytecode import encountered unreadable or unsupported class-file headers. */
final class IncompleteClassImportException extends IllegalStateException {

    IncompleteClassImportException(String message) {
        super(message);
    }
}
