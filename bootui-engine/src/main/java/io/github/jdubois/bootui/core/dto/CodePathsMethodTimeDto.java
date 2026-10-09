package io.github.jdubois.bootui.core.dto;

/**
 * One application method's self time in a route or request tree ({@code docs/PLAN-v2.md} §5.14), every node of the
 * method summed.
 *
 * @param method the method's key, {@code class#name+descriptor}
 * @param className its class
 * @param methodName its name
 * @param selfMillis its self time per request: its time outside its recorded child methods, which includes the SQL, REST,
 *     and other recorded calls it waited on
 * @param share its self time's share of the request's own time, in percent
 */
public record CodePathsMethodTimeDto(
        String method, String className, String methodName, double selfMillis, double share) {}
