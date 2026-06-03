package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record BeanList(int total, List<BeanSummary> beans, PageMetadata page) {
    public BeanList(int total, List<BeanSummary> beans) {
        this(total, beans, new PageMetadata(total, beans.size(), 0, beans.size(), beans.size(), false));
    }
}
