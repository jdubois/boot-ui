package io.github.jdubois.bootui.sample.caught;

import jakarta.ws.rs.NotFoundException;

/** The stock of a product cannot be read: answered with a 404. */
public class StockUnavailableException extends NotFoundException {

    public StockUnavailableException(String sku, Throwable cause) {
        super("No stock known for " + sku, cause);
    }
}
