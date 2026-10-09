package io.github.jdubois.bootui.core.dto;

/**
 * Starts a method probe ({@code docs/PLAN-v2.md} §5.14, M5-8).
 *
 * @param method {@code binary.Class#name}, with the method's descriptor for an overloaded one, as in
 *     {@code com.example.PriceService#quote(I)J}
 * @param recordShapes whether the probe also records argument and return shapes (D44); absent means no
 */
public record CodePathsProbeRequest(String method, Boolean recordShapes) {

    /** A metadata-only probe. */
    public CodePathsProbeRequest(String method) {
        this(method, null);
    }
}
