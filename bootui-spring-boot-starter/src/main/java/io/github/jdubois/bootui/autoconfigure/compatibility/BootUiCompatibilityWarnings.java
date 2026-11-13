package io.github.jdubois.bootui.autoconfigure.compatibility;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.NativeDetector;

/** Passive startup evidence; never instantiates a datasource or scans the application classpath. */
public final class BootUiCompatibilityWarnings {

    private BootUiCompatibilityWarnings() {}

    public static List<String> collect(ListableBeanFactory beans, ClassLoader loader) {
        return collect(beans, loader, "io.r2dbc.spi.ConnectionFactory");
    }

    static List<String> collect(ListableBeanFactory beans, ClassLoader loader, String connectionFactoryType) {
        List<String> warnings = new ArrayList<>();
        String agentProblem = AgentBridgeAccess.locate().problem();
        if (agentProblem != null) {
            warnings.add("BOOTUI-COMPAT-051: " + agentProblem);
        }
        if (NativeDetector.inNativeImage() || AotDetector.useGeneratedArtifacts()) {
            return List.copyOf(warnings);
        }
        try {
            Class<?> connectionFactory = Class.forName(connectionFactoryType, false, loader);
            if (beans.getBeanNamesForType(connectionFactory, true, false).length > 0) {
                warnings.add("BOOTUI-COMPAT-041: R2DBC queries are not captured by BootUI's JDBC SQL tracing."
                        + " Any JDBC observations cover only JDBC, even when both clients are configured;"
                        + " an empty SQL trace does not prove that no database queries ran.");
            }
        } catch (ClassNotFoundException ex) {
            // R2DBC is optional; its absence is not a compatibility warning.
        } catch (RuntimeException | LinkageError ex) {
            warnings.add("BOOTUI-COMPAT-042: R2DBC capture coverage could not be determined because its API"
                    + " could not be linked (" + ex.getClass().getSimpleName() + ").");
        }
        return List.copyOf(warnings);
    }
}
