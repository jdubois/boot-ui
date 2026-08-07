package io.github.jdubois.bootui.engine.crac.fixtures;

/** A token-related URL is not itself a captured credential. */
public class TokenUrlHolder {

    private final String tokenUrl = "https://localhost/token";

    public String tokenUrl() {
        return tokenUrl;
    }
}
