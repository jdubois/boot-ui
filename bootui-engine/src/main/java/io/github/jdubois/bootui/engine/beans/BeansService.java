package io.github.jdubois.bootui.engine.beans;

import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.BeanProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Framework-neutral logic behind the Beans panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads the already-mapped, already-classified, already-self-filtered beans from a
 * {@link BeanProvider} (optional: {@code null} when the backend type is absent) and applies BootUI's
 * stable ordering (by name), classification + free-text filtering and server-side paging on top. The
 * mapping, self-data filtering and classification deliberately stay in the provider (the adapter):
 * classification is framework-specific (its {@code FRAMEWORK} prefixes differ per platform), so doing it
 * there keeps the engine neutral while leaving the resulting behavior byte-identical to the original
 * Spring controller.</p>
 */
public final class BeansService {

    private final BeanProvider provider;

    public BeansService(BeanProvider provider) {
        this.provider = provider;
    }

    /** The sorted, classification/query-filtered and paged bean list; empty when no backend is available. */
    public BeanList beans(String query, String classification, Integer offset, Integer limit) {
        if (provider == null || !provider.available()) {
            return new BeanList(0, List.of());
        }
        List<BeanSummary> beans = new ArrayList<>(provider.beans());
        beans.sort(Comparator.comparing(BeanSummary::name));
        String normalizedQuery = PagedList.normalize(query);
        String normalizedClassification = PagedList.normalize(classification).toUpperCase(Locale.ROOT);
        PagedList.Result<BeanSummary> page = PagedList.from(
                beans,
                bean -> matchesClassification(bean, normalizedClassification) && matchesQuery(bean, normalizedQuery),
                offset,
                limit);
        return new BeanList(beans.size(), page.items(), page.page());
    }

    private static boolean matchesClassification(BeanSummary bean, String classification) {
        return classification.isEmpty() || classification.equals(bean.classification());
    }

    private static boolean matchesQuery(BeanSummary bean, String query) {
        return PagedList.contains(bean.name(), query) || PagedList.contains(bean.type(), query);
    }
}
