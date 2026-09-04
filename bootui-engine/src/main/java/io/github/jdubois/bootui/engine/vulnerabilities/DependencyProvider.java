package io.github.jdubois.bootui.engine.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.util.List;

public interface DependencyProvider {

    List<DependencyDto> dependencies();

    /**
     * The resolved inventory plus its coverage over the application's real JAR set.
     *
     * <p>Defaults to treating {@link #dependencies()} as complete, which is correct for a provider reading a
     * fully-resolved build-time dependency model. A provider that probes the classpath &mdash; where
     * artifacts published without a Maven descriptor are invisible &mdash; must override this and report the
     * archives it could not identify, so the panel never presents a partial inventory as full coverage.</p>
     */
    default DependencyInventory inventory() {
        return DependencyInventory.complete(dependencies());
    }
}
