/**
 * Resource correlation ({@code docs/PLAN-v2.md} §5.11): each request's CPU time, allocated bytes, and completed GC
 * pauses, measured by identity over the segments its work runs on threads, and the {@code gc} source's events, which
 * requests join by collector and collection id. {@code com.sun.management} is reached only through guarded nested
 * classes, so every reading reports itself unavailable on a runtime without it.
 */
package io.github.jdubois.bootui.engine.resources;
