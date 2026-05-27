package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.DependencyDto;

import java.util.List;

interface DependencyProvider {

    List<DependencyDto> dependencies();
}
