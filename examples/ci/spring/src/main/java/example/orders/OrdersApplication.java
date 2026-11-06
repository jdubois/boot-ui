package example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

@SpringBootApplication
public class OrdersApplication {

    public static void main(String[] args) {
        var application = SpringApplication.run(OrdersApplication.class, args);
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        System.out.println("CI_EXAMPLE_PORT=" + port);
    }
}
