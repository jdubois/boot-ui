package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * What one request touched, as its recorded events name it ({@code docs/PLAN-v2.md} §5.3). Tables are read from the
 * statements' text, so a table a view or a stored procedure reaches is not listed. Each list keeps first-seen order.
 *
 * @param tables tables its statements named
 * @param dataSources data sources its statements and connections used
 * @param transactions transactional methods it ran, each with {@code committed} or {@code rolled back}
 * @param caches caches it accessed, with their operations
 * @param messages destinations it sent to, with their broker
 * @param restCalls hosts it called
 * @param logTemplates the templates of the {@code WARN} and {@code ERROR} log events it emitted
 * @param models the AI models it called, with their provider, joined to it by trace id
 */
public record TouchedResourcesDto(
        List<String> tables,
        List<String> dataSources,
        List<String> transactions,
        List<String> caches,
        List<String> messages,
        List<String> restCalls,
        List<String> logTemplates,
        List<String> models) {

    /** Nothing touched. */
    public static final TouchedResourcesDto NONE = new TouchedResourcesDto(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public TouchedResourcesDto {
        tables = DtoCollections.immutableCopy(tables);
        dataSources = DtoCollections.immutableCopy(dataSources);
        transactions = DtoCollections.immutableCopy(transactions);
        caches = DtoCollections.immutableCopy(caches);
        messages = DtoCollections.immutableCopy(messages);
        restCalls = DtoCollections.immutableCopy(restCalls);
        logTemplates = DtoCollections.immutableCopy(logTemplates);
        models = DtoCollections.immutableCopy(models);
    }
}
