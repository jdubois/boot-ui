package io.github.jdubois.bootui.webfluxsample.caught;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The stock of a product cannot be read: answered with a 404. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class StockUnavailableException extends RuntimeException {

    public StockUnavailableException(String sku, Throwable cause) {
        super("No stock known for " + sku, cause);
    }
}
