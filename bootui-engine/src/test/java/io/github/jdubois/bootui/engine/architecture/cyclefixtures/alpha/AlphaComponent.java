package io.github.jdubois.bootui.engine.architecture.cyclefixtures.alpha;

import io.github.jdubois.bootui.engine.architecture.cyclefixtures.beta.BetaComponent;

public final class AlphaComponent {

    private final BetaComponent beta;

    public AlphaComponent(BetaComponent beta) {
        this.beta = beta;
    }

    public BetaComponent beta() {
        return beta;
    }
}
