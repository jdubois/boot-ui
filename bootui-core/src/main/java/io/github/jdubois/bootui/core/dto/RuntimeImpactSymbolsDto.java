package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The symbols change impact can check that match what a developer typed ({@code docs/PLAN-v2.md} §5.7), best matches
 * first, so the impact box suggests routes, beans, repositories, tables, caches, hosts, and events as they type.
 *
 * @param available whether the run's model could be read
 * @param unavailableReason why it could not, or {@code null}
 * @param query what was typed
 * @param symbols the matching symbols, at most {@value #MAX_SYMBOLS}
 * @param total how many symbols match, beyond those listed
 */
public record RuntimeImpactSymbolsDto(
        boolean available, String unavailableReason, String query, List<RuntimeImpactSymbolDto> symbols, int total) {

    /** The symbols a response lists at most. */
    public static final int MAX_SYMBOLS = 20;

    public RuntimeImpactSymbolsDto {
        symbols = DtoCollections.immutableCopy(symbols);
    }
}
