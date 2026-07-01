package io.github.jdubois.bootui.engine.restapi.fixtures.bad;

/** Mutable response DTO exposing public setters (not immutable). */
public class OrderDto {

    private String reference;

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }
}
