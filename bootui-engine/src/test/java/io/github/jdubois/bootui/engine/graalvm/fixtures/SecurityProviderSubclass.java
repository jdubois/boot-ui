package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.security.Provider;

public final class SecurityProviderSubclass extends Provider {

    public SecurityProviderSubclass() {
        super("test", "1.0", "Test-only provider");
    }
}
