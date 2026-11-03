package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.threads.ThreadAnalysisException;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The same retained-evidence handlers on every request stack. */
public final class ThreadAnalysisMcpTools {
    private ThreadAnalysisMcpTools() {}

    public static List<McpTool> tools(ThreadAnalysisService service, McpToolCatalog.Stack stack) {
        return List.of(
                tool("capture_thread_snapshot", stack, args -> service.capture()),
                tool("get_thread_snapshots", stack, args -> service.list(limit(args))),
                tool("get_thread_analysis", stack, args -> service.analysis(args.id(), 0, limit(args))),
                tool("get_thread_detail", stack, args -> service.thread(args.id())),
                tool("clear_thread_snapshots", stack, args -> Map.of("clearedCaptures", service.clear())),
                tool("start_thread_observation", stack, args -> service.observe()),
                tool("get_thread_observation", stack, args -> service.observation(args.id(), 0, limit(args))),
                tool("cancel_thread_observation", stack, args -> {
                    service.cancel(args.id());
                    return service.observation(args.id(), 0, limit(args));
                }));
    }

    private static int limit(McpArguments args) {
        return Math.min(1000, args.limit());
    }

    private static McpTool tool(String name, McpToolCatalog.Stack stack, Function<McpArguments, Object> handler) {
        McpToolCatalog.Entry entry = McpToolCatalog.require(name, stack);
        String description = stack == McpToolCatalog.Stack.QUARKUS
                ? McpToolDescriptions.quarkus(name)
                : McpToolDescriptions.spring(name);
        return new McpTool(name, description, entry.schema(), entry.panelId(), entry.action(), args -> {
            try {
                return handler.apply(args);
            } catch (ThreadAnalysisException failure) {
                if (!McpToolClientExceptions.isClientError(failure.status())) {
                    throw failure;
                }
                throw new McpToolClientException(failure.status(), failure.getMessage());
            }
        });
    }
}
