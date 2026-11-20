package io.github.jdubois.bootui.engine.github;

import java.time.Duration;

public interface GitHubTokenProvider {

    /**
     * Legacy lookup for github.com. Providers supporting other hosts must override
     * {@link #token(String, Duration)} rather than reusing an unscoped credential.
     */
    Token token(Duration timeout);

    default Token token(String repositoryHost, Duration timeout) {
        return "github.com".equals(GitHubRepositoryDetector.credentialHost(repositoryHost)) ? token(timeout) : null;
    }

    record Token(String value, String source) {
        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }
}
