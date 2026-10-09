package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record HttpHeaderDto(String name, List<String> values, boolean masked) {

    public HttpHeaderDto {
        values = DtoCollections.immutableCopy(values);
    }
}
