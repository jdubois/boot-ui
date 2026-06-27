package io.github.jdubois.bootui.engine.github;

import java.time.Duration;

public interface GitHubTokenProvider {

    Token token(Duration timeout);

    record Token(String value, String source) {}
}
