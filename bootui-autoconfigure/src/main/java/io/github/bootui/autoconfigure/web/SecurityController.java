package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.SecurityAuthDto;
import io.github.bootui.core.BootUiDtos.SecurityChainDto;
import io.github.bootui.core.BootUiDtos.SecurityReport;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
@RequestMapping("/bootui/api/security")
public class SecurityController {

    private final ObjectProvider<List<SecurityFilterChain>> securityFilterChainsProvider;

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public SecurityController(ObjectProvider<List<SecurityFilterChain>> securityFilterChainsProvider,
                              ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.securityFilterChainsProvider = securityFilterChainsProvider;
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @GetMapping
    public SecurityReport security() {
        ListableBeanFactory beanFactory = beanFactoryProvider.getIfAvailable();
        if (beanFactory == null) {
            List<SecurityFilterChain> fallbackChains = securityFilterChainsProvider.getIfAvailable(List::of);
            return new SecurityReport(true, toChainDtos(fallbackChains), new SecurityAuthDto(List.of(), null, false));
        }

        Map<String, SecurityFilterChain> chainBeans = beanFactory.getBeansOfType(SecurityFilterChain.class);
        List<SecurityChainDto> chains = toChainDtos(beanFactory, chainBeans);

        List<String> providerTypes = beanFactory.getBeansOfType(AuthenticationProvider.class).values().stream()
                .map(provider -> provider.getClass().getSimpleName())
                .sorted()
                .toList();
        String userDetailsServiceType = beanFactory.getBeansOfType(UserDetailsService.class).values().stream()
                .map(service -> service.getClass().getSimpleName())
                .sorted()
                .findFirst()
                .orElse(null);
        boolean hasAutoConfiguredUser = beanFactory.containsBean("userDetailsServiceAutoConfiguration")
                || beanFactory.containsBean("inMemoryUserDetailsManager");

        return new SecurityReport(true, chains,
                new SecurityAuthDto(providerTypes, userDetailsServiceType, hasAutoConfiguredUser));
    }

    private List<SecurityChainDto> toChainDtos(List<SecurityFilterChain> chains) {
        List<SecurityFilterChain> sortedChains = new ArrayList<>(chains);
        AnnotationAwareOrderComparator.sort(sortedChains);
        return sortedChains.stream()
                .map(chain -> toChainDto(Ordered.LOWEST_PRECEDENCE, chain))
                .toList();
    }

    private List<SecurityChainDto> toChainDtos(ListableBeanFactory beanFactory, Map<String, SecurityFilterChain> chainBeans) {
        List<NamedSecurityChain> chains = new ArrayList<>();
        for (Map.Entry<String, SecurityFilterChain> entry : chainBeans.entrySet()) {
            chains.add(new NamedSecurityChain(entry.getKey(), entry.getValue(), orderOf(beanFactory, entry.getKey(), entry.getValue())));
        }
        chains.sort(Comparator.comparingInt(NamedSecurityChain::order));
        return chains.stream()
                .map(chain -> toChainDto(chain.order(), chain.chain()))
                .toList();
    }

    private int orderOf(ListableBeanFactory beanFactory, String beanName, SecurityFilterChain chain) {
        Order order = beanFactory.findAnnotationOnBean(beanName, Order.class);
        if (order != null) {
            return order.value();
        }
        if (chain instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        return OrderUtils.getOrder(chain.getClass(), Ordered.LOWEST_PRECEDENCE);
    }

    private SecurityChainDto toChainDto(int order, SecurityFilterChain chain) {
        if (!(chain instanceof DefaultSecurityFilterChain defaultChain)) {
            return new SecurityChainDto(order, chain.toString(), List.of(), false, "UNKNOWN", false);
        }
        List<Filter> filters = defaultChain.getFilters();
        List<String> filterNames = filters.stream()
                .map(filter -> filter.getClass().getSimpleName())
                .toList();
        boolean csrfEnabled = filters.stream()
                .anyMatch(filter -> filter.getClass().getName().contains("CsrfFilter"));
        boolean corsEnabled = filters.stream()
                .anyMatch(filter -> filter.getClass().getName().contains("CorsFilter"));
        boolean stateless = filters.stream()
                .anyMatch(filter -> "DisableEncodeUrlFilter".equals(filter.getClass().getSimpleName()));
        return new SecurityChainDto(
                order,
                defaultChain.getRequestMatcher().toString(),
                filterNames,
                csrfEnabled,
                stateless ? "STATELESS" : "UNKNOWN",
                corsEnabled);
    }

    private record NamedSecurityChain(String beanName, SecurityFilterChain chain, int order) {
    }
}
