package io.github.jdubois.bootui.client;

/** Thrown when a response is not the JSON it was expected to be. */
public class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    public JsonParseException(String message, int position) {
        super(message + " at offset " + position);
        this.position = position;
    }

    /** The character offset the parse failed at. */
    public int position() {
        return position;
    }
}
