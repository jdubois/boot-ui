package bootuicodepathsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** A configuration holder named as a bean: never instrumented by the code-paths sensor. */
@ConfigurationProperties
public class ShopProperties {

    public String getName() {
        return "shop";
    }
}
