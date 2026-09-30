package io.github.jdubois.bootui.spi;

/**
 * The kind of thread a piece of work started on ({@code docs/PLAN-v2.md} §5.1), classified by the adapter that owns
 * the thread, from its type and the adapter's own state, never guessed from its name.
 */
public enum ThreadKind {

    /** A pooled platform thread running request or job work: a servlet container thread, a Vert.x worker. */
    WORKER,

    /** A virtual thread. */
    VIRTUAL_THREAD,

    /** A non-blocking I/O event loop: a Reactor Netty or Vert.x event loop. Blocking here stalls other work. */
    EVENT_LOOP,

    /** A Reactor scheduler thread that must not block, such as {@code parallel}. */
    REACTOR_SCHEDULER,

    /** Any other thread, or one the adapter cannot classify. */
    OTHER
}
