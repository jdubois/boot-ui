package io.github.jdubois.bootui.engine.correlation;

/** The part of a request's processing a piece of work ran in ({@code docs/PLAN-v2.md} §5.1). */
public enum RequestPhase {

    /** Before the handler: servlet filters, WebFilters, security, and routing. */
    FILTERS,

    /** Inside the handler method. */
    HANDLER,

    /** After the handler returned: body serialization or view rendering, where lazy loading surfaces. */
    RESPONSE
}
