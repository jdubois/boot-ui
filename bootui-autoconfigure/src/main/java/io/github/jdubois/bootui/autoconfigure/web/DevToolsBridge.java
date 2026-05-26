package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.DevToolsActionResult;
import io.github.jdubois.bootui.core.BootUiDtos.DevToolsStatus;

/**
 * Bridge to optional Spring Boot DevTools APIs.
 */
public interface DevToolsBridge {

    DevToolsStatus status();

    DevToolsActionResult triggerLiveReload();

    DevToolsActionResult scheduleRestart();
}
