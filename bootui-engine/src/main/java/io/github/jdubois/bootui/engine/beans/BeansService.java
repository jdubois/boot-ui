package io.github.jdubois.bootui.engine.beans;

import io.github.jdubois.bootui.core.dto.BeanGraphEdge;
import io.github.jdubois.bootui.core.dto.BeanGraphReport;
import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.BeanProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private static final int DEFAULT_GRAPH_LIMIT = 12;

    private static final int MAX_GRAPH_LIMIT = 50;

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

    /**
     * Returns a bounded, deterministic one-hop neighborhood around an exactly named bean.
     *
     * <p>Each side is capped independently so a high-fan-in bean cannot crowd out its dependencies. Edges
     * point from dependency to consumer. Dependency names that do not resolve to an inventory row are
     * reported separately rather than synthesized into misleading bean nodes.</p>
     */
    public BeanGraphReport graph(String focus, Integer limit) {
        if (provider == null || !provider.available()) {
            return BeanGraphReport.unavailable();
        }
        String normalizedFocus = focus == null ? "" : focus.trim();
        if (normalizedFocus.isEmpty()) {
            return BeanGraphReport.empty();
        }

        Map<String, BeanSummary> byName = new LinkedHashMap<>();
        provider.beans().stream()
                .sorted(Comparator.comparing(BeanSummary::name))
                .forEach(bean -> byName.putIfAbsent(bean.name(), bean));
        BeanSummary focused = byName.get(normalizedFocus);
        if (focused == null) {
            return BeanGraphReport.empty();
        }

        int boundedLimit = limit == null ? DEFAULT_GRAPH_LIMIT : Math.max(1, Math.min(limit, MAX_GRAPH_LIMIT));
        Set<String> dependencyNames = normalizedDependencies(focused);
        List<String> resolvedDependencyNames =
                dependencyNames.stream().filter(byName::containsKey).sorted().toList();
        List<String> allUnresolvedDependencyNames = dependencyNames.stream()
                .filter(name -> !byName.containsKey(name))
                .sorted()
                .toList();
        List<String> unresolvedDependencyNames =
                allUnresolvedDependencyNames.stream().limit(boundedLimit).toList();
        List<String> dependentNames = byName.values().stream()
                .filter(bean -> normalizedDependencies(bean).contains(focused.name()))
                .map(BeanSummary::name)
                .filter(name -> !name.equals(focused.name()))
                .sorted()
                .toList();

        List<BeanSummary> dependencies = limitedBeans(resolvedDependencyNames, byName, boundedLimit);
        List<BeanSummary> dependents = limitedBeans(dependentNames, byName, boundedLimit);
        List<BeanGraphEdge> edges = new ArrayList<>();
        dependencies.forEach(bean -> edges.add(new BeanGraphEdge(bean.name(), focused.name())));
        dependents.forEach(bean -> edges.add(new BeanGraphEdge(focused.name(), bean.name())));

        return new BeanGraphReport(
                true,
                focused,
                dependencies,
                dependents,
                List.copyOf(edges),
                unresolvedDependencyNames,
                Math.max(0, resolvedDependencyNames.size() - dependencies.size()),
                Math.max(0, dependentNames.size() - dependents.size()),
                Math.max(0, allUnresolvedDependencyNames.size() - unresolvedDependencyNames.size()));
    }

    private static Set<String> normalizedDependencies(BeanSummary bean) {
        Set<String> dependencies = new LinkedHashSet<>();
        if (bean.dependencies() == null) {
            return dependencies;
        }
        bean.dependencies().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .filter(name -> !name.equals(bean.name()))
                .forEach(dependencies::add);
        return dependencies;
    }

    private static List<BeanSummary> limitedBeans(List<String> names, Map<String, BeanSummary> byName, int limit) {
        return names.stream().limit(limit).map(byName::get).toList();
    }

    private static boolean matchesClassification(BeanSummary bean, String classification) {
        return classification.isEmpty() || classification.equals(bean.classification());
    }

    private static boolean matchesQuery(BeanSummary bean, String query) {
        return PagedList.contains(bean.name(), query) || PagedList.contains(bean.type(), query);
    }
}
