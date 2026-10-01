/**
 * The runtime model ({@code docs/PLAN-v2.md} §5.4): a small typed graph of what a run contains and did, built on read
 * from the runtime journal and a per-run snapshot of the application's structure. It is an in-memory adjacency
 * structure of interned ids, not a database, and has no query language: callers reach it through named operations.
 * Declared structure, observed execution, and shared access to a resource are kept apart and never merged into one
 * claim.
 */
package io.github.jdubois.bootui.engine.model;
