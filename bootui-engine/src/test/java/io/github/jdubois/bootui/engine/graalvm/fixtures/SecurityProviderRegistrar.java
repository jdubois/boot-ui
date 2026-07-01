package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.security.Provider;
import java.security.Security;

/** Triggers GRAAL-SEC-001 by calling Security.addProvider at run time. */
public class SecurityProviderRegistrar {

    public void register(Provider provider) {
        Security.addProvider(provider);
    }
}
