package io.github.jdubois.bootui.core.dto;

/**
 * The shape of one argument or return value a method probe recorded ({@code docs/PLAN-v2.md} §5.14, M5-8, D37, D44):
 * its type and, for an allowlist of JDK types, a size, a length, a presence, or an enum constant's name, never the value
 * itself, and read by the BootUI agent without running any application code.
 *
 * @param kind {@code null}; {@code primitive} (a primitive parameter or return value, never its value); {@code string}
 *     ({@code size} is its length); {@code collection}, {@code map}, or {@code array} ({@code size} is its size or
 *     length); {@code optional} ({@code present}); {@code enum} ({@code constant}); {@code type} (any other value, its
 *     type only: boxed numbers, booleans, application objects, and collections outside the allowlist); or
 *     {@code unknown}, when the agent's transport lost it
 * @param declaredType the declared parameter or return type, from the method's descriptor
 * @param type the value's runtime type, or {@code null} for {@code null}, {@code unknown}, or a type the agent could not
 *     name
 * @param size a string's length, a collection's or a map's size, or an array's length; at most 268,435,455; or
 *     {@code null} when not applicable or withheld
 * @param present whether an {@code optional} holds a value, else {@code null}
 * @param constant an enum constant's name, or {@code null} when not applicable or withheld
 * @param withheld whether the live exposure withholds a detail derived from the value: a string's length, a
 *     {@code char[]} or {@code byte[]} length, or an enum constant's name, shown with {@code bootui.expose-values=FULL}
 *     only
 */
public record CodePathsValueShapeDto(
        String kind,
        String declaredType,
        String type,
        Integer size,
        Boolean present,
        String constant,
        boolean withheld) {}
