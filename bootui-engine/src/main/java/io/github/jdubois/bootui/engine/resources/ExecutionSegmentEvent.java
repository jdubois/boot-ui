package io.github.jdubois.bootui.engine.resources;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One segment of a request's work on a thread, as a JFR event ({@code docs/PLAN-v2.md} §5.11): it begins when the
 * request's scope opens on the thread and is committed when it closes there, so JFR's own samples join it by thread and
 * interval in JFR's clock. It is disabled unless a <b>Profile resources</b> session enables it, and only created while
 * one runs ({@link JfrSegments}).
 */
@Name(JfrProfiler.SEGMENT_EVENT)
@Label("BootUI Execution Segment")
@Category("BootUI")
@Description("A segment of a request's work on one thread, for BootUI's Profile resources session")
@Enabled(false)
@StackTrace(false)
final class ExecutionSegmentEvent extends jdk.jfr.Event {

    @Label("Request Id")
    String requestId;

    /** The segment's own thread: a segment closed by another thread is committed by that one, so JFR's differs. */
    @Label("Segment Thread Id")
    long segmentThreadId;
}
