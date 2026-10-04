package io.github.jdubois.bootui.engine.model;

/**
 * Calls from one application class's instrumented methods to another's, as the BootUI agent's code paths observed them
 * in this run's route trees ({@code docs/PLAN-v2.md} §5.14, M5-4c): a parent method node and its child, by their classes.
 *
 * @param callerClass the calling method's class, by binary name
 * @param calleeClass the called method's class
 * @param calls how many calls the route trees counted
 */
public record ClassInvocation(String callerClass, String calleeClass, long calls) {}
