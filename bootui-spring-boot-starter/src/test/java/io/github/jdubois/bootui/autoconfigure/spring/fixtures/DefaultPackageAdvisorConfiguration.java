import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DefaultPackageAdvisorConfiguration {
    @Bean
    DefaultPackageAdvisorProduct advisorProduct() {
        return new DefaultPackageAdvisorProduct();
    }
}

class DefaultPackageAdvisorProduct {}
