package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.BeanList;
import io.github.jdubois.bootui.core.BootUiDtos.BeanSummary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeanDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.BeansDescriptor;
import org.springframework.boot.actuate.beans.BeansEndpoint.ContextBeansDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/beans")
public class BeansController {

    private final ObjectProvider<BeansEndpoint> endpoint;

    private final int maxBeans;

    public BeansController(ObjectProvider<BeansEndpoint> endpoint) {
        this(endpoint, new BootUiProperties());
    }

    @Autowired
    public BeansController(ObjectProvider<BeansEndpoint> endpoint, BootUiProperties properties) {
        this.endpoint = endpoint;
        this.maxBeans = Math.max(0, properties.getLimits().getMaxBeans());
    }

    @GetMapping
    public BeanList beans() {
        BeansEndpoint be = endpoint.getIfAvailable();
        if (be == null) {
            return new BeanList(0, false, List.of());
        }
        BeansDescriptor descriptor = be.beans();
        List<BeanSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, ContextBeansDescriptor> ctxEntry : descriptor.getContexts().entrySet()) {
            for (Map.Entry<String, BeanDescriptor> beanEntry : ctxEntry.getValue().getBeans().entrySet()) {
                summaries.add(toSummary(beanEntry.getKey(), beanEntry.getValue()));
            }
        }
        summaries.sort(Comparator.comparing(BeanSummary::name));
        int total = summaries.size();
        boolean truncated = total > maxBeans;
        if (truncated) {
            summaries = new ArrayList<>(summaries.subList(0, maxBeans));
        }
        return new BeanList(total, truncated, summaries);
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
        if (type.startsWith("io.github.jdubois.bootui.")) {
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
}
