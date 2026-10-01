package io.github.jdubois.bootui.spi;

/**
 * The identity of the work a thread is doing right now, stamped on every runtime event when the event is captured.
 *
 * <p>BootUI 1.x re-derived a request's identity after the fact, from trace ids, serving threads, and time windows, so
 * identical requests that overlap could not be told apart. A correlation context is opened by the adapter that owns the
 * request, at the moment the request starts, and every recorder copies it into the event it records. See
 * {@code docs/PLAN-v2.md} §5.1.</p>
 *
 * <p>Every field is nullable: a field is {@code null} when it does not apply or is not known yet. {@link #NONE} is the
 * context of work that no request or execution owns, such as startup, and is never {@code null} itself. Instances are
 * immutable; the {@code with…} methods return a copy.</p>
 *
 * @param requestId BootUI's own identity for one inbound request, unique within the process, present whether or not
 *     tracing is active
 * @param executionId BootUI's identity for a scheduled run or a consumed message, the non-request anchors
 * @param traceId the distributed-trace id, when a tracer is active
 * @param spanId the current span id, when a tracer is active
 * @param routeTemplate the matched route template, such as {@code /api/orders/{id}}, once routing has happened
 * @param handler the handler that serves the request, such as {@code OrderController#get}, once routing has happened
 * @param transactionId BootUI's id of the innermost active transaction
 * @param dataSource the name of the data source of the statement being executed
 * @param linkedTraceId the trace a consumed message's {@code traceparent} names, linking its execution to the work
 *     that sent it, or {@code null}
 * @param bootUi whether the work is BootUI's own, such as the SQL a BootUI panel runs while serving its request, which
 *     the runtime journal never records ({@code docs/PLAN-v2.md} §5.2)
 */
public record CorrelationContext(
        String requestId,
        String executionId,
        String traceId,
        String spanId,
        String routeTemplate,
        String handler,
        String transactionId,
        String dataSource,
        String linkedTraceId,
        boolean bootUi) {

    /** The context of work that no request or execution owns. */
    public static final CorrelationContext NONE =
            new CorrelationContext(null, null, null, null, null, null, null, null, null, false);

    /**
     * The context of BootUI's own requests: no request id, since BootUI never shows its own traffic, but marked so that
     * the work they do, wherever it runs, stays out of the runtime journal.
     */
    public static final CorrelationContext BOOTUI =
            new CorrelationContext(null, null, null, null, null, null, null, null, null, true);

    /** A context of the application's own work. */
    public CorrelationContext(
            String requestId,
            String executionId,
            String traceId,
            String spanId,
            String routeTemplate,
            String handler,
            String transactionId,
            String dataSource) {
        this(requestId, executionId, traceId, spanId, routeTemplate, handler, transactionId, dataSource, null, false);
    }

    /** A context for a new inbound request. */
    public static CorrelationContext forRequest(String requestId) {
        return NONE.withRequestId(requestId);
    }

    /** A context for a new scheduled run or consumed message. */
    public static CorrelationContext forExecution(String executionId) {
        return NONE.withExecutionId(executionId);
    }

    /** Whether this context carries no identity at all. */
    public boolean isEmpty() {
        return requestId == null
                && executionId == null
                && traceId == null
                && spanId == null
                && routeTemplate == null
                && handler == null
                && transactionId == null
                && dataSource == null
                && linkedTraceId == null
                && !bootUi;
    }

    public CorrelationContext withRequestId(String value) {
        return new CorrelationContext(
                value,
                executionId,
                traceId,
                spanId,
                routeTemplate,
                handler,
                transactionId,
                dataSource,
                linkedTraceId,
                bootUi);
    }

    public CorrelationContext withExecutionId(String value) {
        return new CorrelationContext(
                requestId,
                value,
                traceId,
                spanId,
                routeTemplate,
                handler,
                transactionId,
                dataSource,
                linkedTraceId,
                bootUi);
    }

    public CorrelationContext withTrace(String trace, String span) {
        return new CorrelationContext(
                requestId,
                executionId,
                trace,
                span,
                routeTemplate,
                handler,
                transactionId,
                dataSource,
                linkedTraceId,
                bootUi);
    }

    public CorrelationContext withRoute(String template, String handlerName) {
        return new CorrelationContext(
                requestId,
                executionId,
                traceId,
                spanId,
                template,
                handlerName,
                transactionId,
                dataSource,
                linkedTraceId,
                bootUi);
    }

    public CorrelationContext withTransactionId(String value) {
        return new CorrelationContext(
                requestId,
                executionId,
                traceId,
                spanId,
                routeTemplate,
                handler,
                value,
                dataSource,
                linkedTraceId,
                bootUi);
    }

    /** This context linked to the trace a consumed message's {@code traceparent} names. */
    public CorrelationContext withLinkedTraceId(String value) {
        return new CorrelationContext(
                requestId,
                executionId,
                traceId,
                spanId,
                routeTemplate,
                handler,
                transactionId,
                dataSource,
                value,
                bootUi);
    }

    public CorrelationContext withDataSource(String value) {
        return new CorrelationContext(
                requestId,
                executionId,
                traceId,
                spanId,
                routeTemplate,
                handler,
                transactionId,
                value,
                linkedTraceId,
                bootUi);
    }
}
