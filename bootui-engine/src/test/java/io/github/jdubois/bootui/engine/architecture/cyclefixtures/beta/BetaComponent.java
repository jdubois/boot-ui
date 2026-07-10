package io.github.jdubois.bootui.engine.architecture.cyclefixtures.beta;

import io.github.jdubois.bootui.engine.architecture.cyclefixtures.alpha.AlphaComponent;

public final class BetaComponent {

    private final AlphaComponent alpha;

    public BetaComponent(AlphaComponent alpha) {
        this.alpha = alpha;
    }

    public AlphaComponent alpha() {
        return alpha;
    }
}
