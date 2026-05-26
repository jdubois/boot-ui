package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.DevToolsActionResult;
import io.github.bootui.core.BootUiDtos.DevToolsStatus;

/**
 * Bridge to optional Spring Boot DevTools APIs.
 */
public interface DevToolsBridge {

    DevToolsStatus status();

    DevToolsActionResult triggerLiveReload();

    DevToolsActionResult scheduleRestart();
}
