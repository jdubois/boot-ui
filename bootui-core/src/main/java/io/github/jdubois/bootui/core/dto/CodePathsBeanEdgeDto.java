package io.github.jdubois.bootui.core.dto;

/**
 * One bean-to-bean edge of Code Paths' <b>Beans at runtime</b> ({@code docs/PLAN-v2.md} §5.14, M5-4c): a dependency the
 * application declares, a call the BootUI agent's code paths observed in this run, or both.
 *
 * @param from the calling or depending bean's name
 * @param fromType its class, or {@code null} when unknown
 * @param to the called or injected bean's name
 * @param toType its class, or {@code null} when unknown
 * @param declared whether {@code from} declares a dependency on {@code to}, as the Beans panel lists it
 * @param observed whether a method of {@code from} called one of {@code to} in this run's route trees
 * @param calls how many calls the route trees counted, their first requests included, 0 when none
 * @param observable whether a call from {@code from} to {@code to} would be observed: both beans' classes are ones the
 *     code-paths sensor instruments, and none of their methods was adaptively excluded; only then is a declared
 *     dependency without calls "not called in this run"
 * @param unobservableReason why a call between them would not be observed, or {@code null} when it would
 */
public record CodePathsBeanEdgeDto(
        String from,
        String fromType,
        String to,
        String toType,
        boolean declared,
        boolean observed,
        long calls,
        boolean observable,
        String unobservableReason) {}
