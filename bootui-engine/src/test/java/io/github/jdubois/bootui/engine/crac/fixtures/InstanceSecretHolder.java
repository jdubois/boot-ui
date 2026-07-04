package io.github.jdubois.bootui.engine.crac.fixtures;

/**
 * Captures a secret in a singleton-bean instance field rather than a static one (CRAC-SECRET-001),
 * for example an {@code @Value}-injected credential. The checkpoint freezes the field's value just
 * as completely as it would a static field.
 */
public class InstanceSecretHolder {

    private String apiToken = "placeholder";

    public String getApiToken() {
        return apiToken;
    }
}
