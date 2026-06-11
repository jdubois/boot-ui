package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

/** Captures a secret in a static field (CRAC-SECRET-001). */
public class SecretHolder {

    static String apiToken = "placeholder";

    public String getApiToken() {
        return apiToken;
    }
}
