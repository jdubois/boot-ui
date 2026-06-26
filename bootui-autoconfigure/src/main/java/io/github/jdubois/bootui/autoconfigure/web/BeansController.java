package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeanDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeansDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.ContextBeansDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/beans")
public class BeansController {

    private final ObjectProvider<BeansEndpoint> endpoint;

    private final BootUiSelfDataFilter selfDataFilter;

    public BeansController(ObjectProvider<BeansEndpoint> endpoint) {
        this(endpoint, BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public BeansController(ObjectProvider<BeansEndpoint> endpoint, BootUiSelfDataFilter selfDataFilter) {
        this.endpoint = endpoint;
        this.selfDataFilter = selfDataFilter;
    }

    @GetMapping
    public BeanList beans(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "classification", required = false) String classification,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        BeansEndpoint be = endpoint.getIfAvailable();
        if (be == null) {
            return new BeanList(0, List.of());
        }
        BeansDescriptor descriptor = be.beans();
        List<BeanSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, ContextBeansDescriptor> ctxEntry :
                descriptor.getContexts().entrySet()) {
            for (Map.Entry<String, BeanDescriptor> beanEntry :
                    ctxEntry.getValue().getBeans().entrySet()) {
                BeanDescriptor beanDescriptor = beanEntry.getValue();
                if (!selfDataFilter.shouldIncludeBean(
                        beanEntry.getKey(), beanDescriptor.getType(), beanDescriptor.getResource())) {
                    continue;
                }
                summaries.add(toSummary(beanEntry.getKey(), beanEntry.getValue()));
            }
        }
        summaries.sort(Comparator.comparing(BeanSummary::name));
        String normalizedQuery = PagedList.normalize(query);
        String normalizedClassification = PagedList.normalize(classification).toUpperCase(Locale.ROOT);
        PagedList.Result<BeanSummary> page = PagedList.from(
                summaries,
                bean -> matchesClassification(bean, normalizedClassification) && matchesQuery(bean, normalizedQuery),
                offset,
                limit);
        return new BeanList(summaries.size(), page.items(), page.page());
    }

    private BeanSummary toSummary(String name, BeanDescriptor descriptor) {
        String type = descriptor.getType() == null ? null : descriptor.getType().getName();
        return new BeanSummary(
                name,
                type,
                descriptor.getScope(),
                descriptor.getResource(),
                descriptor.getDependencies() == null ? List.of() : Arrays.asList(descriptor.getDependencies()),
                descriptor.getAliases() == null ? List.of() : Arrays.asList(descriptor.getAliases()),
                classify(name, type));
    }

    private String classify(String name, String type) {
        if (type == null) {
            return "OTHER";
        }
        if (selfDataFilter.isBootUiClassOrResource(type)) {
            return "BOOTUI";
        }
        if (type.startsWith("org.springframework.")) {
            return "FRAMEWORK";
        }
        if (type.startsWith("java.") || type.startsWith("jakarta.")) {
            return "PLATFORM";
        }
        return "APPLICATION";
    }

    private boolean matchesClassification(BeanSummary bean, String classification) {
        return classification.isEmpty() || classification.equals(bean.classification());
    }

    private boolean matchesQuery(BeanSummary bean, String query) {
        return PagedList.contains(bean.name(), query) || PagedList.contains(bean.type(), query);
    }
}
