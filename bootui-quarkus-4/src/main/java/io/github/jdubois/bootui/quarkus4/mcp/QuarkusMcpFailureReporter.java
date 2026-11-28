package io.github.jdubois.bootui.quarkus4.mcp;

import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import jakarta.inject.Singleton;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Quarkus logging bridge for unexpected failures contained by the shared MCP boundary. */
@Singleton
public class QuarkusMcpFailureReporter implements McpFailureReporter {

    private static final Logger LOG = Logger.getLogger(QuarkusMcpFailureReporter.class.getName());

    @Override
    public void report(String operation, Throwable failure) {
        LOG.log(Level.SEVERE, "BootUI MCP failure while " + operation, failure);
    }
}
