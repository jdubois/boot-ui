package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.client.RestTemplate;

/** Triggers GRAAL-REFLECT-006 by binding an application DTO inside a method body. */
public class BindingClient {

    private final RestTemplate restTemplate = new RestTemplate();

    public OrderDto order(String url) {
        return restTemplate.getForObject(url, OrderDto.class);
    }

    public String raw(String url) {
        List<Class<?>> unrelated = List.of(CleanComponent.class);
        return restTemplate.getForObject(url, String.class) + unrelated;
    }

    @RegisterReflectionForBinding(HintedOrderDto.class)
    public HintedOrderDto hinted(String url) {
        return restTemplate.getForObject(url, HintedOrderDto.class);
    }

    public ControllerOrderDto controllerContract(String url) {
        return restTemplate.getForObject(url, ControllerOrderDto.class);
    }
}
