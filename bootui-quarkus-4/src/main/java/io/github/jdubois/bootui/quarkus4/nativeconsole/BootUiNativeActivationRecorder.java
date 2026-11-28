package io.github.jdubois.bootui.quarkus4.nativeconsole;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class BootUiNativeActivationRecorder {
    public void initialize(boolean included) {
        BootUiNativeActivation.initialize(included);
    }

    public void freeze() {
        BootUiNativeActivation.freeze();
    }
}
