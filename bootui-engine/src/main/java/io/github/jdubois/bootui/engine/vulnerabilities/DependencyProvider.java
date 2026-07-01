package io.github.jdubois.bootui.engine.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.util.List;

public interface DependencyProvider {

    List<DependencyDto> dependencies();
}
