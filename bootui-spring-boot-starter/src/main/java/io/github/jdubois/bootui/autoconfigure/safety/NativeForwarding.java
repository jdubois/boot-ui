package io.github.jdubois.bootui.autoconfigure.safety;

import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.core.env.Environment;

/** Mirrors Boot's explicit strategy and cloud-platform native-forwarding default. */
public final class NativeForwarding {

    private NativeForwarding() {}

    public static boolean enabled(Environment environment) {
        String strategy = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("server.forward-headers-strategy", String.class)
                .orElse(null);
        if (strategy != null) {
            return "native".equalsIgnoreCase(strategy.trim());
        }
        CloudPlatform platform = CloudPlatform.getActive(environment);
        return platform != null && platform.isUsingForwardHeaders();
    }
}
