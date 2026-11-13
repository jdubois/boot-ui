package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record MappingsReport(int total, String basePath, List<MappingDto> mappings, PageMetadata page) {

    public MappingsReport {
        basePath = basePath == null ? "" : basePath;
        mappings = DtoCollections.immutableCopy(mappings);
    }

    public MappingsReport(int total, List<MappingDto> mappings, PageMetadata page) {
        this(total, "", mappings, page);
    }

    public MappingsReport(int total, List<MappingDto> mappings) {
        this(total, "", mappings, new PageMetadata(total, mappings.size(), 0, mappings.size(), mappings.size(), false));
    }
}
