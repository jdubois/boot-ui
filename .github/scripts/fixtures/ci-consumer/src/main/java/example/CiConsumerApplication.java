package example;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class CiConsumerApplication {

    private final String instance;

    public CiConsumerApplication(@Value("${ci.consumer.instance}") String instance) {
        this.instance = instance;
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(CiConsumerApplication.class, args);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        System.out.println("BOOTUI_CI_CONSUMER_PORT=" + port);
    }

    @GetMapping("/orders")
    List<String> orders() {
        return List.of("order", instance);
    }
}
